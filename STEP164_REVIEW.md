# Step164 review — Weekly backup WorkManager worker contract

Date: 2026-11-03
Baseline: Step163 working tree (`/mnt/data/step154_work`), no rollback to earlier ZIP.

## Changes
- Added `WeeklyBackupWorker.kt` with owner UUID validation, authenticated-session fencing, active-profile reload, inactive/must-change-password guard, owner-scoped journal recovery, stable active `backupId` reuse, durable archive preparation, and delegation to the existing verified TUS uploader.
- CancellationException is propagated. Transient failures return WorkManager retry. Account/session mismatch exits without accessing another owner's data.
- Added a per-owner unique seven-day periodic scheduler API with CONNECTED network constraint, exponential backoff, KEEP policy, and exact-owner cancellation.
- Scheduler is intentionally NOT called from login, startup, or existing sync scheduling. This step implements/reviews the worker contract only; no periodic task is enqueued.
- No Room schema change (v14), no Supabase SQL/API changes, no Storage object changes, no service-role use, no retention deletion.

## Verification
- `tools/verify_step164_weekly_backup_worker.py`: 22/22 PASS.
- Step163 recovery verifier: 19/19 PASS.
- Full Step140–164 regression suite: 416/416 PASS across 25 verifier outputs; Step141 SQLite smoke PASS.
- `python3 -m compileall -q tools`: PASS.
- ZIP archive integrity: PASS (after packaging).

## Not verified / not performed
- No Kotlin compiler or Android framework compile; no APK build/install; no physical-device/WorkManager runtime test.
- No live Supabase auth/profile/RPC/TUS/Storage/RLS test. The Step160 SQL remains a candidate and is not applied.
- No scheduler activation, no live data mutation, no retention cleanup, no GitHub push.
- Gradle was not used, per explicit user instruction.

## Next
- Step165 should add safe activation/deactivation at authenticated profile lifecycle, including role/account switch and logout, but only after the backend contract is approved/applied and worker can be exercised in a controlled staging environment. Keep scheduling disabled until then.
