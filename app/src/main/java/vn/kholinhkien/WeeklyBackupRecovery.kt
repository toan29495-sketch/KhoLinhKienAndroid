package vn.kholinhkien

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Owner-scoped crash recovery for the durable weekly-backup journal.
 * It never contacts Supabase and never deletes remote objects or another owner's files.
 */
internal object WeeklyBackupRecovery {
    data class Report(
        val preparingArchivesRemoved: Int,
        val verifiedArchivesCleaned: Int,
        val resumableArchives: Int,
        val invalidArchivesMarkedFailed: Int,
        val cleanupPending: Int,
        val stalePreparing: Int,
        val staleUploadsResumable: Int,
        val terminalRetained: Int
    )

    suspend fun recoverOwner(context: Context, dao: InventoryDao, ownerId: String): Report {
        require(isCanonicalOwnerId(ownerId)) { "Owner backup không hợp lệ" }
        requireActiveOwner(ownerId)
        val root = File(context.filesDir, "weekly-backup-staging").canonicalFile
        val ownerDirectory = File(root, ownerId).canonicalFile
        check(ownerDirectory.parentFile == root) { "Thư mục backup vượt khỏi vùng staging" }
        val rows = dao.weeklyBackupsForOwner(ownerId)
        // A valid account has at most one in-flight weekly backup. If legacy data,
        // a race, or manual DB repair left multiple active rows, fail closed before
        // touching any archive or journal. Picking the newest row could strand or
        // overwrite another resumable backup under the same owner.
        val activeRows = rows.filter { it.state in setOf("PREPARING", "READY", "UPLOADING") }
        check(activeRows.size <= 1) {
            "Có nhiều journal backup tuần đang hoạt động; giữ nguyên toàn bộ để đối soát an toàn"
        }
        var preparingRemoved = 0
        var verifiedCleaned = 0
        var resumable = 0
        var invalid = 0
        var cleanupPending = 0
        var stalePreparing = 0
        var staleUploadsResumable = 0
        var terminalRetained = 0

        val recoveryCoroutineContext = currentCoroutineContext()
        for (row in rows) {
            recoveryCoroutineContext.ensureActive()
            requireActiveOwner(ownerId)
            when (WeeklyBackupLifecycle.classify(row.state, row.updatedAt, System.currentTimeMillis())) {
                WeeklyBackupLifecycle.Classification.STALE_PREPARING_RECOVERABLE -> stalePreparing++
                WeeklyBackupLifecycle.Classification.STALE_UPLOAD_RESUMABLE -> staleUploadsResumable++
                WeeklyBackupLifecycle.Classification.TERMINAL_RETAINED -> terminalRetained++
                else -> Unit
            }
            requireActiveOwner(ownerId)
            check(row.ownerId == ownerId) { "Journal trả về sai owner" }
            val archive = File(row.archivePath).canonicalFile
            val expected = File(ownerDirectory, "${row.backupId}.zip").canonicalFile
            check(archive == expected && archive.parentFile == ownerDirectory) {
                "Journal chứa đường dẫn archive ngoài vùng staging owner; từ chối thao tác"
            }
            when (row.state) {
                "PREPARING" -> {
                    // No upload is permitted before READY. Remove a crash-truncated ZIP;
                    // the existing PREPARING identity may safely rebuild on next prepare.
                    if (archive.exists()) {
                        recoveryCoroutineContext.ensureActive()
                        requireActiveOwner(ownerId)
                        check(archive.isFile) { "Archive PREPARING không phải tệp thường" }
                        check(archive.delete() || !archive.exists()) { "Không dọn được ZIP PREPARING dở dang" }
                        preparingRemoved++
                    }
                }
                "READY", "UPLOADING" -> {
                    // File.isFile/canRead can be false during transient storage or
                    // permission failures. That is not proof of archive corruption:
                    // preserve the durable journal and let WorkManager retry.
                    if (!archive.isFile || !archive.canRead()) {
                        throw WeeklyBackupStorageException(
                            "Chưa truy cập được ZIP staging; giữ nguyên journal và thử lại khi bộ nhớ/quyền truy cập ổn định"
                        )
                    }
                    val actualSize = archive.length()
                    val digest = try {
                        sha256(archive, ownerId)
                    } catch (storage: WeeklyBackupStorageException) {
                        throw storage
                    } catch (io: IOException) {
                        throw WeeklyBackupStorageException(
                            "Lỗi đọc ZIP staging tạm thời; giữ nguyên journal để thử lại"
                        )
                    } catch (security: SecurityException) {
                        throw WeeklyBackupStorageException(
                            "Quyền đọc ZIP staging tạm thời không khả dụng; giữ nguyên journal"
                        )
                    }
                    val valid = actualSize == row.archiveSizeBytes && row.archiveSizeBytes > 0 &&
                        row.sha256.matches(Regex("[0-9a-fA-F]{64}")) &&
                        digest.equals(row.sha256, ignoreCase = true)
                    if (valid) {
                        resumable++ // Preserve exact path + bytes for the SDK's TUS fingerprint.
                    } else {
                        recoveryCoroutineContext.ensureActive()
                        requireActiveOwner(ownerId)
                        check(dao.markWeeklyBackupFailed(ownerId, row.backupId,
                            "Archive staging có kích thước hoặc SHA-256 không khớp journal") == 1) {
                            "Không thể đánh dấu journal backup lỗi"
                        }
                        invalid++ // A readable, measurable integrity mismatch is terminal; retain bytes for diagnosis.
                    }
                }
                "VERIFIED" -> {
                    if (archive.exists()) {
                        recoveryCoroutineContext.ensureActive()
                        requireActiveOwner(ownerId)
                        check(archive.isFile) { "Archive VERIFIED không phải tệp thường" }
                        if (archive.delete() || !archive.exists()) verifiedCleaned++ else cleanupPending++
                    }
                }
                "FAILED", "ABANDONED" -> Unit // Retain for audit; no automatic retry or destructive cleanup.
                else -> error("Trạng thái journal backup không được hỗ trợ: ${row.state}")
            }
        }
        requireActiveOwner(ownerId)
        return Report(
            preparingRemoved, verifiedCleaned, resumable, invalid, cleanupPending,
            stalePreparing, staleUploadsResumable, terminalRetained
        )
    }

    private suspend fun sha256(file: File, ownerId: String): String {
        val recoveryCoroutineContext = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileInputStream(file).buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    recoveryCoroutineContext.ensureActive()
                    requireActiveOwner(ownerId)
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (io: IOException) {
            throw WeeklyBackupStorageException("Không đọc được ZIP staging trong lúc kiểm tra SHA-256")
        } catch (security: SecurityException) {
            throw WeeklyBackupStorageException("Không có quyền đọc ZIP staging trong lúc kiểm tra SHA-256")
        }
        recoveryCoroutineContext.ensureActive()
        requireActiveOwner(ownerId)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
