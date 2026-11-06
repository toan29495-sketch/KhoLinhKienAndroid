package vn.kholinhkien

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CancellationException
import io.github.jan.supabase.auth.auth

/**
 * Final local reconciliation step. It can run only after the cloud commit and
 * complete snapshot download have been confirmed. The DAO performs validation,
 * owner-scoped replacement, cursor update and journal completion in one Room
 * transaction. No image bytes are copied into permanent local storage.
 */
internal class RestoreCloudSnapshotApplier(
    private val context: Context,
    private val dao: InventoryDao,
    private val client: SupabaseClient = SupabaseClientProvider.client
) {
    data class ApplyResult(
        val restoreId: String,
        val partCount: Int,
        val movementCount: Int,
        val snapshotCursor: Long,
        val localCleanupComplete: Boolean
    )

    suspend fun apply(ownerId: String, restoreId: String): ApplyResult {
        val authenticatedUserId = client.auth.currentUserOrNull()?.id
            ?: error("Cần đăng nhập trước khi chuyển snapshot vào kho")
        require(authenticatedUserId == ownerId) { "Tài khoản đăng nhập không khớp chủ kho" }

        val journal = requireNotNull(dao.restoreJournal(ownerId, restoreId)) { "Không tìm thấy journal khôi phục" }
        require(journal.ownerId == ownerId) { "Phiên khôi phục không thuộc tài khoản hiện tại" }
        require(journal.status == "SNAPSHOT_DOWNLOADED") {
            "Chỉ được chuyển kho sau khi snapshot đã tải đầy đủ: ${journal.status}"
        }
        require(journal.snapshotCursor > 0L) { "Snapshot cursor không hợp lệ" }
        require(client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đã thay đổi trước khi áp dụng snapshot; kho hiện tại được giữ nguyên"
        }

        dao.applyCommittedRestoreSnapshot(
            ownerId = ownerId,
            restoreId = restoreId,
            snapshotCursor = journal.snapshotCursor,
            expectedPartCount = journal.partCount,
            expectedMovementCount = journal.movementCount
        )

        // Room has committed the new inventory and cursor atomically. Cleanup is
        // deliberately best-effort and repeatable; it must never roll back or
        // misreport the already completed restore if file deletion is interrupted.
        val cleanupComplete = try {
            RestoreStagingLifecycle.cleanupCompleted(context, dao, ownerId, restoreId)
        } catch (cancelled: CancellationException) {
            // The Room snapshot transaction is already committed. Preserve coroutine
            // cancellation; the retained COMPLETE journal makes cleanup safely retryable.
            throw cancelled
        } catch (_: Exception) {
            false
        }

        return ApplyResult(
            restoreId = restoreId,
            partCount = journal.partCount,
            movementCount = journal.movementCount,
            snapshotCursor = journal.snapshotCursor,
            localCleanupComplete = cleanupComplete
        )
    }

}
