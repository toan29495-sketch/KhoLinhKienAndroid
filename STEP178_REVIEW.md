# Step178 Review — weekly-backup process restart and lock recovery

## Scope
Audit the owner-scoped in-process single-flight mutex under Android process death and coroutine cancellation, without introducing a persistent lock that could survive incorrectly or block a future process.

## Findings
- `WeeklyBackupExecutionLock` is a process-memory `ConcurrentHashMap<String, Mutex>`. Android process termination discards the registry and all mutex state; a newly created process starts with an empty registry, so there is no stale persisted lock to clear.
- The critical section uses `Mutex.withLock`, which provides structured release when the block exits or is cancelled. The worker explicitly rethrows `CancellationException`; it does not swallow cancellation as a retryable exception.
- Durable recovery remains separate from the transient mutex: after acquiring the lock and rechecking the authenticated owner, the worker runs owner-scoped journal recovery before reading/creating an active journal.
- Existing WorkManager unique periodic `KEEP` scheduling and the Room transaction that prevents a second active journal remain the durable duplicate guards. No cross-process lock was added or claimed.
- A waiting worker can proceed after the previous holder exits; a fresh-process model can acquire a newly created owner lock after simulated process death.

## Verification
- `verify_step178_weekly_backup_process_restart_lock.py`: 15/15 PASS. Includes static source-contract checks and a Python model for fresh-process lock reset and waiting-worker progress after release.
- Regression Step158–178: 21 verifier scripts, 355/355 checks PASS.
- Python `compileall` for tools: PASS.

## Limits
This is source-contract review plus a Python concurrency model, not Android process-kill/restart, Coroutine Mutex, or WorkManager runtime testing. No Kotlin compile, APK build/install, device test, live Supabase/TUS/RLS/staging test, server/data mutation, schedule activation, or GitHub push. No Gradle used.
