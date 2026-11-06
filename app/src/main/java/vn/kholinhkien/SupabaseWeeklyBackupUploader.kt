package vn.kholinhkien

import android.content.Context
import android.os.StatFs
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.storage.createOrContinueUpload
import io.github.jan.supabase.storage.downloadAuthenticatedTo
import io.ktor.http.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Cloud upload gateway for the automatic backup ZIP.
 *
 * Called by the owner-scoped WeeklyBackupWorker after a durable Room journal and
 * app-private ZIP exist. Upload is resumable and remains gated by authenticated
 * owner checks, remote metadata registration, authenticated readback and SHA-256.
 *
 * supabase-kt is pinned to 3.0.1. Its File-based resumable API supports the
 * UploadOptionBuilder lambda, so this adapter explicitly sets application/zip
 * and upsert=false. The SDK fingerprints resumable sessions by source file path
 * and size: the caller must preserve the same archive path and bytes across
 * retries. Do not rebuild a different ZIP under the same backupId after process
 * death; Step162 persists an owner-scoped upload journal and durable staged ZIP.
 */
internal class SupabaseWeeklyBackupUploader(
    private val context: Context,
    private val client: SupabaseClient = SupabaseClientProvider.client,
    private val dao: InventoryDao = AppDb.get(context).dao()
) {
    data class Result(
        val backupId: String,
        val storagePath: String,
        val status: String,
        val archiveSizeBytes: Long,
        val sha256: String
    )

    suspend fun uploadAndVerify(
        prepared: PreparedWeeklyCloudBackup,
        backupId: String
    ): Result {
        requireCanonicalUuid(prepared.ownerId, "ownerId")
        requireCanonicalUuid(backupId, "backupId")
        val uploadCoroutineContext = currentCoroutineContext()
        uploadCoroutineContext.ensureActive()
        requireActiveOwner(prepared.ownerId)
        WeeklyBackupRecovery.recoverOwner(context, dao, prepared.ownerId)
        uploadCoroutineContext.ensureActive()
        requireActiveOwner(prepared.ownerId)
        try {
            validatePreparedArchive(prepared, prepared.archive.length())
        } catch (invalid: IllegalArgumentException) {
            throw PermanentWeeklyBackupException(invalid.message ?: "Archive backup không hợp lệ")
        }
        val journal = dao.weeklyBackup(prepared.ownerId, backupId)
            ?: error("Không tìm thấy journal bền vững cho backup tuần")
        if (journal.archivePath != prepared.archive.canonicalPath ||
            journal.state !in setOf("READY", "UPLOADING") ||
            journal.archiveSizeBytes != prepared.archive.length() ||
            !journal.sha256.equals(prepared.sha256, ignoreCase = true)) {
            throw PermanentWeeklyBackupException(
                "Journal không khớp archive/metadata; từ chối upload để tránh lệch TUS fingerprint"
            )
        }
        // Persist UPLOADING before any remote registration/upload. If Android kills
        // this process at any later point, the next Worker resumes this exact journal
        // and staged file rather than inventing a new backup identity.
        uploadCoroutineContext.ensureActive()
        requireActiveOwner(prepared.ownerId)
        require(dao.markWeeklyBackupUploading(prepared.ownerId, backupId) == 1) {
            "Không thể ghi trạng thái UPLOADING vào journal"
        }

        val expectedPath = "${prepared.ownerId}/$backupId.zip"
        uploadCoroutineContext.ensureActive()
        requireActiveOwner(prepared.ownerId)
        val registration = beginRegistration(prepared, backupId, journal.archiveSizeBytes)
        if (registration.storagePath != expectedPath) {
            throw PermanentWeeklyBackupException("Máy chủ trả đường dẫn backup không khớp owner/backupId")
        }
        if (registration.status != "PENDING" && registration.status != "VERIFIED") {
            throw PermanentWeeklyBackupException(
                "Bản sao lưu đang ở trạng thái không thể tiếp tục: ${registration.status}"
            )
        }
        requireActiveOwner(prepared.ownerId)

        val verifyFile = File(context.cacheDir, ".verify_weekly_${prepared.ownerId}_$backupId.zip")
        try {
            if (registration.status == "PENDING") {
                // The ZIP is the immutable backup payload. Original photo objects may
                // change or disappear after preparation; never read them again here.
                // Re-hash the exact staged ZIP at the last boundary before handing the
                // File to TUS, so a same-path/same-size mutation cannot reuse its old
                // backupId or resumable fingerprint unnoticed.
                try {
                    validatePreparedArchive(prepared, journal.archiveSizeBytes)
                } catch (invalid: IllegalArgumentException) {
                    throw PermanentWeeklyBackupException(
                        invalid.message ?: "ZIP backup đã thay đổi trước khi upload"
                    )
                }
                uploadCoroutineContext.ensureActive()
                requireActiveOwner(prepared.ownerId)
                val uploadError = try {
                    val upload = client.storage.from(BUCKET)
                        .resumable.createOrContinueUpload(expectedPath, prepared.archive) {
                            upsert = false
                            contentType = ContentType.parse("application/zip")
                        }
                    requireActiveOwner(prepared.ownerId)
                    upload.startOrResumeUploading()
                    requireActiveOwner(prepared.ownerId)
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    error
                }

                // If a previous attempt completed the object but lost its response,
                // the immutable path may already exist. Accept only after a full
                // authenticated download and byte-for-byte SHA-256 verification.
                if (uploadError != null) {
                    val existingVerified = verifyStoredObject(
                        ownerId = prepared.ownerId,
                        storagePath = expectedPath,
                        target = verifyFile,
                        expectedSize = prepared.archive.length(),
                        expectedSha256 = prepared.sha256
                    )
                    if (!existingVerified) throw uploadError
                }
            }

            requireActiveOwner(prepared.ownerId)
            if (!verifyStoredObject(
                    ownerId = prepared.ownerId,
                    storagePath = expectedPath,
                    target = verifyFile,
                    expectedSize = prepared.archive.length(),
                    expectedSha256 = prepared.sha256
                )) {
                throw PermanentWeeklyBackupException("Bản sao lưu trên Cloud không khớp kích thước hoặc SHA-256")
            }

            // beginRegistration is also the reconciliation read after process death.
            // If the server already committed VERIFIED before Android died, its
            // owner-scoped metadata was matched above and the object has just been
            // downloaded + SHA-256 checked again. Do not require a second completion
            // transition; commit that authoritative result into the local journal.
            val status = if (registration.status == "VERIFIED") {
                uploadCoroutineContext.ensureActive()
                requireActiveOwner(prepared.ownerId)
                "VERIFIED"
            } else {
                uploadCoroutineContext.ensureActive()
                requireActiveOwner(prepared.ownerId)
                val completedRaw = client.postgrest.rpc(
                    "complete_inventory_backup",
                    parameters = buildJsonObject {
                        put("p_backup_id", backupId)
                        put("p_verified_sha256", prepared.sha256.lowercase())
                    }
                )
                requireActiveOwner(prepared.ownerId)
                val completed = parseSingleRpcRow(completedRaw.toString())
                val returnedId = completed["backup_id"]?.jsonPrimitive?.contentOrNull
                val completedStatus = completed["status"]?.jsonPrimitive?.contentOrNull
                require(returnedId == backupId && completedStatus == "VERIFIED") {
                    "Máy chủ không xác nhận đúng bản sao lưu đã kiểm tra"
                }
                completedStatus
            }
            uploadCoroutineContext.ensureActive()
            requireActiveOwner(prepared.ownerId)
            require(dao.markWeeklyBackupVerified(prepared.ownerId, backupId) == 1) {
                "Cloud đã VERIFIED nhưng không thể chốt journal cục bộ; giữ nguyên ZIP để đối soát/retry"
            }
            requireActiveOwner(prepared.ownerId)
            check(prepared.archive.delete() || !prepared.archive.exists()) {
                "Backup đã VERIFIED nhưng chưa dọn được ZIP staging cục bộ"
            }

            return Result(
                backupId = backupId,
                storagePath = expectedPath,
                status = status,
                archiveSizeBytes = journal.archiveSizeBytes,
                sha256 = prepared.sha256.lowercase()
            )
        } finally {
            // The verification copy is disposable and never becomes a persistent photo/cache.
            verifyFile.delete()
        }
    }

    private suspend fun beginRegistration(
        prepared: PreparedWeeklyCloudBackup,
        backupId: String,
        expectedArchiveSizeBytes: Long
    ): Registration {
        requireActiveOwner(prepared.ownerId)
        val raw = client.postgrest.rpc(
            "begin_inventory_backup",
            parameters = buildJsonObject {
                put("p_backup_id", backupId)
                put("p_backup_kind", BACKUP_KIND_WEEKLY)
                put("p_snapshot_cursor", prepared.syncCursor)
                put("p_archive_size_bytes", expectedArchiveSizeBytes)
                put("p_sha256", prepared.sha256.lowercase())
                put("p_part_count", prepared.partCount)
                put("p_movement_count", prepared.movementCount)
            }
        )
        requireActiveOwner(prepared.ownerId)
        val row = parseSingleRpcRow(raw.toString())
        val returnedId = row["backup_id"]?.jsonPrimitive?.contentOrNull
        val path = row["storage_path"]?.jsonPrimitive?.contentOrNull
        val status = row["status"]?.jsonPrimitive?.contentOrNull
        require(returnedId == backupId && !path.isNullOrBlank() && !status.isNullOrBlank()) {
            "Máy chủ trả metadata đăng ký backup không hợp lệ"
        }
        return Registration(path, status)
    }

    private suspend fun verifyStoredObject(
        ownerId: String,
        storagePath: String,
        target: File,
        expectedSize: Long,
        expectedSha256: String
    ): Boolean {
        requireActiveOwner(ownerId)
        require(expectedSize > 0L && expectedSize <= MAX_ARCHIVE_BYTES)
        // Remove a partial verification copy from a prior interrupted attempt before
        // measuring free space. The canonical staged ZIP is never touched here.
        if (target.exists() && !target.delete()) {
            throw WeeklyBackupStorageException("Không dọn được tệp xác minh backup dở dang")
        }
        val available = StatFs(context.cacheDir.absolutePath).availableBytes
        if (available < expectedSize + MIN_FREE_BYTES) {
            throw WeeklyBackupStorageException(
                "Không đủ dung lượng tạm để xác minh backup; cần thêm ${expectedSize + MIN_FREE_BYTES - available} byte"
            )
        }
        try {
            client.storage.from(BUCKET).downloadAuthenticatedTo(storagePath, target)
            requireActiveOwner(ownerId)
            if (!target.isFile || target.length() != expectedSize) return false
            val actualDigest = sha256File(target, ownerId)
            requireActiveOwner(ownerId)
            return actualDigest.equals(expectedSha256, ignoreCase = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            target.delete()
        }
    }

    private suspend fun validatePreparedArchive(
        prepared: PreparedWeeklyCloudBackup,
        expectedSizeBytes: Long
    ) {
        val archive = prepared.archive.canonicalFile
        val stagingRoot = File(File(context.filesDir, "weekly-backup-staging"), prepared.ownerId).canonicalFile
        require(archive.parentFile == stagingRoot && archive.extension.equals("zip", ignoreCase = true)) {
            "ZIP backup phải nằm trong thư mục staging riêng tư, đúng owner"
        }
        if (!archive.isFile || !archive.canRead()) {
            throw WeeklyBackupStorageException(
                "Chưa truy cập được ZIP staging; giữ nguyên journal UPLOADING để thử lại"
            )
        }
        require(expectedSizeBytes in 1..MAX_ARCHIVE_BYTES && archive.length() == expectedSizeBytes) {
            "Kích thước ZIP backup đã thay đổi hoặc vượt 256 MiB"
        }
        require(prepared.syncCursor >= 0L && prepared.partCount >= 0 && prepared.movementCount >= 0) {
            "Metadata snapshot backup không hợp lệ"
        }
        require(prepared.sha256.matches(Regex("^[a-fA-F0-9]{64}$"))) {
            "SHA-256 của ZIP backup không hợp lệ"
        }
        val actualDigest = sha256File(archive, prepared.ownerId)
        require(actualDigest.equals(prepared.sha256, ignoreCase = true)) {
            "ZIP backup đã thay đổi sau khi được chuẩn bị"
        }
    }

    private fun requireActiveOwner(ownerId: String) {
        if (client.auth.currentUserOrNull()?.id != ownerId) {
            throw WeeklyBackupIdentityException(
                "Tài khoản đã thay đổi hoặc phiên đăng nhập hết hạn; đã dừng sao lưu Cloud"
            )
        }
    }

    private fun requireCanonicalUuid(value: String, label: String) {
        val canonical = runCatching { UUID.fromString(value).toString() }.getOrNull()
        require(canonical != null && canonical == value) {
            "$label không phải UUID hợp lệ"
        }
    }

    private fun parseSingleRpcRow(raw: String) = Json.parseToJsonElement(raw).jsonArray.single().jsonObject

    private suspend fun sha256File(file: File, ownerId: String): String {
        val coroutineContext = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileInputStream(file).buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    requireActiveOwner(ownerId)
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (io: IOException) {
            throw WeeklyBackupStorageException("Lỗi đọc ZIP/cache tạm thời khi xác minh SHA-256")
        } catch (security: SecurityException) {
            throw WeeklyBackupStorageException("Không có quyền đọc ZIP/cache tạm thời khi xác minh SHA-256")
        }
        coroutineContext.ensureActive()
        requireActiveOwner(ownerId)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Registration(val storagePath: String, val status: String)

    private companion object {
        const val BUCKET = "inventory-backups"
        const val BACKUP_KIND_WEEKLY = "WEEKLY_AUTO"
        const val MAX_ARCHIVE_BYTES = 268435456L
        const val MIN_FREE_BYTES = 16L * 1024L * 1024L
    }
}
