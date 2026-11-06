package vn.kholinhkien

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.storage.downloadAuthenticatedTo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class PreparedWeeklyCloudBackup(
    val archive: File,
    val ownerId: String,
    val createdAt: Long,
    val syncCursor: Long,
    val partCount: Int,
    val movementCount: Int,
    val sha256: String
)

object BackupManager {

    /**
     * Creates a verified, durable app-private ZIP from one consistent Room snapshot.
     * The owner-scoped Room journal is committed first so the same backupId, path,
     * and bytes can be resumed after process death without changing the TUS identity.
     */
    suspend fun prepareWeeklyCloudBackup(
        context: Context,
        dao: InventoryDao,
        ownerId: String,
        username: String,
        backupId: String,
        timestampMillis: Long = System.currentTimeMillis()
    ): PreparedWeeklyCloudBackup {
        require(isCanonicalOwnerId(ownerId)) { "Không xác định được tài khoản sở hữu kho hợp lệ" }
        val canonicalBackupId = runCatching { UUID.fromString(backupId).toString() }.getOrNull()
        require(canonicalBackupId == backupId) { "backupId phải là UUID chữ thường ở dạng canonical" }
        requireActiveOwner(ownerId)
        WeeklyBackupRecovery.recoverOwner(context, dao, ownerId)
        requireActiveOwner(ownerId)
        val directory = File(File(File(context.filesDir, "weekly-backup-staging"), ownerId), "")
        check(directory.exists() || directory.mkdirs()) { "Không tạo được vùng lưu backup tự động riêng tư" }
        val archive = File(directory, "$backupId.zip").canonicalFile
        check(archive.parentFile == directory.canonicalFile) { "Đường dẫn backup tự động vượt khỏi thư mục owner" }
        val existingJournal = dao.weeklyBackup(ownerId, backupId)
        val journal = dao.beginWeeklyBackup(
            WeeklyBackupJournal(
                backupId = backupId,
                ownerId = ownerId,
                state = "PREPARING",
                archivePath = archive.absolutePath,
                createdAt = existingJournal?.createdAt ?: timestampMillis
            )
        )
        check(journal.archivePath == archive.absolutePath) { "Journal không khớp đường dẫn archive đã đăng ký" }

        // Once READY/UPLOADING, the exact bytes and path are part of the TUS
        // retry identity. Never regenerate that archive under the same backupId.
        if (journal.state == "READY" || journal.state == "UPLOADING") {
            requireActiveOwner(ownerId)
            check(archive.isFile && archive.length() == journal.archiveSizeBytes &&
                sha256File(archive).equals(journal.sha256, ignoreCase = true)) {
                "Archive của backup đang chờ upload bị mất/thay đổi; không được tạo ZIP mới dưới cùng backupId. Hãy tạo backupId mới."
            }
            return PreparedWeeklyCloudBackup(
                archive, ownerId, journal.createdAt, journal.snapshotCursor,
                journal.partCount, journal.movementCount, journal.sha256
            )
        }
        check(journal.state == "PREPARING") {
            "Journal backup tự động đang ở trạng thái ${journal.state}; cần tạo backupId mới"
        }

        try {
            val backupCoroutineContext = currentCoroutineContext()
            val snapshot = dao.captureAutomaticBackupSnapshot(ownerId)
                ?: error("Kho còn giao dịch chưa đồng bộ hoặc đang khôi phục; chưa thể tạo backup tự động")
            check(snapshot.ownerId == ownerId) { "Snapshot không thuộc tài khoản hiện tại" }
            archive.delete() // PREPARING means no upload has been allowed to start.
            requireActiveOwner(ownerId)
            FileOutputStream(archive).use { rawOutput ->
                WeeklyBackupGuardedOutputStream(
                    delegate = rawOutput,
                    stagingDirectory = directory,
                    reserveBytes = WEEKLY_BACKUP_FREE_RESERVE_BYTES,
                    maxBytes = WEEKLY_BACKUP_MAX_ARCHIVE_BYTES,
                    checkActive = { backupCoroutineContext.ensureActive() }
                ).use { output ->
                    createZip(
                    context = context,
                    destination = output,
                    ownerId = ownerId,
                    username = username,
                    timestampMillis = journal.createdAt,
                    parts = snapshot.parts,
                    movements = snapshot.movements,
                    snapshotCursor = snapshot.syncCursor,
                    backupKind = "WEEKLY_AUTO"
                    )
                }
            }
            requireActiveOwner(ownerId)
            val verifiedEntries = FileInputStream(archive).use { BackupArchiveInspector.verify(it, ownerId) }
            RandomAccessFile(archive, "r").use { file ->
                check(ZipCentralDirectoryVerifier.verify(file.channel) == verifiedEntries) {
                    "Central Directory không khớp nội dung backup tự động đã xác minh"
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(archive).buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    backupCoroutineContext.ensureActive()
                    requireActiveOwner(ownerId)
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val sha256 = digest.digest().toHex()
            val sizeBytes = archive.length()
            requireActiveOwner(ownerId)
            check(dao.markWeeklyBackupReady(
                ownerId, backupId, snapshot.syncCursor, snapshot.parts.size,
                snapshot.movements.size, sizeBytes, sha256
            ) == 1) { "Không thể ghi trạng thái READY vào journal backup tự động" }
            return PreparedWeeklyCloudBackup(
                archive, ownerId, journal.createdAt, snapshot.syncCursor,
                snapshot.parts.size, snapshot.movements.size, sha256
            )
        } catch (error: Throwable) {
            // Keep PREPARING journal for safe same-ID reconstruction; no TUS
            // upload can begin until READY has been durably committed.
            archive.delete()
            throw error
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    /** Fixed backup name: SL + stable KTV username + ddMMHHmm. */
    fun backupFileName(username: String, timestampMillis: Long = System.currentTimeMillis()): String {
        val ktvToken = username.trim().uppercase(Locale.ROOT)
            .filter { it in 'A'..'Z' || it in '0'..'9' }
            .ifBlank { "KTV" }
        val dateTime = SimpleDateFormat("ddMMHHmm", Locale.ROOT).format(Date(timestampMillis))
        return "SL${ktvToken}${dateTime}.zip"
    }

    /** Saves directly to a fixed, app-generated filename; no rename picker is shown. */
    suspend fun saveBackup(
        context: Context,
        username: String,
        ownerId: String,
        parts: List<Part>,
        movements: List<Movement>,
        timestampMillis: Long = System.currentTimeMillis()
    ): String {
        require(isCanonicalOwnerId(ownerId)) { "Không xác định được tài khoản sở hữu kho hợp lệ" }
        requireActiveOwner(ownerId)
        val fileName = backupFileName(username, timestampMillis)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/KiemSoatLinhKien"
            val ownerMarker = "KiemSoatLinhKienBackup:$ownerId"
            cleanupStalePendingBackups(resolver, collection, relativePath, ownerMarker, timestampMillis)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection,
                arrayOf(fileName, relativePath + "/"), null)?.use { cursor ->
                check(!cursor.moveToFirst()) { "Đã tồn tại bản sao lưu $fileName. Hãy tạo bản sao lưu ở phút tiếp theo để không ghi đè." }
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.DESCRIPTION, ownerMarker)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("Không tạo được tệp sao lưu trong Downloads")
            try {
                val output = resolver.openOutputStream(uri) ?: error("Không mở được tệp sao lưu")
                createZip(context, output, ownerId, username, timestampMillis, parts, movements)
                requireActiveOwner(ownerId)
                val readBack = resolver.openInputStream(uri) ?: error("Không đọc lại được bản sao lưu vừa tạo")
                val verifiedEntries = BackupArchiveInspector.verify(readBack, ownerId)
                val descriptor = resolver.openFileDescriptor(uri, "r")
                    ?: error("Không mở được ZIP để kiểm tra Central Directory")
                descriptor.use { pfd ->
                    val channel = FileInputStream(pfd.fileDescriptor).channel
                    val centralEntries = ZipCentralDirectoryVerifier.verify(channel)
                    check(centralEntries == verifiedEntries) {
                        "Central Directory không khớp các mục đã đọc và xác minh"
                    }
                }
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                requireActiveOwner(ownerId)
                check(resolver.update(uri, published, null, null) == 1) { "Không thể hoàn tất lưu bản sao lưu" }
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
            return "Downloads/KiemSoatLinhKien/$fileName"
        }

        // Android 8/9 fallback: app-specific Documents folder, still with an immutable generated name.
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "KiemSoatLinhKien")
        check(directory.exists() || directory.mkdirs()) { "Không tạo được thư mục sao lưu" }
        cleanupStalePartialBackups(directory, ownerId, timestampMillis)
        val target = File(directory, fileName)
        check(!target.exists()) { "Đã tồn tại bản sao lưu $fileName; hãy thử lại sau một phút." }
        val partial = File(directory, ".backup_${ownerId}_${UUID.randomUUID()}.partial")
        try {
            partial.outputStream().buffered().use { createZip(context, it, ownerId, username, timestampMillis, parts, movements) }
            requireActiveOwner(ownerId)
            val verifiedEntries = partial.inputStream().use { BackupArchiveInspector.verify(it, ownerId) }
            RandomAccessFile(partial, "r").use { archive ->
                val centralEntries = ZipCentralDirectoryVerifier.verify(archive.channel)
                check(centralEntries == verifiedEntries) {
                    "Central Directory không khớp các mục đã đọc và xác minh"
                }
            }
            requireActiveOwner(ownerId)
            check(!target.exists()) { "Bản sao lưu cùng tên vừa được tạo; không thể ghi đè tệp hiện có." }
            check(partial.renameTo(target)) { "Không thể hoàn tất bản sao lưu. Tệp tạm sẽ được dọn." }
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
        return target.absolutePath
    }

    /** Removes only this owner's abandoned Android 8/9 partial files older than 24 hours. */
    private fun cleanupStalePartialBackups(directory: File, ownerId: String, nowMillis: Long) {
        if (!isCanonicalOwnerId(ownerId)) return
        val canonicalRoot = directory.canonicalFile
        val prefix = ".backup_${ownerId}_"
        val suffixPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.partial$")
        directory.listFiles()?.forEach { candidate ->
            val nameTail = candidate.name.removePrefix(prefix)
            if (!candidate.name.startsWith(prefix) || !suffixPattern.matches(nameTail)) return@forEach
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            if (!candidate.isFile || canonical != candidate.absoluteFile || canonical.parentFile != canonicalRoot) return@forEach
            val age = nowMillis - candidate.lastModified()
            if (candidate.lastModified() > 0L && age >= STALE_TEMP_AGE_MILLIS) candidate.delete()
        }
    }

    /** Removes only old, still-pending MediaStore rows carrying this exact owner marker. */
    private fun cleanupStalePendingBackups(
        resolver: android.content.ContentResolver,
        collection: android.net.Uri,
        relativePath: String,
        ownerMarker: String,
        nowMillis: Long
    ) {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.DESCRIPTION}=?"
        val staleUris = mutableListOf<android.net.Uri>()
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED), selection,
            arrayOf(relativePath + "/", ownerMarker), null)?.use { cursor ->
            val idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            val dateColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            if (idColumn < 0 || dateColumn < 0) return@use
            while (cursor.moveToNext()) {
                val createdSeconds = cursor.getLong(dateColumn)
                if (createdSeconds <= 0L || nowMillis - createdSeconds * 1000L < STALE_TEMP_AGE_MILLIS) continue
                staleUris += android.content.ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
            }
        }
        staleUris.forEach { resolver.delete(it, null, null) }
    }

