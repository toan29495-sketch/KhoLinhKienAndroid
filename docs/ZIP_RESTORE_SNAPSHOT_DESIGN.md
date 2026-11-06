# ZIP restore: safe cross-device snapshot design

Status: approved simplified restore strategy (2026-11-02); implementation gate — do not enable automatic sync after restore until every phase below is implemented and verified.

## User-approved data policy

The current app data is disposable test data. Restore does **not** need to preserve or merge the existing inventory for the selected account. It may replace that account's local and cloud inventory/history with the ZIP snapshot. This permission is strictly owner-scoped: never delete another KTV's inventory or movements, and do not treat Admin global-history access as permission to overwrite other owners. The UI must clearly warn that the selected account's current test data will be replaced. The ZIP remains the source of truth for the restored owner.


## Current blockers confirmed in source (2026-11-02)

1. `ZipRestore.restore()` copies photo entries to `filesDir/part_photos`; this violates the agreed cloud-first rule. Restored photos must first live in a temporary pending-upload directory.
2. The restore transaction replaces local parts and movements but does not replace/clear old `sync_outbox` rows or per-owner `sync_cursors`. Old queued operations can replay against the restored snapshot; old pull cursors can skip required cloud events.
3. Current `apply_inventory_operation` supports one part/movement operation at a time, not an atomic inventory snapshot. Replaying ordinary operations is not equivalent to restore: stock operations are additive and may duplicate stock/history.
4. The current cloud pull worker can race with restore unless the owner is explicitly put into a restore/sync-paused state.
5. Storage object paths are owner-scoped. A restored photo must be uploaded under the *currently authenticated* owner folder; the backup's previous device paths must never be reused as local paths or remote object paths.

## Required restore protocol

### Phase A — preflight (no mutations)

- Stream ZIP entries to a private staging directory with entry-count, per-entry, total-size, traversal, duplicate-name, and checksum validation.
- Validate manifest version, owner binding, row counts, all UUIDs, duplicate part codes, movement references, photo references, and supported photo formats/sizes.
- Confirm the authenticated Supabase user equals the requested owner. Do not permit a backup from another account to be restored into this account implicitly.
- Keep existing Room rows, outbox, cursors, and existing photos untouched until preflight succeeds.

### Phase B — pause and stage

- Acquire a per-owner restore mutex/lease and cancel or defer both push and pull workers for that owner. Since current data is disposable, do not build a merge/conflict-resolution flow; replace only the selected owner snapshot.
- Copy ZIP photos only to `filesDir/pending_part_photos/restore_<restoreId>/`; these are temporary pending uploads, never permanent `part_photos` files.
- Record a durable restore journal (restoreId, ownerId, manifest digest, phase, counts, staged paths). On process death, resume or safely clean the staged operation; never silently delete an uncommitted restore.

### Phase C — atomic cloud snapshot

- Upload staged photos to private Storage paths `<authenticatedOwnerId>/<partSyncId>.<ext>` with upsert.
- Read each uploaded object back through the authenticated session and verify non-empty content (preferably SHA-256 against ZIP photo digest).
- Invoke a dedicated `restore_inventory_snapshot` RPC with a stable restore UUID, manifest digest, complete part snapshot and movement snapshot (chunked payloads may be staged server-side, but final commit must be atomic).
- RPC must be SECURITY DEFINER with fixed search_path, require `auth.uid()` and active profile, lock owner restore state, verify idempotency by restore UUID + manifest digest, validate every owner/UUID/reference, and replace that owner's parts/movements in one database transaction. It must not accept client-supplied owner IDs. Existing rows for `auth.uid()` may be deleted because the user explicitly approved replacement of test data; rows belonging to every other owner must remain untouched.
- RPC writes the corresponding change-feed events in the same transaction and returns a committed snapshot version/cursor. A retry with the same restore UUID and digest returns the prior committed result; same UUID with a different digest fails.
- Never implement restore by replaying `STOCK_CHANGE` operations: these are additive and can double-count stock.

### Phase D — local commit and reconciliation

- After the cloud RPC confirms commit, replace Room parts/movements in one Room transaction, preserve backup `syncId` values, set `photoPath` to the newly uploaded cloud object path, clear only the restored owner's obsolete outbox rows, and set that owner's cursor to the RPC-returned snapshot cursor.
- Do not advance any other owner's cursor or alter other KTV data.
- If local commit fails after cloud commit, retain the restore journal and retry local reconciliation from the server-confirmed snapshot; do not resume normal pull/push yet.
- Once Room reconciliation succeeds, remove staged photo files. Keep a recoverable journal until cleanup is confirmed.
- Resume pull/push only after local and cloud snapshot identifiers match. Pull events newer than the returned snapshot cursor.

