# Step145 review — cold-start session restore (03/11/2026)

## Scope
Continue from the Step144 archive/source. Fix the cold-start path where any profile-fetch exception previously caused an automatic Supabase sign-out.

## Change
- `InventoryApp` now uses one `restoreSession` suspend flow for initial launch and manual retry.
- If there is no Supabase session, show the normal login screen.
- If profile lookup succeeds, apply the server profile and role.
- If the repository explicitly reports `InactiveProfileException`, clear the in-memory profile. The repository attempts to sign out and preserves coroutine cancellation.
- If the lookup fails for a transient network/server/decoding reason, do not call sign-out and do not expose cached Admin/KTV UI. Keep the Supabase session, show a dedicated retry screen, and let the user retry or explicitly sign out.
- Retry runs the same restore flow. The screen states that local data is preserved and the inventory remains locked until the account is verified.
- Existing foreground refresh and Step143 role-transition cleanup remain unchanged.

## Verification
- `tools/verify_step145_cold_start_session_restore.py`: 12/12 PASS.
- `tools/verify_step144_foreground_profile_refresh.py`: 14/14 PASS.
- `tools/verify_step143_admin_downgrade_local_cache.py`: 11/11 PASS.
- `tools/verify_step142_remote_reconciliation.py`: 12/12 PASS.
- Step141 restore remote contract: 15/15 PASS.
- Step140 cloud photo memory-only: 13/13 PASS.
- `python -m compileall -q tools`: PASS.
- ZIP integrity and SHA-256 checked after packaging.

## Safety / unchanged areas
- No Supabase SQL, migration, RPC, Edge Function, Auth account, Storage bucket/object, or server data changes.
- No local business data reset or deletion.
- No Gradle use.
- No Android framework/Compose compilation, APK build/install, physical-device test, QR test, two-device test, or GitHub push. Static source checks do not prove runtime behavior.

## Remaining checks
- Verify retry behavior on actual Android device under airplane mode / reconnect.
- Verify locked account and Admin→KTV downgrade while app is open.
- Continue source audit of foreground/background WorkManager session fencing.
