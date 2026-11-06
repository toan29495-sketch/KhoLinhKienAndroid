package vn.kholinhkien

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.room.withTransaction
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

object ZipRestore {
    private const val MAX_ENTRIES = 10002
    private const val MAX_TOTAL_BYTES = 300L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 100L * 1024L * 1024L
    private const val MAX_PHOTO_BYTES = 20L * 1024L * 1024L
    private const val MAX_MANIFEST_BYTES = 1024L * 1024L

    private const val MIN_FREE_BYTES_BEFORE_RESTORE = 32L * 1024L * 1024L
    private const val MIN_FREE_BYTES_DURING_RESTORE = 16L * 1024L * 1024L
    private const val STALE_TEMP_AGE_MILLIS = 24L * 60L * 60L * 1000L
    private const val TEMP_PREFIX = "zip_restore_"

    suspend fun restore(context: Context, uri: Uri, dao: InventoryDao, ownerId: String): Pair<Int, Int> =
        restoreFromInput(context, { context.contentResolver.openInputStream(uri) ?: error("Không mở được tệp ZIP") }, dao, ownerId)

    suspend fun restore(context: Context, archive: File, dao: InventoryDao, ownerId: String): Pair<Int, Int> =
        restoreFromInput(context, {
            val canonical = archive.canonicalFile
            val inboxRoot = File(context.filesDir, "cloud_restore_inbox/$ownerId").canonicalFile
            require(canonical.parentFile == inboxRoot && canonical.isFile) { "Tệp backup không nằm trong inbox của tài khoản" }
            archive.inputStream()
        }, dao, ownerId)

