# RESTORE_COMMIT pull protocol (Bước 5)

## Current implementation status — Step141 (2026-11-03)

The owner-feed path now handles `RESTORE_COMMIT` by creating/resuming an owner-scoped `REMOTE_FEED` Room journal, downloading immutable committed chunks, verifying each chunk through the owner-only RPC, and applying the snapshot through the existing Room transaction. The Room 12→13 migration stores the source type and expected chunk counts so process death can resume without a local ZIP payload.

- The marker cursor is not advanced until the snapshot has been applied, or a matching `COMPLETE` journal proves this device already applied the same restore ID, digest, counts and cursor.
- If local outbox work exists, the worker leaves the marker cursor unchanged and retries; it does not delete or discard local operations.
- The Admin-global feed only advances its separate global cursor for restore markers. It never replaces the Admin's own inventory with another KTV's snapshot.
- Interrupted `REMOTE_FEED` journals resume automatically. Local ZIP restore journals remain owned by `RestoreCloudCoordinator` and are not hijacked by the pull worker.
- Remote restore uses no ZIP or image staging directory; cloud photo paths remain cloud references.

The deployed Step139 RPCs are already present on the connected Supabase project. Step141 changes Android/Room source only; no Supabase SQL or account data was changed in this step.

The older notes below describe the original design gate and historical implementation boundary. They should be read as design history, not as the current Step141 status.

---


Historical status note from the original protocol draft (superseded): Step139 deployed the SQL restore RPCs and Step141 connected the owner pull worker to the committed snapshot flow. See the current implementation status at the top of this document.

## Why the current pull loop cannot consume RESTORE_COMMIT

Original Step135 design finding (superseded by Step141): the pull loop then accepted only `PART` and `MOVEMENT`; a marker could have been skipped. Step141 adds durable `REMOTE_FEED` journals, bounded chunk download, per-chunk server verification, and owner-scoped Room snapshot/cursor replacement before cursor advancement.

## Required server contract

The commit transaction must:

1. Lock the owner and restore session.
2. Verify all expected chunk indexes and row counts for both entity types.
3. Validate UUID uniqueness, `(owner_id, code)` uniqueness, movement fields, and Storage object paths scoped to `auth.uid()`.
4. Replace only `parts` and `movements` belonging to `auth.uid()`.
5. Mark the restore session `COMMITTED` and insert exactly one `RESTORE_COMMIT` event in the same transaction.
6. Return the committed event cursor and retain immutable staged chunks until the client acknowledges local reconciliation.

The feed migration must extend the `entity_type` constraint to include `RESTORE_COMMIT`. The pull RPC must expose this event in strict event order.

## Required snapshot-read contract

A dedicated authenticated RPC must read the committed session by `(restore_id, owner_id = auth.uid())`, verify `status = COMMITTED`, and return staged chunks in deterministic order. It must not read the mutable live tables page-by-page because rows can change while a multi-request snapshot is being read. Staged chunks are the immutable source for this restore. The response includes restore ID, manifest digest, expected part/movement counts, entity type, chunk index, row count and payload.

A separate acknowledgement RPC may mark local reconciliation complete; it must not delete staging until the client has durably committed the local snapshot and cursor. Cleanup must be owner-scoped and safe to retry.

## Required Room staging

Add local staging entities keyed by `(restoreId, entityType, chunkIndex)` and persist each downloaded chunk before requesting the next one. Validate the server-returned restore ID/digest/counts against the active `RestoreJournal`. A repeated identical chunk is idempotent; a different payload for the same key aborts the restore.

Do not write staged rows into `parts` or `movements` while downloading. After all chunks are present, verify contiguous indexes and aggregate counts, then in one Room transaction:

- replace only rows for the target owner;
- clear only that owner's obsolete outbox entries;
- write the returned feed cursor;
- mark the journal `COMPLETE`;
- remove local staging rows for this restore.

The transaction must not touch other owners' parts, movements, cursors, outbox or journals. The Admin global-history cursor is separate and must never be used as the Admin's personal inventory cursor.

## Pull worker state machine

- Normal `PART`/`MOVEMENT`: preserve current owner scoping and unsent-local-change behavior.
- `RESTORE_COMMIT`: stop processing later events; persist a journal/control state; download immutable snapshot chunks; validate; execute the Room replacement transaction; only then continue from the marker cursor.
- Failure at any point before the Room transaction: retain current live inventory and journal; retry from the last persisted chunk.
- Process death: the next worker resumes the journal and re-fetches missing chunks idempotently.
- Failure after the Room transaction but before server acknowledgement: detect the completed local journal/cursor, acknowledge idempotently, and do not reapply the replacement.

## Admin global history

An Admin's global movement feed may encounter a KTV `RESTORE_COMMIT`. It must not replace the Admin's own inventory. The Admin global-history worker should treat the marker as a signal to refresh only the affected KTV's history through an explicitly owner-scoped admin RPC, or safely advance its separate global cursor if that refresh is not part of this release. It must never call the ordinary owner snapshot replacement path for another KTV.

## Release gate

Do not deploy the commit RPC or emit `RESTORE_COMMIT` until all of the following exist together: feed constraint migration, atomic commit RPC, immutable snapshot-read RPC, local Room staging entities/migration, journal recovery, worker handling, and tests for interruption/replay/owner isolation. No Gradle build or APK is part of this design step.

## Step 16 implementation note
`RestoreCloudSnapshotReader` now reads the committed PART/MOVEMENT chunks into separate Room staging entity types (`SNAPSHOT_PART`, `SNAPSHOT_MOVEMENT`). It validates the committed session envelope and chunk boundaries. It deliberately does not replace live Room tables; the atomic local swap remains a later, separately reviewed step.
