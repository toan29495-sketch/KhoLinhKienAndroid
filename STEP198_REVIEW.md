# Step198 Review — Process-death recovery for confirmed restore sessions

Date: 04/11/2026

## Baseline and provenance
- Exact baseline: `KhoLinhKienAndroid-step197-sync-restore-serialization-20261103.zip`
- Baseline SHA-256: `4d09d147d79fe5397c32e303aaa02054fd58050260cc4fb694fa4ffd15d6d744`
- This is a new implementation of the previously planned Step198 scope; no unsupported historical Step198 artifact is claimed.

## Scope implemented
- Added `RestoreRecoveryWorker` to resume only already-confirmed `LOCAL_ZIP` restore journals in coordinator-supported states. It uses exact owner + restore ID, checks the authenticated Supabase UID, and delegates to the existing idempotent `RestoreCloudCoordinator.resume` path.
- The worker does not auto-start a new restore, does not process `REMOTE_FEED` journals (those remain owned by `SyncPullWorker`), and leaves interrupted `STAGING`/`FAILED`/`ABORTED` ZIP imports untouched for explicit user re-selection or recovery.
- `STAGED` is resumed only after durable staged rows and referenced photo files pass `RestoreStagingLifecycle.canResumeStaged`.
- Added `RestoreRecoveryScheduler`: unique WorkManager work per owner + restore ID, connected-network constraint, exponential retry, and owner-tag cancellation.
- Authenticated app startup enumerates only the current owner's journals and enqueues resumable local restore sessions, including idempotent cleanup of `COMPLETE` sessions.
- Added an owner-scoped Room Flow for restore journals; Compose UI observes it so the inventory lock/status updates when WorkManager changes a journal after process recreation.
- Sign-out and inactive-profile retirement cancel only the corresponding owner's recovery work.
- Existing coordinator checks remain authoritative: pending Outbox must be empty, `RestoreOperationGate` serializes import/resume/abort with push/pull workers, `RestoreSnapshotGate` serializes snapshot read/apply, and Room applies live inventory only from `SNAPSHOT_DOWNLOADED`.

## Verification
- `verify_step198_process_death_restore_recovery.py`: 28/28 PASS.
- Step190–Step197 regression: 121/121 PASS.
- Combined targeted checks: 149/149 PASS.
- `python -m compileall -q tools`: PASS.
- ZIP archive integrity and post-package verifier rerun: PASS.

## Safety boundaries
- No Gradle, Kotlin compilation, APK build, Android device test, live Supabase call, restore execution, SQL/schema change, user data mutation, Storage mutation, or GitHub push.
- This is source-contract verification. Actual WorkManager process-death/network retry behavior still requires APK/device testing.
- `STAGING` local ZIP extraction cannot resume from an expired content URI; the UI continues to require selecting the same archive again. The restore journal and live inventory remain protected.
- `REMOTE_FEED` recovery remains with `SyncPullWorker`; this worker intentionally does not compete with that state machine.
