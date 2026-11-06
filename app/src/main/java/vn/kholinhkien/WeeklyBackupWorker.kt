package vn.kholinhkien

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes one owner-scoped scheduled cloud-backup attempt. Scheduling is deliberately
 * opt-in at this step; this worker is not enqueued by login or app startup yet.
 */
internal class WeeklyBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ownerId = inputData.getString(KEY_OWNER_ID) ?: return Result.failure()
        if (!isCanonicalOwnerId(ownerId)) return Result.failure()
        val client = SupabaseClientProvider.client
        val authenticatedId = client.auth.currentUserOrNull()?.id
        if (authenticatedId == null || authenticatedId != ownerId) return Result.success()

        return WeeklyBackupExecutionLock.withOwner(ownerId) {
            // Re-check after waiting: another worker may have held this owner's lock
            // while the account session changed.
            if (client.auth.currentUserOrNull()?.id != ownerId) return@withOwner Result.success()
            executeOwnerBackup(ownerId, client)
        }
    }

    private suspend fun executeOwnerBackup(
        ownerId: String,
        client: io.github.jan.supabase.SupabaseClient
    ): Result {
        return try {
            val profile = AuthRepository().getCurrentProfile()
            if (!profile.isActive || profile.id != ownerId || profile.mustChangePassword) return Result.success()
            val dao = AppDb.get(applicationContext).dao()

            // Recovery is local-only and owner-scoped. It preserves READY/UPLOADING
            // bytes so the TUS SDK sees the same path and file fingerprint on retry.
            WeeklyBackupRecovery.recoverOwner(applicationContext, dao, ownerId)
            if (client.auth.currentUserOrNull()?.id != ownerId) return Result.success()

            val active = dao.activeWeeklyBackup(ownerId)
            // A fresh trigger resumes the active journal; it never replaces an
            // in-flight backup with a new snapshot or TUS fingerprint.
            val backupId = WeeklyBackupLifecycle.backupIdForAttempt(
                active, UUID.randomUUID().toString()
            )
            val prepared = BackupManager.prepareWeeklyCloudBackup(
                context = applicationContext,
                dao = dao,
                ownerId = ownerId,
                username = profile.username,
                backupId = backupId
            )
            if (client.auth.currentUserOrNull()?.id != ownerId) return Result.success()

            SupabaseWeeklyBackupUploader(applicationContext, client, dao)
                .uploadAndVerify(prepared, backupId)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InactiveProfileException) {
            // Profile validation may sign out the shared Supabase session.
            // Retire only this owner's periodic work; never cancel another owner.
            WeeklyBackupWorkScheduler.cancel(applicationContext, ownerId)
            Result.success()
        } catch (_: WeeklyBackupIdentityException) {
            // Session/account changed: stop this owner's attempt without touching another account.
            Result.success()
        } catch (_: WeeklyBackupStorageException) {
            // Low disk, temporary file access denial, or transient staging/cache I/O
            // must not terminalize the journal. Keep READY/UPLOADING + ZIP for retry.
            Result.retry()
        } catch (permanent: PermanentWeeklyBackupException) {
            // Terminal integrity failure is recorded only while this exact owner is
            // still authenticated. A delayed catch after logout/account switch must
            // not mutate the previous owner's local journal from the new session.
            runCatching {
                if (client.auth.currentUserOrNull()?.id == ownerId) {
                    val failedDao = AppDb.get(applicationContext).dao()
                    if (client.auth.currentUserOrNull()?.id == ownerId) {
                        val active = failedDao.activeWeeklyBackup(ownerId)
                        if (active != null && client.auth.currentUserOrNull()?.id == ownerId) {
                            failedDao.markWeeklyBackupFailed(ownerId, active.backupId,
                                permanent.message ?: "Lỗi dữ liệu backup không thể tự phục hồi")
                        }
                    }
                }
            }
            Result.failure()
        } catch (_: Exception) {
            // Network, backend availability, and temporary storage errors remain retryable.
            Result.retry()
        }
    }

    companion object {
        const val KEY_OWNER_ID = "weekly_backup_owner_id"
    }
}

/** Owner-scoped recurring cloud backup scheduler. */
internal object WeeklyBackupWorkScheduler {
    const val PERIODIC_INTERVAL_DAYS = 30L

    /** Enqueue one explicit backup now; recurring 30-day scheduling remains separate. */
    fun enqueueNow(context: Context, ownerId: String) {
        require(isCanonicalOwnerId(ownerId)) { "Owner sao lưu tự động không hợp lệ" }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<WeeklyBackupWorker>()
            .setInputData(androidx.work.workDataOf(WeeklyBackupWorker.KEY_OWNER_ID to ownerId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            oneTimeName(ownerId), ExistingWorkPolicy.KEEP, request
        )
    }

    fun schedule(context: Context, ownerId: String) {
        require(isCanonicalOwnerId(ownerId)) { "Owner sao lưu tự động không hợp lệ" }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<WeeklyBackupWorker>(
            PERIODIC_INTERVAL_DAYS, TimeUnit.DAYS
        )
            .setInputData(androidx.work.workDataOf(WeeklyBackupWorker.KEY_OWNER_ID to ownerId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            uniqueName(ownerId), ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    fun cancel(context: Context, ownerId: String) {
        if (!isCanonicalOwnerId(ownerId)) return
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(oneTimeName(ownerId))
        manager.cancelUniqueWork(uniqueName(ownerId))
    }

    private fun oneTimeName(ownerId: String) = "inventory-weekly-backup-once-$ownerId"
    private fun uniqueName(ownerId: String) = "inventory-weekly-backup-$ownerId"
}


/** In-process single-flight fence; WorkManager remains the durable scheduler. */
internal object WeeklyBackupExecutionLock {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withOwner(ownerId: String, block: suspend () -> T): T {
        require(isCanonicalOwnerId(ownerId)) { "Owner backup không hợp lệ" }
        val mutex = locks.getOrPut(ownerId) { Mutex() }
        return mutex.withLock { block() }
    }
}
