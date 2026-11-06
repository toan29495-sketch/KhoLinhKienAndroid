# Step209 Review — 30-day session age guard and automatic backup cadence

Date: 2026-11-05

## Scope
Implement the user-selected 30-day sign-in age and 30-day automatic cloud-backup cycle while preserving existing owner isolation, offline inventory, backup journal recovery, and restore safety.

## Changes
- Added `SessionExpiryPolicy.kt` with a per-owner authentication timestamp synchronously committed to app-private preferences, a 30-day absolute age threshold, and fail-closed behavior for missing/future timestamps.
- Successful login records the timestamp before profile retrieval. Session age is checked on cold-start restoration and app foreground return. Expiry cancels only that owner's sync, restore-recovery, and backup work; it signs out and clears only the timestamp. Room inventory and local business data are not deleted.
- Changed periodic backup interval from 7 to 30 days and switched to `ExistingPeriodicWorkPolicy.UPDATE`, so an existing 7-day periodic job receives the new spec instead of being left unchanged by KEEP.
- Wired periodic scheduling to the authenticated profile lifecycle after the forced-password-change gate. The existing worker continues to enforce owner identity, active profile, session matching, connected network, durable journal recovery, and verified upload.
- Kept manual one-time backup independent from the periodic schedule.

## Verification
- `verify_step145_cold_start_session_restore.py`: 12/12 PASS
- `verify_step164_weekly_backup_worker.py`: 23/23 PASS
- `verify_step165_weekly_backup_identity_lifecycle.py`: 12/12 PASS
- `verify_step171_weekly_backup_logout_consistency.py`: 11/11 PASS
- `verify_step175_weekly_backup_relogin_recovery.py`: 18/18 PASS
- `verify_step208_backup_trust_model.py`: 9/9 PASS
- `verify_step209_session_expiry_and_backup_cadence.py`: 12/12 PASS
- `python -m compileall -q tools`: PASS

## Limitations and risks
- The 30-day app-side guard is based on device wall-clock time and app-private preferences. It is not tamper-proof and does not revoke a stolen refresh token on the server. Missing timestamp on an upgraded installation forces one re-login.
- Supabase's server-enforced Time-box user sessions is a separate Auth project setting. Current Supabase documentation states this feature is available on Pro plans and above. The connector confirms the project is ACTIVE_HEALTHY but does not expose the Auth session timeout setting or plan entitlement. No server-side 30-day enforcement is claimed.
- WorkManager periodic execution is best-effort and requires CONNECTED network; Android may defer execution under system constraints. The first periodic run is scheduled by WorkManager using the 30-day interval, not an immediate backup.
- No Kotlin/Compose compilation, APK build, Android device test, live backup upload, or restore runtime test was performed. No Gradle execution, Supabase setting/data mutation, deployment, or GitHub push was performed.
