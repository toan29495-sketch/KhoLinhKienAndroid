# Step144 review — foreground profile refresh (2026-11-03)

## Scope
Continued from the Step143 working tree. Added an app-foreground refresh of the signed-in profile so role, active status, display name, and password-change state are re-read from Supabase when the activity returns to `ON_START`.

## Source changes
- `AuthRepository.kt`: introduced `InactiveProfileException`; an explicitly inactive profile still signs out, but is now distinguishable from transport/decoding failures.
- `MainActivity.kt`: registered a `LifecycleEventObserver` with `DisposableEffect`; refresh runs on `Dispatchers.IO`, is guarded against overlapping refreshes, propagates coroutine cancellation, and only applies the result if it still belongs to the same current account.
- A transient refresh failure retains the existing profile and local UI/data, and reports that the profile could not be refreshed. It does not sign out the user.
- When the refreshed role changes Admin → KTV, the existing profile-keyed effects run: Admin history UI is cleared, Step143 removes foreign-owner movement cache and resets the Admin-global cursor, and `SyncWorkScheduler` cancels Admin-global workers. KTV → Admin promotion similarly reschedules Admin pull and refreshes the global history path.
- No Room schema, SQL/RPC, Edge Function, Storage, Auth user, or server data changes.

## Verification
- `tools/verify_step144_foreground_profile_refresh.py`: 14/14 PASS.
- Step143 downgrade/cache verifier: 11/11 PASS.
- Step142 remote reconciliation verifier: 12/12 PASS.
- Step141 restore contract verifier: 15/15 PASS.
- Step140 cloud-photo memory-only verifier: 14/14 PASS.
- `python -m compileall -q tools`: PASS.

## Known remaining issue
The cold-start session restoration block still signs out on any `getCurrentProfile()` exception, including a temporary network failure. Step144 deliberately limits the fix to foreground refresh after a profile is already loaded. A separate change should make cold-start behavior distinguish inactive/invalid sessions from offline/transient errors and provide a clear retry path without discarding the Supabase session.

## Not performed
No Gradle, Android framework/Compose compilation, Room annotation processing, WorkManager runtime test, emulator/device test, APK build/install, QR scan test, live Supabase mutation, SQL migration, Edge deployment, Storage change, or GitHub push. The lifecycle behavior is source-verified only and still needs phone testing.
