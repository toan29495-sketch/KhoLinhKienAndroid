# Step103 – Apply isolated KEEP_SERVER conflict resolution (2026-11-03)

## Finding
`KEEP_SERVER` could be saved for `PART_UPSERT`, but there was no executor. The UI explicitly said it was not applied and the owner queue stayed blocked. Applying a server snapshot without strict isolation could overwrite local queued work, resurrect a tombstone, cross owner boundaries, or replace a different local part that happens to own the server code.

## Change
- Added `SyncConflictKeepServerResolver`: performs a fresh authenticated owner-scoped snapshot read, requires a live (not deleted) server row, validates owner UUID/entity UUID and positive server version, and parses all part fields into a Room `Part`.
- Added atomic DAO `resolveIsolatedKeepServer`: only accepts a READY_FOR_SERVER_RECHECK `KEEP_SERVER` decision for `PART_UPSERT`; requires the same owner/entity, existing local row, no dependent/attempted operations, no active restore, and no conflicting local owner of the server code.
- In one Room transaction, replace only the target local part with the fresh server snapshot, mark the blocked original operation RESOLVED, and mark the decision SERVER_APPLIED. No movement rows are deleted or rewritten.
- Added explicit confirmation UI. The queue is unblocked only after the DAO transaction succeeds. Any failed precondition leaves local data, blocked operation and queue intact.
- `PART_DELETE`, `STOCK_CHANGE`, deleted/missing server rows, and non-isolated dependency chains remain manual-only.

## Verification
- `tools/verify_step103_keep_server_apply.py`: 13/13 static assertions PASS.
- Full regression: `tools/verify_sync_step49_72.py` 24/24 + SQLite smoke; every Step73–103 verifier passed.
- ZIP integrity and SHA-256 verified.

## Not performed / limitations
- No Android compile/runtime/device test, PostgreSQL/Supabase staging test, SQL deployment, APK build, Edge Function deployment, GitHub push, or production change.
- The snapshot RPC must already be deployed and its JSON timestamp/field contract must be verified against the live schema before this path is used in production.
- Current resolver intentionally supports only isolated `PART_UPSERT`; queued dependents or any attempted operation remain blocked for manual review. It does not auto-discard or rewrite movements.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source.
