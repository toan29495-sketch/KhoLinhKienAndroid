# Step169 Review — Weekly backup cancellation responsiveness

## Scope
Make weekly backup preparation respond promptly to WorkManager/coroutine cancellation while preserving the durable journal and exact resumable READY/UPLOADING archives.

## Changes
- `WeeklyBackupGuardedOutputStream` accepts a cancellation probe and invokes it before each write-capacity check, preventing long synchronous ZIP output from ignoring cancellation between writes.
- Weekly backup preparation captures its coroutine context and supplies `ensureActive()` to the guarded stream.
- ZIP photo discovery, parts serialization, movement serialization, photo-copy loops, and final archive SHA-256 calculation check coroutine cancellation between work units/chunks.
- Existing `createZip` `finally` cleanup still removes temporary downloaded cloud photos. The existing PREPARING failure path removes a partial archive and retains the journal identity.
- READY/UPLOADING reuse remains outside the ZIP rebuild `try` path; the canonical staged bytes and TUS identity are not rewritten.
- Worker continues to rethrow `CancellationException`, allowing WorkManager cancellation semantics to remain intact.

## Verification
- `verify_step169_weekly_backup_cancellation.py`: source-contract checks.
- Existing Step140–168 regression scripts rerun; results recorded in `PROGRESS.md`.
- Python compileall and ZIP integrity checked.

## Not verified / not done
- No Kotlin/Android compilation, WorkManager runtime cancellation test, APK build/install, or phone test.
- No live Supabase/TUS/Storage/RLS/staging test.
- No schedule enqueue/activation, account/profile/inventory mutation, remote/local retention deletion, or GitHub push.
- No Gradle used.

## Safety
- Room remains v14; no SQL/backend change.
- No remote objects or user data changed.
- Cancellation of PREPARING work preserves the journal for recovery; READY/UPLOADING archive bytes remain untouched.