    private suspend fun restoreFromInput(context: Context, openInput: () -> java.io.InputStream, dao: InventoryDao, ownerId: String): Pair<Int, Int> {
        require(isCanonicalOwnerId(ownerId)) {
            "Mã chủ kho không hợp lệ để khôi phục ZIP"
        }
        requireActiveOwner(ownerId)
        cleanupStaleTemporaryStages(context, ownerId)
        val availableBytes = runCatching { StatFs(context.cacheDir.absolutePath).availableBytes }
            .getOrElse { error("Không kiểm tra được dung lượng trống: ${it.message ?: "lỗi hệ thống"}") }
        require(availableBytes >= MIN_FREE_BYTES_BEFORE_RESTORE) {
            "Bộ nhớ điện thoại còn quá ít (cần ít nhất 32 MB trống) để bắt đầu khôi phục. Hãy giải phóng bộ nhớ rồi thử lại."
        }
        val ownerTempPrefix = "$TEMP_PREFIX${ownerId.lowercase()}_"
        val stage = File(context.cacheDir, "$ownerTempPrefix${UUID.randomUUID()}")
        require(stage.mkdirs()) { "Không tạo được thư mục tạm để kiểm tra ZIP" }
        try {
            val input = openInput()
            val seen = mutableSetOf<String>()
            var totalBytes = 0L
            var entryCount = 0
            input.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        requireActiveOwner(ownerId)
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        require(++entryCount <= MAX_ENTRIES) { "ZIP có quá nhiều mục" }
                        require(name.isNotBlank() && !name.startsWith("/") && !name.contains("\\") &&
                            name.split("/").none { it == ".." || it.isBlank() }) {
                            "Tệp ZIP có đường dẫn không an toàn"
                        }
                        if (entry.isDirectory) {
                            require(name == "photos/") { "Thư mục trong ZIP không hợp lệ" }
                        } else {
                            require(seen.add(name)) { "ZIP chứa mục bị trùng: $name" }
                            val isPhoto = name.startsWith("photos/") &&
                                name.removePrefix("photos/").matches(Regex("[A-Za-z0-9._-]+"))
                            require(name == "manifest.json" || name == "parts.jsonl" ||
                                name == "movements.jsonl" || isPhoto) {
                                "Tệp ZIP chứa thành phần không hợp lệ: $name"
                            }
                            val limit = when {
                                name == "manifest.json" -> MAX_MANIFEST_BYTES
                                isPhoto -> MAX_PHOTO_BYTES
                                else -> MAX_ENTRY_BYTES
                            }
                            val target = File(stage, name)
                            require(target.canonicalPath.startsWith(stage.canonicalPath + File.separator)) {
                                "Đường dẫn giải nén không hợp lệ"
                            }
                            require(target.parentFile?.let { it.mkdirs() || it.isDirectory } == true) {
                                "Không tạo được thư mục giải nén"
                            }
                            var entryBytes = 0L
                            target.outputStream().buffered().use { output ->
                                while (true) {
                                    requireActiveOwner(ownerId)
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    entryBytes = Math.addExact(entryBytes, count.toLong())
                                    totalBytes = Math.addExact(totalBytes, count.toLong())
                                    require(entryBytes <= limit) { "Mục ZIP quá lớn: $name" }
                                    require(totalBytes <= MAX_TOTAL_BYTES) { "Tệp ZIP vượt giới hạn 300 MB" }
                                    requireAvailableSpace(context, MIN_FREE_BYTES_DURING_RESTORE + count)
                                    output.write(buffer, 0, count)
                                }
                                output.flush()
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }

            val manifestFile = File(stage, "manifest.json")
            val partsFile = File(stage, "parts.jsonl")
            val movementsFile = File(stage, "movements.jsonl")
            require(manifestFile.isFile && partsFile.isFile && movementsFile.isFile) {
                "ZIP thiếu manifest hoặc dữ liệu linh kiện/lịch sử"
            }
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            require(manifest.optInt("version") == 2) { "Phiên bản tệp ZIP không được hỗ trợ" }
            val backupOwner = manifest.optString("ownerId", "")
            require(backupOwner.isNotBlank() && backupOwner == ownerId) {
                "Bản sao lưu thiếu chủ sở hữu hoặc thuộc tài khoản khác. Không thể khôi phục vào kho này."
            }
            val expectedParts = manifest.optInt("partCount", -1)
            val expectedMovements = manifest.optInt("movementCount", -1)
            require(expectedParts in 0..500000 && expectedMovements in 0..1000000) {
                "Thông tin số lượng dữ liệu không hợp lệ"
            }
            require(countLines(partsFile) == expectedParts) { "Số lượng linh kiện trong ZIP không khớp" }
            require(countLines(movementsFile) == expectedMovements) { "Số lượng lịch sử trong ZIP không khớp" }

            // ZIP v2 is checksum-mandatory. Never accept a partially protected archive.
            val checksums = manifest.optJSONObject("checksums")
                ?: error("Manifest thiếu danh sách SHA-256 bắt buộc")
            val expectedNames = seen.filter { it != "manifest.json" }.toSet()
            val checksumNames = buildSet {
                val keys = checksums.keys()
                while (keys.hasNext()) add(keys.next())
            }
            require(checksumNames == expectedNames) {
                "Danh sách SHA-256 không khớp chính xác với các thành phần trong ZIP"
            }
            for (name in expectedNames) {
                val expected = checksums.optString(name, "")
                require(expected.matches(Regex("[a-fA-F0-9]{64}"))) { "Mã SHA-256 không hợp lệ: $name" }
                require(sha256(File(stage, name)).equals(expected, ignoreCase = true)) {
                    "Dữ liệu bị lỗi hoặc đã thay đổi: $name"
                }
            }
            val manifestDigest = sha256(manifestFile)
            val codes = HashSet<String>()
            val partSyncIds = HashSet<String>()
            val movementSyncIds = HashSet<String>()
            val photoEntries = HashSet<String>()
            partsFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val item = JSONObject(line)
                    val code = item.optString("code", "").trim()
                    require(code.isNotBlank() && item.optString("name", "").isNotBlank()) {
                        "Linh kiện có mã hoặc tên trống"
                    }
                    require(codes.add(code)) { "Tệp có mã linh kiện trùng: $code" }
                    val suppliedId = item.optString("syncId", "")
                    val stableId = stableRestoreId(suppliedId, "$ownerId:$manifestDigest:PART:$code")
                    require(partSyncIds.add(stableId)) { "Tệp có syncId linh kiện trùng: $stableId" }
                    require(item.optInt("quantity", 0) >= 0 && item.optInt("minQuantity", 0) >= 0) {
                        "Số lượng linh kiện không hợp lệ: $code"
                    }
                    val mode = item.optString("trackingMode", "SINGLE")
                    require(mode == "SINGLE" || mode == "BULK") { "Kiểu quản lý không hợp lệ: $code" }
                    val photo = item.optString("photoEntry", "")
                    if (photo.isNotBlank()) {
                        require(photo.startsWith("photos/") && File(stage, photo).isFile) {
                            "Thiếu ảnh của linh kiện: $code"
                        }
                        photoEntries.add(photo)
                    }
                }
            }
            val archivePhotoEntries = expectedNames.filter { it.startsWith("photos/") }.toSet()
            require(archivePhotoEntries == photoEntries) {
                "Danh sách ảnh trong ZIP không khớp với ảnh được linh kiện tham chiếu"
            }
            require(photoEntries.all { entry ->
                val extension = File(entry).extension.lowercase()
                extension in setOf("jpg", "jpeg", "png", "webp")
            }) { "ZIP chứa định dạng ảnh không được hỗ trợ" }

            movementsFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                var lineIndex = 0
                lines.filter { it.isNotBlank() }.forEach { line ->
                    lineIndex++
                    val item = JSONObject(line)
                    require(item.optString("code", "").isNotBlank() &&
                        item.optString("type", "").isNotBlank()) { "Lịch sử giao dịch không hợp lệ" }
                    val suppliedId = item.optString("syncId", "")
                    val stableId = stableRestoreId(suppliedId, "$ownerId:$manifestDigest:MOVEMENT:${lineIndex}")
                    require(movementSyncIds.add(stableId)) { "Tệp có syncId lịch sử trùng: $stableId" }
                    require(item.optInt("amount", 0) >= 0 && item.optInt("beforeQty", 0) >= 0 &&
                        item.optInt("afterQty", 0) >= 0) { "Số lượng trong lịch sử không hợp lệ" }
                }
            }

