# Step166 Review — Weekly backup retry classification

## Scope
Make weekly-backup retries distinguish account/session changes, terminal integrity failures, and retryable transport/backend/storage failures. Preserve the durable owner-scoped journal and exact TUS source-file fingerprint.

## Changes
- Added `WeeklyBackupFailure.kt` with dedicated `PermanentWeeklyBackupException` and `WeeklyBackupIdentityException` types.
- Shared identity guard and uploader identity guard now throw `WeeklyBackupIdentityException`; the Worker stops that owner's attempt without treating an account switch as a network retry.
- Removed the broad `IllegalArgumentException -> Result.success()` branch. Unexpected exceptions (including network/backend and temporary free-space failures) now remain retryable.
- Journal/archive mismatch, invalid prepared archive metadata, server path/status mismatch, and completed Cloud download with incorrect size/SHA are terminal integrity/contract failures. The Worker marks the active owner journal FAILED and returns `Result.failure()` rather than retrying the same corrupt archive forever.
- `verifyStoredObject` now propagates download/network exceptions so WorkManager can retry. It returns a mismatch only after a completed download is available for exact size and SHA-256 comparison. Temporary verification files are still removed in `finally`.
- Preserved cancellation propagation, owner checks at network boundaries, immutable `backupId`, durable staging path, TUS resume behavior, and no remote Storage deletion.
- Updated Step162/164/165 source verifiers to assert the refined conditions rather than the old broad source expression.

## Verification
- `verify_step166_weekly_backup_retry_classification.py`: 19/19 PASS.
- Step165 identity/lifecycle: 12/12 PASS.
- Step164 worker: 22/22 PASS.
- Step163 recovery: 19/19 PASS.
- Step162 journal + SQLite migration smoke: 23/23 PASS.
- Step161 uploader: 21/21 PASS.
- Full Step140–166 regression: 27 verifier scripts; 448 PASS output lines, 0 FAIL output lines. Step141 SQLite migration smoke PASS.
- `python3 -m compileall -q tools`: PASS.
- ZIP integrity: verified after packaging.

## Not verified / not done
- No Kotlin compiler/Android compile; no Room/Compose/WorkManager runtime test; no APK build/install or phone test.
- No live Supabase SQL/bucket/RLS/RPC changes, TUS upload/download, or staging verification.
- No periodic schedule activation or enqueue; no Storage object deletion; no account/profile/inventory data mutation; no GitHub push.
- No Gradle used.

## Safety
- No Room schema change (still v14).
- No Supabase/live project changes.
- No service-role key requested or used.
