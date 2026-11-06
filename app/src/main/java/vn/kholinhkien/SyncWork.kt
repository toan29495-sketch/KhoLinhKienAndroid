package vn.kholinhkien

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class SyncOutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Serialize outbox uploads with restore import/resume/abort. A restore checks
        // that the outbox is empty while holding this same process-wide gate.
        if (!RestoreOperationGate.mutex.tryLock()) return Result.retry()
        return try {
            doWorkWhileRestoreGateHeld()
        } finally {
            RestoreOperationGate.mutex.unlock()
        }
    }

    private suspend fun doWorkWhileRestoreGateHeld(): Result {
        val ownerId = inputData.getString(KEY_OWNER_ID) ?: return Result.failure()
        return try {
            if (AppDb.get(applicationContext).dao().activeRestore(ownerId) != null) return Result.retry()
            when (val result = SyncOutboxSender(AppDb.get(applicationContext).dao(), applicationContext.filesDir).sendPending(ownerId)) {
                is SyncSendResult.Completed -> Result.success()
                is SyncSendResult.Partial -> if (result.retryable) Result.retry() else Result.success()
                SyncSendResult.NoSession, SyncSendResult.WrongAccount -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object { const val KEY_OWNER_ID = "owner_id" }
}

object SyncWorkScheduler {
    fun cancelOwner(context: Context, ownerId: String) {
        if (ownerId.isBlank()) return
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork("inventory-sync-once-$ownerId")
        manager.cancelUniqueWork("inventory-sync-periodic-$ownerId")
        manager.cancelUniqueWork("inventory-pull-once-$ownerId")
        manager.cancelUniqueWork("inventory-pull-once-admin-$ownerId")
        manager.cancelUniqueWork("inventory-pull-periodic-$ownerId")
        manager.cancelUniqueWork("inventory-pull-periodic-admin-$ownerId")
    }

    fun schedule(context: Context, ownerId: String, isAdmin: Boolean = false) {
        if (ownerId.isBlank()) return
        val manager = WorkManager.getInstance(context.applicationContext)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncOutboxWorker>()
            .setInputData(androidx.work.workDataOf(SyncOutboxWorker.KEY_OWNER_ID to ownerId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniqueWork("inventory-sync-once-$ownerId", ExistingWorkPolicy.KEEP, request)

        val periodic = PeriodicWorkRequestBuilder<SyncOutboxWorker>(15, TimeUnit.MINUTES)
            .setInputData(androidx.work.workDataOf(SyncOutboxWorker.KEY_OWNER_ID to ownerId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(
            "inventory-sync-periodic-$ownerId",
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )

        // Every account, including Admin, must pull its own inventory feed.
        // Admin additionally pulls the redacted global movement feed so that the
        // Admin's own warehouse and system-wide history stay separate partitions.
        val ownerPullRequest = OneTimeWorkRequestBuilder<SyncPullWorker>()
            .setInputData(androidx.work.workDataOf(
                SyncPullWorker.KEY_OWNER_ID to ownerId,
                SyncPullWorker.KEY_IS_ADMIN to false
            ))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniqueWork("inventory-pull-once-$ownerId", ExistingWorkPolicy.KEEP, ownerPullRequest)

        val ownerPullPeriodic = PeriodicWorkRequestBuilder<SyncPullWorker>(15, TimeUnit.MINUTES)
            .setInputData(androidx.work.workDataOf(
                SyncPullWorker.KEY_OWNER_ID to ownerId,
                SyncPullWorker.KEY_IS_ADMIN to false
            ))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(
            "inventory-pull-periodic-$ownerId",
            ExistingPeriodicWorkPolicy.KEEP,
            ownerPullPeriodic
        )

        if (isAdmin) {
            val adminPullRequest = OneTimeWorkRequestBuilder<SyncPullWorker>()
                .setInputData(androidx.work.workDataOf(
                    SyncPullWorker.KEY_OWNER_ID to ownerId,
                    SyncPullWorker.KEY_IS_ADMIN to true
                ))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            manager.enqueueUniqueWork("inventory-pull-once-admin-$ownerId", ExistingWorkPolicy.KEEP, adminPullRequest)

            val adminPullPeriodic = PeriodicWorkRequestBuilder<SyncPullWorker>(15, TimeUnit.MINUTES)
                .setInputData(androidx.work.workDataOf(
                    SyncPullWorker.KEY_OWNER_ID to ownerId,
                    SyncPullWorker.KEY_IS_ADMIN to true
                ))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            manager.enqueueUniquePeriodicWork(
                "inventory-pull-periodic-admin-$ownerId",
                ExistingPeriodicWorkPolicy.KEEP,
                adminPullPeriodic
            )
        } else {
            // A role downgrade must not leave an obsolete global-history worker scheduled.
            manager.cancelUniqueWork("inventory-pull-once-admin-$ownerId")
            manager.cancelUniqueWork("inventory-pull-periodic-admin-$ownerId")
        }
    }
}
