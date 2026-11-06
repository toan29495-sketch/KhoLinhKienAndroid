# Step107 review – owner-isolated local pending photos (2026-11-03)

## Scope
Audit the same-device account-switch boundary for KTV-local data, with emphasis on private photo files referenced by owner-scoped Room rows and Outbox operations.

## Finding
- Room inventory, movement, Outbox, cursor, tombstone and restore queries are owner-scoped; WorkManager names and input data are owner-scoped; cloud photo object paths already include owner/entity/operation IDs; cloud photo cache is cleared after successful logout.
- New camera and gallery photos were written into one shared `filesDir/pending_part_photos` directory. Room rows still carried `ownerId`, and the sender verified the authenticated owner, but the local file namespace itself did not express ownership. Timestamp-only filenames also had avoidable collision risk.
- The photo sender accepted any absolute local path in an Outbox payload, rather than restricting upload candidates to the app's pending-photo namespace.

## Changes
- New camera captures and gallery imports are written to `filesDir/pending_part_photos/<ownerId>/`.
- New filenames use random UUIDs rather than millisecond timestamps.
- The photo picker helper now requires the active owner ID and validates its UUID-shaped form.
- `SyncOutboxSender` receives the app-private files root and only uploads files whose canonical parent is exactly the active owner's pending directory.
- A narrow compatibility exception remains for legacy files directly inside `pending_part_photos/` with `pending_` filenames, so existing unsent Outbox entries created by older app versions are not stranded. No new writes use this legacy directory.
- Local photo preview rejects paths outside app-private storage and checks owner identity for the new owner-scoped pending-photo namespace.
- Existing Room owner scoping, Outbox operation IDs/payloads, cloud paths, movement history and account-specific WorkManager scheduling are unchanged.

## Verification
- `tools/verify_step107_owner_local_photo_isolation.py`: 14/14 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 static checks + SQLite schema/atomic replacement smoke test PASS.
- Every `tools/verify_step73_*.py` through `tools/verify_step107_*.py`: PASS.
- ZIP integrity and SHA-256 are verified for the immutable Step107 checkpoint.

## Limitations / not performed
- This is source-level isolation and deterministic/static regression only; no Android runtime, same-device multi-account instrumentation, or real file-permission test was run.
- No Supabase/PostgreSQL staging, SQL deployment, APK build/install, Edge Function deployment, GitHub push, or production change.
- Legacy pending-photo files remain in the old shared directory until their existing Outbox references are successfully acknowledged and deleted. They are retained intentionally to avoid losing unsent uploads.
- Restored local photo files in the historical `part_photos` directory remain app-private and reachable only through owner-scoped inventory rows; this step does not relocate existing restored files because doing so safely requires a Room/photo-path migration and reference-aware cleanup.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source.