    private suspend fun createZip(
        context: Context,
        destination: OutputStream,
        ownerId: String,
        username: String,
        timestampMillis: Long,
        parts: List<Part>,
        movements: List<Movement>,
        snapshotCursor: Long? = null,
        backupKind: String = "MANUAL"
    ) {
        require(isCanonicalOwnerId(ownerId)) { "Không xác định được tài khoản sở hữu kho hợp lệ" }
        val backupCoroutineContext = currentCoroutineContext()
        val temporaryDirectory = File(context.cacheDir, "backup_photos_${System.nanoTime()}")

        try {
            requireActiveOwner(ownerId)
            check(temporaryDirectory.mkdirs()) { "Không tạo được thư mục ảnh tạm để sao lưu" }
            // Map the stored path to a ZIP entry. Cloud objects are downloaded only
            // into this temporary cache directory; local pending-upload files are
            // read in place and are never moved or deleted by backup.
            val photoFiles = linkedMapOf<String, File>()
            val photoEntries = linkedMapOf<String, String>()
            var cloudPhotoCacheBytes = 0L
            for (part in parts) {
                backupCoroutineContext.ensureActive()
                requireActiveOwner(ownerId)
                val path = part.photoPath.trim()
                if (path.isBlank() || photoEntries.containsKey(path)) continue

                val source = if (File(path).isAbsolute) {
                    validateLocalPhotoFile(context, File(path), ownerId)
                } else {
                    validateCloudPath(path, ownerId)
                    val extension = path.substringAfterLast('.', "jpg").lowercase()
                        .takeIf { it in ALLOWED_EXTENSIONS }
                        ?: error("Định dạng ảnh cloud không được hỗ trợ: $path")
                    // Keep a reserve for the app and filesystem while downloading. The
                    // storage API writes to a file, so preflight against the largest
                    // permitted image before each transfer rather than risking a full cache.
                    require(temporaryDirectory.usableSpace >= MAX_PHOTO_BYTES + MIN_CACHE_RESERVE_BYTES + WEEKLY_BACKUP_FREE_RESERVE_BYTES) {
                        "Bộ nhớ tạm không đủ để tải ảnh sao lưu an toàn. Hãy giải phóng ít nhất 52 MB rồi thử lại."
                    }
                    val target = File(temporaryDirectory, "${photoFiles.size + 1}.$extension")
                    SupabaseClientProvider.client.storage.from("part-images")
                        .downloadAuthenticatedTo(path, target)
                    // A transfer may finish after the user has switched accounts.
                    // Never let that result proceed into this owner's archive.
                    requireActiveOwner(ownerId)
                    val downloadedBytes = target.length()
                    require(target.isFile && downloadedBytes in 1..MAX_PHOTO_BYTES) {
                        "Ảnh cloud không hợp lệ hoặc vượt giới hạn 20 MB: ${target.name}"
                    }
                    cloudPhotoCacheBytes += downloadedBytes
                    require(cloudPhotoCacheBytes <= MAX_TOTAL_BYTES) {
                        "Tổng dung lượng ảnh cloud tạm vượt giới hạn 300 MB cho một lần sao lưu"
                    }
                    target
                }

                require(source.isFile && source.length() in 1..MAX_PHOTO_BYTES) {
                    "Ảnh không hợp lệ hoặc vượt giới hạn 20 MB: ${source.name}"
                }
                val extension = source.extension.lowercase().takeIf { it in ALLOWED_EXTENSIONS }
                    ?: "jpg"
                val entryName = "photos/${photoFiles.size + 1}.$extension"
                photoFiles[path] = source
                photoEntries[path] = entryName
            }

            // Keep backup output within the same uncompressed limits accepted by ZipRestore.
            // This prevents creating an archive that this app would later refuse to restore.
            require(photoFiles.size + 3 <= MAX_ENTRIES) {
                "Bản sao lưu có quá nhiều ảnh (tối đa ${MAX_ENTRIES - 3} ảnh)"
            }

            // Do not open/truncate the destination until every referenced photo
            // has been found and every cloud download has completed successfully.
            requireActiveOwner(ownerId)
            ZipOutputStream(destination.buffered()).use { zip ->
                val checksums = JSONObject()
                var totalUncompressedBytes = 0L
                val entryByteCounts = mutableMapOf<String, Long>()
                fun accountBytes(entryName: String, count: Int, entryLimit: Long) {
                    require(count >= 0) { "Kích thước mục ZIP không hợp lệ: $entryName" }
                    totalUncompressedBytes += count.toLong()
                    require(totalUncompressedBytes <= MAX_TOTAL_BYTES) {
                        "Bản sao lưu vượt giới hạn tổng 300 MB sau giải nén"
                    }
                    val currentEntryBytes = entryByteCounts[entryName] ?: 0L
                    val nextEntryBytes = currentEntryBytes + count.toLong()
                    require(nextEntryBytes <= entryLimit) { "Mục ZIP quá lớn: $entryName" }
                    entryByteCounts[entryName] = nextEntryBytes
                }

                zip.putNextEntry(ZipEntry("parts.jsonl"))
                val partDigest = MessageDigest.getInstance("SHA-256")
                parts.forEach { p ->
                    backupCoroutineContext.ensureActive()
                    requireActiveOwner(ownerId)
                    val item = JSONObject()
                        .put("syncId", p.syncId).put("code", p.code).put("name", p.name)
                        .put("quantity", p.quantity).put("minQuantity", p.minQuantity)
                        .put("unit", p.unit).put("photoEntry", photoEntries[p.photoPath.trim()] ?: "")
                        .put("note", p.note).put("updatedAt", p.updatedAt)
                        .put("trackingMode", p.trackingMode)
                    val line = (item.toString() + "\n").toByteArray(Charsets.UTF_8)
                    accountBytes("parts.jsonl", line.size, MAX_JSON_ENTRY_BYTES)
                    partDigest.update(line)
                    zip.write(line)
                }
                checksums.put("parts.jsonl", partDigest.digest().toHex())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("movements.jsonl"))
                val movementDigest = MessageDigest.getInstance("SHA-256")
                movements.forEach { m ->
                    backupCoroutineContext.ensureActive()
                    requireActiveOwner(ownerId)
                    val item = JSONObject()
                        .put("syncId", m.syncId).put("code", m.code).put("partName", m.partName)
                        .put("type", m.type).put("amount", m.amount)
                        .put("beforeQty", m.beforeQty).put("afterQty", m.afterQty)
                        .put("reason", m.reason).put("machineOrStore", m.machineOrStore)
                        .put("time", m.time).put("ownerName", m.ownerName)
                    val line = (item.toString() + "\n").toByteArray(Charsets.UTF_8)
                    accountBytes("movements.jsonl", line.size, MAX_JSON_ENTRY_BYTES)
                    movementDigest.update(line)
                    zip.write(line)
                }
                checksums.put("movements.jsonl", movementDigest.digest().toHex())
                zip.closeEntry()

                photoFiles.forEach { (storedPath, file) ->
                    backupCoroutineContext.ensureActive()
                    requireActiveOwner(ownerId)
                    val entryName = photoEntries.getValue(storedPath)
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            backupCoroutineContext.ensureActive()
                            requireActiveOwner(ownerId)
                            val count = input.read(buffer)
                            if (count < 0) break
                            accountBytes(entryName, count, MAX_PHOTO_BYTES)
                            digest.update(buffer, 0, count)
                            zip.write(buffer, 0, count)
                        }
                    }
                    checksums.put(entryName, digest.digest().toHex())
                    zip.closeEntry()
                }

