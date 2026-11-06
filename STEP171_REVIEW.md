# Step171 Review — Weekly backup logout and account-lifecycle consistency

## Scope
Close owner-scoped WorkManager lifecycle gaps when a profile becomes inactive or a user signs out from the session-recovery screen where `currentProfile` is null.

## Changes
- `AuthRepository.currentUserId()` exposes only the current authenticated Supabase user ID so the UI can capture the owner before profile validation can sign out the session.
- During cold/session restore, `MainActivity` captures the owner ID before `getCurrentProfile()`. If the profile is inactive and `AuthRepository` signs out, the UI cancels only that canonical owner's weekly unique work afterward.
- The session-recovery sign-out action now obtains the owner from the live auth session instead of `currentProfile` (which is null on that screen), and cancels only that canonical owner's weekly work before sign-out.
- `WeeklyBackupWorker` retires only its own owner's periodic work when profile validation reports `InactiveProfileException`; it does not cancel any other owner's work.
- Existing explicit logout ordering remains cancel-owner-work before auth sign-out. Admin-to-KTV role changes remain unaffected; Admin's own warehouse backup remains tied to the same owner ID.
- Weekly schedule remains opt-in; no login/startup enqueue was added.

## Verification
- `verify_step171_weekly_backup_logout_consistency.py`: 11/11 PASS.
- Step140–171 regression: 32 verifier scripts PASS / 0 FAIL (includes Step163–170 contracts).
- Python `compileall` PASS.
- ZIP integrity and SHA-256 verified for the Step171 artifact.

## Not verified / not done
- No Kotlin/Android compilation, APK build/install, or physical-device WorkManager/logout test.
- No live Supabase Auth, Storage, TUS, RLS, or staging test.
- No weekly schedule activation/enqueue, account/profile/inventory/history mutation, backend/schema change, or GitHub push.
- No Gradle used, per explicit instruction.

## Safety
- No Room schema migration; Room remains v14.
- No SQL, bucket, policy, RPC, Storage object, or server-side change.
- All cancellation is owner-specific and guarded by canonical UUID validation. No READY/UPLOADING archive bytes or TUS fingerprints are changed.