## Failure rules

- Preflight/upload/RPC failure before cloud commit: existing local data remains unchanged; keep or clean staged files according to a recoverable journal; no local destructive write.
- Cloud commit success but local commit failure: pause sync and retry reconciliation; never pull older cloud data over the device or replay old outbox rows.
- Local commit success but temp cleanup failure: data remains valid; retain journal and retry cleanup.
- No permanent local photo copy is created by restore. Normal viewing remains RAM-only; explicit user download is the only persistent device save.

## Verification gate before release

1. Restore a ZIP with no photos, one photo, and many photos; test large files beyond 50 MB without loading whole ZIP/JSONL/photos into RAM.
2. Corrupt each ZIP component and checksum; verify rejection before any Room/cloud mutation.
3. Kill the app during each phase; verify safe resume/recovery and no partial destructive restore.
4. Restore on a second device; verify all photo paths resolve from cloud and no old device path exists in Room.
5. Restore while there are pending/failed outbox rows and nonzero pull cursor; verify old operations cannot replay and cursor starts at returned snapshot cursor.
6. Verify KTV restore only affects that KTV; Admin's own inventory remains owner-scoped; Admin global history behavior remains separate.
7. Verify retry idempotency: repeated same restore UUID does not duplicate parts/movements or stock.
8. Verify ZIP v2 streaming, checksums, JSONL row counts, UUID preservation, and photo hashes.

Until the dedicated RPC, journal, pause/resume coordination, and recovery tests exist, cross-device cloud restore is **not complete** and the release build must remain blocked.


## User-approved simplification (2026-11-02)
- No attempt is required to preserve or merge old test data belonging to the account being restored. The ZIP snapshot replaces that owner's inventory and history.
- The implementation must still be atomic: a failed preflight/upload/RPC must not leave half-restored Room or cloud data.
- Scope remains strict: restore may replace only the authenticated owner. Admin global-history visibility does not grant permission to replace KTV data.
- After cloud snapshot commit, clear that owner's obsolete outbox rows and reset only that owner's cursor to the server-returned snapshot cursor.


## Implementation progress (2026-11-02, local working copy)
- Added Room `restore_journal` entity/table (schema v7) with migration 6→7 and DAO methods to persist a restore state, inspect active restore, clear only the selected owner's outbox/cursor, and prune completed journals.
- Added a guard to both WorkManager push and pull workers: while an unfinished restore journal exists for the owner, workers return retry and do not mutate cloud/Room state.
- Added draft Supabase migration `202611020004_restore_inventory_snapshot.sql` with owner-scoped, SECURITY DEFINER atomic snapshot replacement, UUID+manifest idempotency, owner-folder photo path validation, movement-to-part validation, fresh change-feed snapshots, and returned cursor. The RPC deletes/replaces only `auth.uid()` rows; it does not grant Admin access to other owners' data.
- IMPORTANT LIMITATION: the current RPC draft accepts complete JSONB arrays in one request. It is not yet suitable for very large ZIP backups and has not been wired to the Android restore flow. A chunk-staging protocol (begin/stage/commit) is still required before enabling it for production-size backups.
- `ZipRestore.restore()` still uses the old local-only flow and still copies photos to permanent `filesDir/part_photos`; this has NOT been fixed yet. No restore journal is written by the restore UI yet, so the new worker gate is groundwork only. Do not run this migration or enable the RPC until chunk staging and Android integration are completed and reviewed.
- No SQL parser/database execution, Supabase migration, Kotlin type-check, Gradle build, device test, or GitHub push has been performed in this turn.

## Durable local staging table (implemented in source, 2026-11-02)
- Room schema version 8 adds `restore_staged_rows`, keyed by `(restoreId, entityType, chunkIndex, rowIndex)` and indexed by `(ownerId, restoreId, entityType, chunkIndex)`.
- DAO supports batch insert, ordered read, count, and owner/restore-scoped cleanup. The table stores JSON rows separately from live `parts` and `movements`.
- Migration 7→8 is registered in the Room database builder. It does not clear or rewrite live inventory.
- This is only the durable local storage primitive. ZIP preflight has not yet been wired to populate it; cloud begin/stage/commit calls, local atomic reconcile, and recovery coordinator remain outstanding. Do not describe restore as complete or enable the old `ZipRestore.restore()` as cloud restore.
