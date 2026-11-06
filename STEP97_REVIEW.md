# Step97 – Bound restore staging resources (2026-11-03)

## Scope
Audit restore-session/chunk staging for abandoned sessions and unbounded per-owner resource use. Preserve committed immutable snapshots because other devices may still need them for snapshot pull.

## Findings
- `inventory_restore_sessions` had no per-owner active-session cap.
- `inventory_restore_chunks` limited each JSONB chunk to 1 MiB and 200 rows, but had no cumulative byte or chunk-count cap.
- STAGING sessions had `updated_at`, but no expiry/cleanup path was exposed to the Android restore sender.
- Committed chunks are intentionally retained and are not safe to garbage-collect without a separate snapshot-retention protocol.

## Changes
- Added migration `202611030009_bound_restore_staging_resources.sql`.
- Added owner-bound `cleanup_my_inventory_restore_staging()` SECURITY DEFINER RPC. It requires an authenticated active profile and expires only that owner's STAGING sessions whose `updated_at` is older than 14 days. Expired sessions become ABORTED; only chunks returned by the same expiration CTE are deleted.
- Added a trigger limiting each owner to one active STAGING restore session and rejects manifests declaring more than 8,192 combined chunks.
- Added a per-owner serialized chunk-insert trigger enforcing a maximum of 8,192 staged chunks and 256 MiB of cumulative JSONB payload for active STAGING sessions.
- Added explicit authenticated-only EXECUTE grant for cleanup RPC; trigger functions are not client callable.
- Updated `RestoreCloudSessionSender` to invoke cleanup before begin/resume and recheck the authenticated user ID afterward.
- COMMITTED snapshots, commit ledger rows, live inventory, and ABORTED session metadata are not deleted by this cleanup.

## Verification
- `tools/verify_step97_restore_staging_quota.py`: 25/25 PASS (static assertions + deterministic boundary model).
- `tools/verify_sync_step49_72.py`: 24/24 + SQLite schema/atomic replacement smoke PASS.
- All verifier scripts Step73–97 passed.
- ZIP integrity and SHA-256 verified for the Step97 checkpoint.

## Limits / deployment notes
- No PostgreSQL/Supabase staging execution or catalog-level trigger/RPC test was performed.
- The reconstructed source archive does not include the original base schema migration, so live table owners, grants, triggers, and pre-existing cleanup jobs must be inspected before deploying.
- A local restore journal older than 14 days can encounter an expired server session after cleanup; the current client fails closed rather than silently reusing an ABORTED session. User may need to reselect/reimport the ZIP to create a new restore ID. A future UX improvement can offer a safe restart path.
- Committed snapshots are retained indefinitely because the server currently has no device acknowledgement/retention protocol proving they are no longer needed. This step bounds uncommitted staging, not committed snapshot storage.
- No APK build, Android device test, SQL deployment, Edge Function deployment, GitHub push, or production change.
- Source lineage remains reconstructed Step49–72 branch, not byte-for-byte restoration of the lost original source.
