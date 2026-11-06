# Step163 — Weekly backup crash recovery

## Scope
Implemented owner-scoped local recovery for the durable weekly backup journal introduced in Step162. No Room schema change and no Supabase operation.

## Changes
- Added `WeeklyBackupRecovery.recoverOwner(context, dao, ownerId)`.
- Added owner-filtered DAO query `weeklyBackupsForOwner`.
- Invoked recovery after an authenticated profile is loaded in `MainActivity` (on `Dispatchers.IO`), and defensively before preparation/upload retry.
- `PREPARING`: remove a truncated/incomplete ZIP; keep the journal identity so preparation can rebuild safely before any upload is allowed.
- `READY` / `UPLOADING`: preserve exact archive path and bytes only when file size and SHA-256 match. If missing or changed, mark journal `FAILED`; never regenerate under the same TUS identity.
- `VERIFIED`: retry local ZIP cleanup idempotently; retain journal metadata for audit.
- `FAILED` / `ABANDONED`: retain for audit; no automatic retry or deletion.
- Canonical path must be exactly `filesDir/weekly-backup-staging/<ownerId>/<backupId>.zip`; no traversal or cross-owner cleanup.

## Verification
- Step162 regression: 23/23 PASS.
- Step163 recovery contract + restart fixtures: 19/19 PASS.
- `python3 -m compileall -q tools`: PASS.
- No new Room migration required; schema remains v14.

## Not performed
- No Kotlin/Android compile, APK build/install, emulator or phone test.
- No WorkManager schedule/worker connection.
- No live Supabase RPC, Storage/TUS or RLS test; no SQL applied.
- No GitHub push.
- Gradle intentionally not used.

## Next
Step164: implement an explicit owner-scoped weekly backup coordinator and Worker contract, but keep scheduling disabled until the backend candidate is approved/applied and the worker's auth/session, cancellation, network constraints and idempotent retry behavior are verified.
