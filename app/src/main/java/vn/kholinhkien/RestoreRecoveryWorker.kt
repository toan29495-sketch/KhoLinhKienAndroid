package vn.kholinhkien

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Re-enters an already-confirmed owner-scoped LOCAL_ZIP restore after process death.
 * It never starts a new restore from an unconfirmed archive and never handles REMOTE_FEED
 * journals (SyncPullWorker owns those). The coordinator/journal remain authoritative.
 */
internal class RestoreRecoveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ownerId = inputData.getString(KEY_OWNER_ID) ?: return Result.failure()
        val restoreId = inputData.getString(KEY_RESTORE_ID) ?: return Result.failure()
        if (!isCanonicalOwnerId(ownerId) || !isCanonicalRestoreId(restoreId)) return Result.failure()

        val client = SupabaseClientProvider.client
        if (client.auth.currentUserOrNull()?.id != ownerId) return Result.success()

        val dao = AppDb.get(applicationContext).dao()
        val journal = dao.restoreJournal(ownerId, restoreId) ?: return Result.success()
        if (journal.ownerId != ownerId || journal.sourceType != "LOCAL_ZIP") return Result.success()

        // Incomplete ZIP extraction cannot be resumed from a consumed content URI.
        // Keep it untouched; the UI asks the user to select the same archive again.
        if (journal.status in setOf("STAGING", "FAILED", "ABORTED")) return Result.success()
        if (journal.status !in RESUMABLE_STATUSES && journal.status != "COMPLETE") return Result.success()
        if (journal.status == "STAGED" &&
            !RestoreStagingLifecycle.canResumeStaged(applicationContext, dao, ownerId, restoreId)
        ) return Result.success()

        if (client.auth.currentUserOrNull()?.id != ownerId) return Result.success()
        return try {
            // Coordinator owns RestoreOperationGate and RestoreSnapshotGate. Calling the
            // same resume path makes retries idempotent across process recreation.
            val result = RestoreCloudCoordinator(applicationContext, dao).resume(ownerId, restoreId)
            if (result.localCleanupComplete) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Preserve the journal and staged bytes. WorkManager applies exponential
            // backoff and will retry when the network/service becomes available.
            Result.retry()
        }
    }

    companion object {
        const val KEY_OWNER_ID = "restore_recovery_owner_id"
        const val KEY_RESTORE_ID = "restore_recovery_id"
        val RESUMABLE_STATUSES = setOf(
            "STAGED", "PHOTOS_UPLOADED", "PAYLOAD_READY", "CLOUD_STAGING",
            "CLOUD_STAGED", "COMMIT_UNKNOWN", "COMMIT_CONFIRMED",
            "SNAPSHOT_DOWNLOADING", "SNAPSHOT_DOWNLOADED"
        )

        private fun isCanonicalRestoreId(value: String): Boolean =
            runCatching { java.util.UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
    }
}

/** Enqueues only journals whose prior user-confirmed restore can safely resume. */
internal object RestoreRecoveryScheduler {
    suspend fun schedulePending(context: Context, dao: InventoryDao, ownerId: String): Int {
        require(isCanonicalOwnerId(ownerId)) { "Owner khôi phục không hợp lệ" }
        check(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đã thay đổi; không lập lịch phục hồi phiên của tài khoản khác"
        }
        val manager = WorkManager.getInstance(context.applicationContext)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        var scheduled = 0
        for (journal in dao.restoreJournals(ownerId)) {
            check(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) {
                "Tài khoản đã thay đổi trong lúc đọc journal phục hồi"
            }
            if (journal.ownerId != ownerId || journal.sourceType != "LOCAL_ZIP") continue
            if (journal.status !in RestoreRecoveryWorker.RESUMABLE_STATUSES && journal.status != "COMPLETE") continue
            val request = OneTimeWorkRequestBuilder<RestoreRecoveryWorker>()
                .setInputData(androidx.work.workDataOf(
                    RestoreRecoveryWorker.KEY_OWNER_ID to ownerId,
                    RestoreRecoveryWorker.KEY_RESTORE_ID to journal.restoreId
                ))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("restore-recovery-$ownerId")
                .build()
            manager.enqueueUniqueWork(
                uniqueName(ownerId, journal.restoreId), ExistingWorkPolicy.KEEP, request
            )
            scheduled++
        }
        check(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đã thay đổi trong lúc lập lịch phục hồi"
        }
        return scheduled
    }

    fun cancelOwner(context: Context, ownerId: String) {
        if (!isCanonicalOwnerId(ownerId)) return
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag("restore-recovery-$ownerId")
    }

    private fun uniqueName(ownerId: String, restoreId: String) =
        "inventory-restore-recovery-$ownerId-$restoreId"
}
