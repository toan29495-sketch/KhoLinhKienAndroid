# ZIP restore — chunk staging protocol

# Current status update — Step141 (2026-11-03)

The remote owner-feed consumer is now implemented in `SyncPullWorker`: it resumes `REMOTE_FEED` restore journals, reads the chunk counts from the RESTORE_COMMIT marker, verifies immutable snapshot chunks, applies the owner snapshot and cursor atomically, and only then advances the feed. Admin-global markers remain separate and never replace Admin's personal inventory. See `RESTORE_COMMIT_PULL_PROTOCOL.md` for the current contract.

The SQL migrations remain managed by the remote migration manifest; do not replay the pending reconciliation directory as a batch.

---

Historical status note from the original draft (superseded): the staging/commit RPCs were subsequently deployed in Step139 and are called by the Android restore coordinator. See the current status update at the top of this document.

## RPC contract

1. `begin_inventory_restore(restore_id, manifest_sha256, expected_part_count, expected_movement_count, expected_part_chunks, expected_movement_chunks)` creates or resumes a session for `auth.uid()`. Reusing the UUID with different manifest/count metadata is rejected.
2. `stage_inventory_restore_chunk(restore_id, entity_type, chunk_index, payload)` accepts one JSON array of 1–200 records. `entity_type` is `PART` or `MOVEMENT`. The expected final chunk size is derived from the manifest counts. Replaying the same chunk is idempotent; different content at the same index is rejected. Each chunk is also capped at 1 MiB of PostgreSQL JSONB storage size; the client must split earlier if a 200-row chunk exceeds this limit.
3. A future `commit_inventory_restore(restore_id)` must verify exact contiguous chunk indexes, aggregate row counts, validate all records and references, then atomically replace only `auth.uid()` parts and movements, write one `RESTORE_COMMIT` feed marker, record the returned cursor, and mark the session committed. It must not delete staged rows before the client confirms local reconciliation/cleanup.

## Chunk integrity

The server computes PostgreSQL `md5(jsonb::text)` for same-chunk replay consistency, avoiding a fragile requirement for Kotlin to reproduce PostgreSQL JSONB serialization byte-for-byte. This MD5 is not a cryptographic authenticity guarantee. The ZIP manifest and each file/photo still require SHA-256 validation on the Android side. The manifest SHA-256 binds the restore session to the validated ZIP.

## Limits and operational notes

- Maximum 200 records and 1 MiB JSONB storage size per chunk.
- Chunk rows use a composite `(owner_id, restore_id)` foreign key so a chunk cannot be attached to a session owned by another account.
- Part/movement count limits mirror the snapshot design draft.
- Empty datasets have zero chunks; no empty chunk RPC is sent.
- Session/chunk tables have RLS enabled and no direct grants. All access is through `SECURITY DEFINER` functions that derive owner from `auth.uid()` and verify active profile.
- Staging rows are not live inventory and must not be visible to normal pull workers.
- A cleanup/expiry policy for abandoned staging sessions is still required. Do not add a blind TTL delete until Android recovery/journal behavior is implemented.
- Historical note: at the time of this draft the commit RPC was deliberately withheld. Step139 later deployed the chunked atomic commit implementation; do not replay the superseded single-request draft.

## Static review still required

Before deployment, validate SQL syntax and behavior against the actual Supabase project, especially availability of `public.is_active_profile()`, role grants, PostgreSQL function overloads, table/index ownership, and concurrent begin/stage/commit calls. No database execution or migration deployment has occurred.

## Commit and change-feed contract (review gate)

The commit RPC is intentionally still pending. The source review found that the current pull worker dispatches only `PART` and `MOVEMENT`; it treats any other event type as an error. Therefore, adding a `RESTORE_COMMIT` row today would cause pull to retry forever and would not reconcile devices that missed older events.

Before a commit migration is written/deployed, the Android pull protocol must be upgraded together with the SQL contract:

1. Extend `inventory_sync_events.entity_type` to accept `RESTORE_COMMIT` (or use a separately typed control-event table; do not silently overload a PART/MOVEMENT event).
2. Commit inserts exactly one control event for the owner, with payload containing `restore_id`, `manifest_sha256`, and a stable snapshot cursor/version. This event and the inventory replacement must be in the same PostgreSQL transaction.
3. The pull RPC must return the control event in normal event order. The client must not advance its cursor until it has atomically replaced its local owner snapshot from an authoritative snapshot endpoint or staged snapshot response.
4. The pull worker must pause normal event application at the marker, reconcile parts and movements (including deletions), then persist the marker cursor in the same Room transaction as the local replacement. If reconciliation fails, leave the cursor before the marker and retry.
5. Admin global-history pull must not interpret a KTV inventory restore as permission to replace the Admin's own inventory. It may refresh the relevant KTV history view separately, with explicit owner scope.