            // Persist a durable restore workspace first. Live parts/movements and sync state
            // are deliberately untouched until the cloud snapshot commit protocol is wired.
            val restoreId = UUID.nameUUIDFromBytes(
                "$ownerId:$manifestDigest".toByteArray(Charsets.UTF_8)
            ).toString()
            val active = dao.activeRestore(ownerId)
            require(active == null || active.restoreId == restoreId) {
                "Tài khoản đang có một lần khôi phục khác chưa hoàn tất. Hãy tiếp tục hoặc hủy lần đó trước."
            }
            val existing = dao.restoreJournal(ownerId, restoreId)
            if (existing == null) {
                // Recover the narrow crash window after mkdirs() but before journal insertion.
                val orphan = File(context.filesDir, "restore_staging/$restoreId").canonicalFile
                val root = File(context.filesDir, "restore_staging").canonicalFile
                require(orphan.path.startsWith(root.path + File.separator) && orphan.name == restoreId) {
                    "Đường dẫn staging mồ côi không an toàn"
                }
                if (orphan.exists()) require(orphan.deleteRecursively()) {
                    "Không dọn được thư mục staging dở dang trước khi thử lại"
                }
            }
            if (existing != null && existing.status == "STAGED") {
                require(RestoreStagingLifecycle.canResumeStaged(context, dao, ownerId, restoreId)) {
                    "Phiên staging đã hoàn thành nhưng dữ liệu không còn đầy đủ; cần kiểm tra thủ công."
                }
                return expectedParts to expectedMovements
            }
            require(existing == null || existing.status in setOf("STAGING", "FAILED")) {
                "Phiên khôi phục đang ở trạng thái ${existing?.status}; không thể ghi đè an toàn."
            }
            if (existing != null) {
                val oldDirectory = File(existing.stagedDirectory).canonicalFile
                val stagingRoot = File(context.filesDir, "restore_staging").canonicalFile
                require(oldDirectory.path.startsWith(stagingRoot.path + File.separator) &&
                    oldDirectory.name == restoreId) { "Đường dẫn staging cũ không an toàn" }
                AppDb.get(context).withTransaction {
                    val deleted = dao.deleteAbortableRestoreJournal(ownerId, restoreId)
                    check(deleted == 1) { "Trạng thái phiên đã thay đổi; không thể thử lại an toàn" }
                    dao.clearRestoreStagedRows(ownerId, restoreId)
                }
                if (oldDirectory.exists()) require(oldDirectory.deleteRecursively()) {
                    "Không dọn được staging lỗi trước khi thử lại"
                }
            }
            val durableStage = File(context.filesDir, "restore_staging/$restoreId")
            // Cache extraction and durable staging coexist briefly. Reserve the complete
            // photo-copy size plus a safety margin before creating the durable workspace.
            val photoBytes = photoEntries.sumOf { File(stage, it).length() }
            requireAvailableSpace(context, photoBytes + MIN_FREE_BYTES_DURING_RESTORE)
            require(durableStage.mkdirs()) { "Không tạo được vùng staging bền vững" }
            var journalPersisted = false
            try {
                val durablePhotos = File(durableStage, "photos")
                require(durablePhotos.mkdirs() || durablePhotos.isDirectory) {
                    "Không tạo được vùng ảnh staging"
                }
                photoEntries.sorted().forEach { entry ->
                    val source = File(stage, entry)
                    val target = File(durableStage, entry)
                    require(target.canonicalPath.startsWith(durableStage.canonicalPath + File.separator)) {
                        "Đường dẫn ảnh staging không hợp lệ"
                    }
                    require(target.parentFile?.let { it.mkdirs() || it.isDirectory } == true) {
                        "Không tạo được thư mục ảnh staging"
                    }
                    copyWithSpaceGuard(context, source, target)
                }

                requireActiveOwner(ownerId)
                requireAvailableSpace(context, MIN_FREE_BYTES_DURING_RESTORE)
                val reserved = dao.beginLocalRestoreIfSafe(RestoreJournal(
                    restoreId = restoreId,
                    ownerId = ownerId,
                    manifestSha256 = manifestDigest,
                    status = "STAGING",
                    stagedDirectory = durableStage.absolutePath,
                    partCount = expectedParts,
                    movementCount = expectedMovements,
                    snapshotCursor = 0L,
                    lastError = ""
                ))
                require(reserved) {
                    "Một phiên khôi phục khác vừa được tạo cho tài khoản này. Dữ liệu kho hiện tại được giữ nguyên; hãy tải lại danh sách phiên rồi tiếp tục phiên đang hoạt động."
                }
                journalPersisted = true

                suspend fun persistLines(file: File, entityType: String, expectedCount: Int) {
                    requireActiveOwner(ownerId)
                    var chunkIndex = 0
                    var rowIndex = 0
                    val batch = ArrayList<RestoreStagedRow>(200)
                    file.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            requireActiveOwner(ownerId)
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) continue
                            batch.add(RestoreStagedRow(
                                restoreId = restoreId,
                                ownerId = ownerId,
                                entityType = entityType,
                                chunkIndex = chunkIndex,
                                rowIndex = rowIndex,
                                payloadJson = JSONObject(line).toString()
                            ))
                            rowIndex++
                            if (batch.size == 200) {
                                // Each batch is durable and idempotently addressed by its row key.
                                requireAvailableSpace(context, MIN_FREE_BYTES_DURING_RESTORE)
                                dao.insertRestoreStagedRows(batch.toList())
                                batch.clear()
                                chunkIndex++
                            }
                        }
                    }
                    if (batch.isNotEmpty()) {
                        requireAvailableSpace(context, MIN_FREE_BYTES_DURING_RESTORE)
                        dao.insertRestoreStagedRows(batch.toList())
                    }
                    require(rowIndex == expectedCount) { "Số dòng staging $entityType không khớp" }
                }

                persistLines(partsFile, "PART", expectedParts)
                persistLines(movementsFile, "MOVEMENT", expectedMovements)
                require(dao.countStagedRestoreRows(ownerId, restoreId, "PART") == expectedParts &&
                    dao.countStagedRestoreRows(ownerId, restoreId, "MOVEMENT") == expectedMovements) {
                    "Dữ liệu staging chưa đầy đủ"
                }
                requireActiveOwner(ownerId)
                dao.saveRestoreJournal(dao.restoreJournal(ownerId, restoreId)!!.copy(
                    status = "STAGED", updatedAt = System.currentTimeMillis()
                ))
            } catch (cancelled: CancellationException) {
                // If the journal exists, preserve durable STAGING rows for deterministic re-import.
                // Before journal persistence, the directory is only an orphan and is safe to remove.
                if (!journalPersisted) runCatching { durableStage.deleteRecursively() }
                throw cancelled
            } catch (error: Exception) {
                if (journalPersisted) {
                    // ENOSPC may also prevent persisting FAILED. Preserve the original
                    // exception; the durable journal remains non-resumable until re-import.
                    runCatching {
                        dao.saveRestoreJournal(RestoreJournal(
                            restoreId = restoreId, ownerId = ownerId, manifestSha256 = manifestDigest,
                            status = "FAILED", stagedDirectory = durableStage.absolutePath,
                            partCount = expectedParts, movementCount = expectedMovements,
                            lastError = error.message ?: "Lỗi staging"
                        ))
                    }
                } else {
                    // No journal means no recovery contract: reclaim only this exact UUID stage.
                    runCatching { durableStage.deleteRecursively() }
                }
                throw error
            }
            return expectedParts to expectedMovements
        } finally {
            // Best-effort immediate cleanup. If Android refuses deletion (for example,
            // after ENOSPC or process interruption), the next restore removes this
            // uniquely named cache directory once it is older than the stale threshold.
            runCatching { stage.deleteRecursively() }
        }
    }


    private fun requireAvailableSpace(context: Context, requiredBytes: Long) {
        val available = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }
            .getOrElse { error("Không kiểm tra được dung lượng trống: ${it.message ?: "lỗi hệ thống"}") }
        require(requiredBytes >= 0L && available >= requiredBytes) {
            "Không đủ dung lượng để tiếp tục khôi phục (cần còn ${(requiredBytes / (1024L * 1024L)).coerceAtLeast(1)} MB). " +
                "Dữ liệu kho hiện tại chưa bị thay đổi; hãy giải phóng bộ nhớ rồi chọn lại ZIP."
        }
    }

    private fun copyWithSpaceGuard(context: Context, source: File, target: File) {
        source.inputStream().buffered().use { input ->
            FileOutputStream(target).use { rawOutput ->
                rawOutput.buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        requireAvailableSpace(context, MIN_FREE_BYTES_DURING_RESTORE + count)
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    rawOutput.fd.sync()
                }
            }
        }
        require(target.length() == source.length()) { "Ảnh staging bị thiếu dữ liệu khi sao chép" }
    }

    private fun requireActiveOwner(ownerId: String) {
        require(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đã thay đổi hoặc phiên đăng nhập hết hạn; đã dừng khôi phục ZIP"
        }
    }

    /** Removes only abandoned ZIP extraction directories from this app's cache. */
    internal fun cleanupStaleTemporaryStages(
        context: Context,
        ownerId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Int {
        require(isCanonicalOwnerId(ownerId)) {
            "Mã chủ kho không hợp lệ để dọn vùng ZIP tạm"
        }
        val cacheRoot = context.cacheDir.canonicalFile
        val ownerPrefix = "$TEMP_PREFIX${ownerId.lowercase()}_"
        // Legacy folders have no trustworthy owner marker. Never let one KTV's
        // cleanup reclaim an unscoped folder that may belong to another account.
        val candidates = cacheRoot.listFiles().orEmpty().filter {
            it.isDirectory && isCanonicalTempDirectoryName(it.name, ownerPrefix)
        }
        var removed = 0
        candidates.forEach { candidate ->
            runCatching {
                val canonical = candidate.canonicalFile
                // Require the listed path itself to be the canonical direct child. This
                // excludes symlinks, nested paths, regular files, and lookalike names.
                val isSafeDirectory = canonical.path == candidate.absoluteFile.path &&
                    canonical.parentFile == cacheRoot && canonical.isDirectory &&
                    isCanonicalTempDirectoryName(canonical.name, ownerPrefix)
                if (isSafeDirectory &&
                    nowMillis - candidate.lastModified() >= STALE_TEMP_AGE_MILLIS) {
                    if (candidate.deleteRecursively()) removed++
                }
            }
        }
        return removed
    }

    private fun isCanonicalTempDirectoryName(name: String, ownerPrefix: String): Boolean {
        if (!name.startsWith(ownerPrefix)) return false
        val suffix = name.removePrefix(ownerPrefix)
        return runCatching { UUID.fromString(suffix).toString() == suffix }.getOrDefault(false)
    }

    private fun isCanonicalOwnerId(value: String): Boolean {
        if (!Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(value)) {
            return false
        }
        return runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }
            .getOrDefault(false)
    }

    private fun stableRestoreId(raw: String, seed: String): String {
        if (raw.isNotBlank()) return UUID.fromString(raw).toString()
        return UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
    }

    private fun countLines(file: File): Int = file.useLines { lines -> lines.count { it.isNotBlank() } }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

}
