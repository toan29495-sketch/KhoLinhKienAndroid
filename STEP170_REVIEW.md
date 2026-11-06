# Step170 Review — Weekly backup recovery cancellation responsiveness

## Scope
Make owner-scoped startup/retry recovery respond promptly to coroutine cancellation, especially while validating large READY/UPLOADING ZIP archives, without weakening owner isolation or changing resumable TUS bytes.

## Changes
- `WeeklyBackupRecovery.recoverOwner` captures the current coroutine context and checks `ensureActive()` plus the authenticated owner before processing each journal row.
- The READY/UPLOADING SHA-256 verifier is now a suspend function. It checks cancellation and active owner before each 64 KiB read and again before finalizing the digest.
- PREPARING partial-archive deletion, invalid-journal FAILED transition, and VERIFIED local cleanup recheck cancellation and owner immediately before the local destructive/mutating operation.
- READY/UPLOADING archive bytes are still read-only; valid archives retain the exact canonical path and bytes required by the TUS fingerprint. Suspect archives are still retained for diagnosis when marked FAILED.
- Updated Step163 source verifier for the owner-aware suspend hash signature; no behavior regression intended.

## Verification
- `verify_step170_weekly_backup_recovery_cancellation.py`: 11/11 PASS.
- `verify_step169_weekly_backup_cancellation.py`: 14/14 PASS.
- Step140–170 targeted regression: 31/31 verifier scripts PASS, including Step163 19/19.
- Python compileall PASS.
- ZIP integrity and SHA-256 recorded with the artifact.

## Not verified / not done
- No Kotlin/Android compilation, WorkManager runtime cancellation test, APK build/install, or phone test.
- No live Supabase/TUS/Storage/RLS/staging test.
- No schedule enqueue/activation, account/profile/inventory mutation, remote/local retention deletion, or GitHub push.
- No Gradle used, per explicit instruction.

## Safety
- Room remains v14; no SQL/backend/bucket/RLS/RPC change.
- No remote objects or user data changed.
- Cancellation propagates as `CancellationException`; READY/UPLOADING files are never rewritten or deleted by this recovery change.
