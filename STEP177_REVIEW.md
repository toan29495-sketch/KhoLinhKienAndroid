# Step177 Review — owner-scoped weekly-backup single-flight

## Scope
Prevent two weekly-backup Worker executions for the same owner from entering recovery/prepare/upload concurrently within the app process, while allowing distinct owners to proceed independently.

## Changes
- Added `WeeklyBackupExecutionLock`, an owner-keyed `ConcurrentHashMap<String, Mutex>` with coroutine `withLock` critical sections.
- `WeeklyBackupWorker.doWork` acquires the lock before profile validation, journal recovery, archive preparation, and upload; it rechecks the authenticated owner after waiting so a session switch while queued cannot start the previous owner's work.
- Different owner IDs use different mutexes. Cancellation continues to propagate. Existing unique periodic `KEEP` scheduling and transactional active-journal guard remain in place.
- This is an in-process fence. It does not claim cross-process coordination; the app currently uses the normal single-process WorkManager configuration. Durable duplicate protection still relies on owner-scoped Room journal transaction and WorkManager unique periodic scheduling.

## Verification
- `verify_step177_weekly_backup_single_flight.py`: 13/13 PASS, including threaded serialization/isolation model.

## Limits
Static source contract and Python concurrency model only. No Android WorkManager runtime/concurrency test, Kotlin compile, APK, device test, Supabase/TUS/RLS/staging test. No server/data mutation, schedule activation, GitHub push. No Gradle used.