The commit RPC must lock the session and owner, verify that every expected chunk index exists exactly once and each stored row count matches its manifest-derived expected size, verify aggregate counts, validate UUID uniqueness/code uniqueness/references/photo paths, and only then replace rows for `auth.uid()`. It must set the session to `COMMITTED`, write the commit ledger and one control event, and return the resulting event cursor in that same transaction. Repeating the same restore ID and digest returns the recorded result; a different digest or owner is rejected.

Do not deploy `202611020004_restore_inventory_snapshot.sql` as the final restore implementation: it is the superseded one-request draft and does not consume staging chunks.

## Step 6 — commit and immutable snapshot-read SQL draft

Migration `202611020006_restore_commit_and_snapshot_read.sql` adds:

- `commit_inventory_restore(restore_id)`: owner-authenticated and owner-serialized; verifies session state, exact chunk counts/indexes, per-chunk row counts, part/movement field validity, duplicate IDs/codes and movement-to-restored-part references before deleting anything. It replaces only `auth.uid()` rows, writes one `RESTORE_COMMIT` control event and the commit ledger in the same transaction, then marks the session committed.
- `read_inventory_restore_snapshot_chunk(restore_id, entity_type, chunk_index)`: exposes one chunk only after commit, with manifest digest, immutable commit cursor, total/chunk counts and payload checksum. The staging RPC refuses writes after the session leaves `STAGING`, so committed chunks remain the authoritative immutable snapshot for other devices.
- `inventory_sync_events` now permits `RESTORE_COMMIT`; the event payload carries restore ID, digest, counts and chunk counts. Its `event_id` is the snapshot cursor. Devices must not advance past this marker until local replacement succeeds.
- The superseded `restore_inventory_snapshot(uuid,text,jsonb,jsonb)` all-in-one RPC is dropped to prevent accidental bypass of chunk staging.

Historical deployment gate (superseded by Step139/Step141): the original SQL draft was withheld until the owner pull worker, snapshot staging, atomic Room apply and Admin-global marker policy were implemented. The current source status is documented at the top of this file and in `RESTORE_COMMIT_PULL_PROTOCOL.md`.

## Static review notes / unresolved validation

- SQL has been reviewed against the current `202611020001`, `202611020004`, and `202611020005` drafts. No PostgreSQL server/client is installed in the working environment, so syntax and execution are not database-validated.
- Confirm actual unique constraints and column definitions for `public.parts` and `public.movements` against the live Supabase schema before deployment. The draft follows the column contract used by migration `202611020004`.
- Photo objects must already be uploaded and verified before `commit_inventory_restore`; this SQL validates owner-folder prefixes but cannot independently prove Storage object readability. Android must verify every object before staging/commit.
- Committed staging chunks are intentionally retained. A later acknowledged-device retention/cleanup protocol is required; do not add blind TTL cleanup.
- Chunk staging uses `payload_md5` for idempotent replay consistency, not as a cryptographic trust boundary. The validated ZIP manifest SHA-256 remains the restore identity.

## Canonical restore payload contract (review update 2026-11-02)

The ZIP JSONL format remains camelCase for compatibility with the Android model. Before calling `stage_inventory_restore_chunk`, the Android preflight mapper must convert each row to the SQL RPC's snake_case contract:

- PART: `syncId -> sync_id`, `minQuantity -> min_quantity`, `photoEntry -> photo_path` only after the photo has been uploaded and verified in Storage, `trackingMode -> tracking_mode`, `updatedAt -> updated_at`.
- MOVEMENT: `syncId -> sync_id`, `partName -> part_name`, `beforeQty -> before_qty`, `afterQty -> after_qty`, `machineOrStore -> machine_or_store`, `time -> time`.

`syncId` is a stable UUID and is preserved through backup/restore. Legacy version-2 ZIPs without `syncId` may be imported by generating UUIDs once during preflight and persisting the generated mapping in the restore journal before staging; never regenerate them on retry. `photoEntry` is a ZIP-local path and MUST NOT be passed to SQL as `photo_path`.

## Step 19 — verify downloaded snapshot chunks

Migration `202611020007_verify_restore_snapshot_chunk.sql` adds an owner-scoped RPC for committed sessions. Android sends the JSON array it just parsed; PostgreSQL compares JSONB equality, `md5(jsonb::text)`, and row count against the immutable stored chunk. The reader will not insert the chunk into local snapshot staging until this verification succeeds. MD5 is used as a consistency checksum, not as a cryptographic signature.
