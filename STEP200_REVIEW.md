# Step200 Review — Explicit owner-scoped Cloud backup entrypoint

Date: 04/11/2026

## Baseline and provenance
- Exact baseline: `KhoLinhKienAndroid-step199-atomic-restore-reservation-20261104.zip`
- Baseline SHA-256: `85687f2d0c41e3e0678397d74dc07c002f7145c735979067876e95fc946f7e7b`
- Preserves Step198 process-death recovery and live Room journal observation, plus Step199 atomic restore reservation.

## Work completed
- Added `WeeklyBackupWorkScheduler.enqueueNow(ownerId)` for a one-time owner-scoped WorkManager request with connected-network constraint, exponential backoff, and `ExistingWorkPolicy.KEEP`.
- Added the explicit **Sao lưu Cloud ngay** action to Tools. It checks the authenticated owner, is disabled while a restore is active, and reports only that the request was queued. It does not claim the backup is complete.
- One-time and periodic backup work are cancelled together on owner sign-out; weekly recurring scheduling remains separate and is not activated.
- Updated uploader documentation to describe the actual Worker pipeline: durable local journal/archive, metadata registration, resumable TUS upload with `upsert=false`, authenticated readback, exact size/SHA-256 validation, remote VERIFIED RPC and local journal confirmation.
- Re-applied Step198 recovery scheduler, Flow-backed journal state and owner-scoped cancellation on top of the Step200 UI changes; no Step198 functionality was dropped.

## Verification
- `verify_step200_manual_cloud_backup_entrypoint.py`: 18/18 PASS.
- Step199 atomic reservation: 13/13 PASS.
- Step198 process-death recovery: 28/28 PASS.
- Step190–Step197 regression: 121/121 PASS.
- Combined targeted checks: 180/180 PASS (no double counting; Step194's 17 checks are already within Step190–197).
- `python -m compileall -q tools`: PASS.

## Not verified / unfinished
- Static/source-contract verification only. No Kotlin compilation, APK build/install, Android device test, TUS/Storage/RPC execution, or real SHA-256 round-trip on Android.
- WorkManager live progress/result is not yet surfaced in the UI. The UI reports queueing only; users must refresh Cloud catalog and confirm `VERIFIED`.
- Weekly recurring backup remains disabled and opt-in; no scheduler call site was added.
- No Gradle, Supabase schema/policy/data/metadata/Storage mutation, restore execution, or GitHub push.
