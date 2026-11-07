package vn.kholinhkien

import android.content.Context
import android.net.Uri
import java.io.File
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/**
 * End-to-end, resumable coordinator for owner-scoped ZIP restore.
 * Each phase is checkpointed by RestoreJournal; retry resumes from that state.
 * Live Room inventory is changed only by RestoreCloudSnapshotApplier, after the
 * server commit and complete snapshot verification have succeeded.
 */
internal class RestoreCloudCoordinator(
    private val context: Context,
    private val dao: InventoryDao
) {
    data class Result(
        val restoreId: String,
        val partCount: Int,
        val movementCount: Int,
        val snapshotCursor: Long,
        val localCleanupComplete: Boolean
    )

    suspend fun importAndRestore(uri: Uri, ownerId: String): Result = RestoreOperationGate.mutex.withLock {
        requireActiveOwner(ownerId)
        requireNoPendingOutbox(ownerId)

        val stagedCounts = ZipRestore.restore(context, uri, dao, ownerId)
        val journal = requireNotNull(dao.activeRestore(ownerId)) {
            "Không tìm thấy phiên staging sau khi kiểm tra ZIP"
        }
        require(journal.status == "STAGED") { "ZIP chưa ở trạng thái staging hoàn chỉnh: ${journal.status}" }
        require(journal.partCount == stagedCounts.first && journal.movementCount == stagedCounts.second) {
            "Số lượng staging không khớp kết quả kiểm tra ZIP"
        }
        resumeLocked(ownerId, journal.restoreId)
    }

    /** Restore from the private durable Cloud inbox; never accepts arbitrary filesystem paths. */
    suspend fun importAndRestore(archive: File, ownerId: String): Result = RestoreOperationGate.mutex.withLock {
        requireActiveOwner(ownerId)
        require(isCanonicalOwnerId(ownerId)) { "Không xác định được tài khoản đang đăng nhập" }
        requireNoPendingOutbox(ownerId)
        val canonical = archive.canonicalFile
        val inbox = File(context.filesDir, "cloud_restore_inbox/$ownerId").canonicalFile
        require(canonical.parentFile == inbox && canonical.isFile && canonical.extension == "zip") {
            "Tệp khôi phục không thuộc inbox bền vững của tài khoản hiện tại"
        }
        val inboxItem = WeeklyBackupRestoreInbox(context).listVerified(ownerId).firstOrNull { it.file.canonicalFile == canonical }
            ?: error("Backup không còn trong danh sách staging đã xác minh; hãy tải và xác minh lại từ Cloud")
        require(inboxItem.file.length() > 0L) { "Backup staging rỗng" }
        val stagedCounts = ZipRestore.restore(context, canonical, dao, ownerId)
        require(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) { "Tài khoản đã thay đổi trong khi staging backup" }
        val journal = requireNotNull(dao.activeRestore(ownerId)) { "Không tìm thấy phiên staging sau khi kiểm tra ZIP" }
        require(journal.status == "STAGED") { "ZIP chưa ở trạng thái staging hoàn chỉnh: ${journal.status}" }
        require(journal.partCount == stagedCounts.first && journal.movementCount == stagedCounts.second) { "Số lượng staging không khớp kết quả kiểm tra ZIP" }
        resumeLocked(ownerId, journal.restoreId)
    }

    /** Resume by exact journal ID after process death or a network interruption. */
    suspend fun resume(ownerId: String, restoreId: String): Result = RestoreOperationGate.mutex.withLock {
        resumeLocked(ownerId, restoreId)
    }

    private suspend fun resumeLocked(ownerId: String, restoreId: String): Result {
        requireActiveOwner(ownerId)
        require(ownerId.isNotBlank() && restoreId.isNotBlank()) { "Thiếu tài khoản hoặc restoreId" }
        var journal = requireNotNull(dao.restoreJournal(ownerId, restoreId)) { "Không tìm thấy phiên khôi phục" }
        require(journal.ownerId == ownerId) { "Phiên khôi phục không thuộc tài khoản hiện tại" }
        // Recheck after the Room read: account switching can race a resumed screen.
        requireActiveOwner(ownerId)

        if (journal.status == "COMPLETE") {
            // COMPLETE still belongs to the original owner; do not run even local
            // cleanup through a stale screen after logout/account switch.
            requireActiveOwner(ownerId)
            queuePostRestoreBackupIfNeeded(ownerId, journal)
            val cleaned = try {
                RestoreStagingLifecycle.cleanupCompleted(context, dao, ownerId, restoreId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            return completedResult(journal, cleaned)
        }

        // Outbox is checked before any remote work because the active restore guard
        // pauses normal sync. Never strand pending operations behind a restore journal.
        requireNoPendingOutbox(ownerId)

        when (journal.status) {
            "STAGED", "PHOTOS_UPLOADED", "PAYLOAD_READY" -> {
                RestoreCloudPayloadPreparerFactory.create(dao).prepare(ownerId, restoreId)
                journal = requireNotNull(dao.restoreJournal(ownerId, restoreId))
            }
        }

        when (journal.status) {
            "PAYLOAD_READY", "CLOUD_STAGING" -> {
                RestoreCloudSessionSender(dao).stage(ownerId, restoreId)
                journal = requireNotNull(dao.restoreJournal(ownerId, restoreId))
            }
        }

        when (journal.status) {
            "CLOUD_STAGED", "COMMIT_UNKNOWN", "COMMIT_CONFIRMED" -> {
                requireNoPendingOutbox(ownerId)
                RestoreCloudCommitter(dao).commit(ownerId, restoreId)
                journal = requireNotNull(dao.restoreJournal(ownerId, restoreId))
            }
        }

        if (journal.status in setOf("COMMIT_CONFIRMED", "SNAPSHOT_DOWNLOADING", "SNAPSHOT_DOWNLOADED")) {
            RestoreSnapshotGate.mutex.withLock {
                // Another worker may have completed this exact restore while this
                // coordinator was waiting. Always make the durable journal authoritative.
                journal = requireNotNull(dao.restoreJournal(ownerId, restoreId))
                require(journal.ownerId == ownerId) { "Phiên khôi phục không thuộc tài khoản hiện tại" }
                if (journal.status in setOf("COMMIT_CONFIRMED", "SNAPSHOT_DOWNLOADING", "SNAPSHOT_DOWNLOADED")) {
                    // Always re-read a previously downloaded snapshot before applying it.
                    // Older builds could checkpoint SNAPSHOT_DOWNLOADED with staged PART
                    // rows missing server_version. Re-download verifies the immutable
                    // cloud checksum and runs current compatibility normalization.
                    RestoreCloudSnapshotReader(context.applicationContext, dao).download(ownerId, restoreId)
                    journal = requireNotNull(dao.restoreJournal(ownerId, restoreId))
                }
                if (journal.status == "SNAPSHOT_DOWNLOADED") {
                    requireNoPendingOutbox(ownerId)
                    // Re-read immediately before applying; the Applier independently
                    // rejects every state except SNAPSHOT_DOWNLOADED.
                    journal = requireNotNull(dao.restoreJournal(ownerId, restoreId))
                    if (journal.status == "SNAPSHOT_DOWNLOADED") {
                        RestoreCloudSnapshotApplier(context.applicationContext, dao).apply(ownerId, restoreId)
                        journal = requireNotNull(dao.restoreJournal(ownerId, restoreId))
                    }
                }
            }
        }

        require(journal.status == "COMPLETE") {
            "Phiên khôi phục đang ở trạng thái ${journal.status}; có thể tiếp tục lại sau khi xử lý nguyên nhân."
        }
        queuePostRestoreBackupIfNeeded(ownerId, journal)
        val cleanupComplete = try {
            RestoreStagingLifecycle.cleanupCompleted(context, dao, ownerId, restoreId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        return completedResult(journal, cleanupComplete)
    }

    private suspend fun queuePostRestoreBackupIfNeeded(ownerId: String, journal: RestoreJournal) {
        if (journal.sourceType != "LOCAL_ZIP") return
        requireActiveOwner(ownerId)
        if (dao.markPostRestoreBackupQueued(ownerId, journal.restoreId) == 1) {
            // The durable per-restore flag prevents repeated resume/cleanup taps
            // from creating duplicate cloud backups.
            WeeklyBackupWorkScheduler.enqueueNow(context, ownerId)
        }
    }

    private fun requireActiveOwner(ownerId: String) {
        require(ownerId.isNotBlank() && SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đã thay đổi hoặc phiên đăng nhập hết hạn; đã dừng khôi phục"
        }
    }

    private suspend fun requireNoPendingOutbox(ownerId: String) {
        requireActiveOwner(ownerId)
        val pending = dao.countUnsentOutbox(ownerId)
        requireActiveOwner(ownerId)
        require(pending == 0) {
            "Còn $pending thao tác chưa đồng bộ. Hãy đồng bộ hết dữ liệu trước khi bắt đầu/tiếp tục khôi phục để tránh mất thao tác mới."
        }
    }

    private fun completedResult(journal: RestoreJournal, cleanupComplete: Boolean) = Result(
        restoreId = journal.restoreId,
        partCount = journal.partCount,
        movementCount = journal.movementCount,
        snapshotCursor = journal.snapshotCursor,
        localCleanupComplete = cleanupComplete
    )

}