                val manifest = JSONObject()
                    .put("version", 2)
                    .put("ownerId", ownerId)
                    .put("backupUsername", username.trim().uppercase(Locale.ROOT))
                    .put("createdAt", timestampMillis)
                    .put("fileName", backupFileName(username, timestampMillis))
                    .put("partCount", parts.size)
                    .put("movementCount", movements.size)
                    .put("backupKind", backupKind)
                    .put("checksums", checksums)
                if (snapshotCursor != null) {
                    require(snapshotCursor >= 0L) { "Cursor snapshot không hợp lệ" }
                    manifest.put("snapshotCursor", snapshotCursor)
                }
                val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
                require(manifestBytes.size.toLong() <= MAX_MANIFEST_BYTES) {
                    "Thông tin manifest của bản sao lưu quá lớn"
                }
                accountBytes("manifest.json", manifestBytes.size, MAX_MANIFEST_BYTES)
                backupCoroutineContext.ensureActive()
                requireActiveOwner(ownerId)
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestBytes)
                zip.closeEntry()
            }
        } finally {
            // Never retain downloaded cloud images after the ZIP operation.
            temporaryDirectory.deleteRecursively()
        }
    }

    /**
     * Local photo references must stay inside app-private storage. New pending
     * uploads are owner-scoped; only the explicitly named legacy pending files
     * remain readable for backward compatibility with older Outbox rows.
     */
    private suspend fun validateLocalPhotoFile(context: Context, source: File, ownerId: String): File {
        require(isCanonicalOwnerId(ownerId)) { "Mã chủ kho không hợp lệ khi sao lưu ảnh" }
        val canonicalSource = source.canonicalFile
        val privateRoot = context.filesDir.canonicalFile
        require(canonicalSource.path.startsWith(privateRoot.path + File.separator)) {
            "Ảnh cục bộ nằm ngoài vùng dữ liệu riêng của ứng dụng"
        }

        val pendingRoot = File(privateRoot, "pending_part_photos").canonicalFile
        if (canonicalSource.path.startsWith(pendingRoot.path + File.separator)) {
            val ownerDirectory = File(pendingRoot, ownerId).canonicalFile
            val isOwnerScoped = canonicalSource.parentFile == ownerDirectory
            val isLegacyPending = canonicalSource.parentFile == pendingRoot &&
                canonicalSource.name.startsWith("pending_")
            require(isOwnerScoped || isLegacyPending) {
                "Ảnh chờ đồng bộ không thuộc tài khoản đang sao lưu"
            }
        }
        // All legacy absolute paths (including old part_photos/ restores) need an
        // exclusive owner reference. This prevents a shared file from entering a
        // different KTV's ZIP even when it is inside app-private storage.
        require(AppDb.get(context).dao().isLocalPhotoReferencedOnlyByOwner(ownerId, canonicalSource.path)) {
            "Không thể xác minh ảnh cục bộ chỉ thuộc tài khoản đang sao lưu"
        }
        require(canonicalSource.isFile) {
            "Ảnh cục bộ không còn tồn tại: ${source.name}"
        }
        return canonicalSource
    }


    /** Fail closed whenever the authenticated account no longer owns this operation. */
    private fun requireActiveOwner(ownerId: String) {
        val activeId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        require(activeId == ownerId) {
            "Tài khoản đã thay đổi hoặc phiên đăng nhập hết hạn; đã dừng sao lưu để bảo vệ dữ liệu"
        }
    }

    private fun isCanonicalOwnerId(value: String): Boolean {
        if (!Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(value)) {
            return false
        }
        return runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }
            .getOrDefault(false)
    }

    private fun validateCloudPath(path: String, ownerId: String) {
        require(!path.startsWith('/') && !path.contains('\\')) { "Đường dẫn ảnh cloud không hợp lệ" }
        val segments = path.split('/')
        require(segments.size >= 2 && segments.first() == ownerId &&
            segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Ảnh cloud không thuộc tài khoản hiện tại hoặc đường dẫn không an toàn"
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val MAX_PHOTO_BYTES = 20L * 1024L * 1024L
    private const val MAX_JSON_ENTRY_BYTES = 100L * 1024L * 1024L
    private const val MAX_MANIFEST_BYTES = 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 300L * 1024L * 1024L
    private const val MIN_CACHE_RESERVE_BYTES = 32L * 1024L * 1024L
    private const val WEEKLY_BACKUP_FREE_RESERVE_BYTES = 16L * 1024L * 1024L
    private const val WEEKLY_BACKUP_MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L
    private const val STALE_TEMP_AGE_MILLIS = 24L * 60L * 60L * 1000L
    private const val MAX_ENTRIES = 10002
    private val ALLOWED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
}
