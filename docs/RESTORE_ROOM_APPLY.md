# Room snapshot application — Step 17

The local snapshot applier is intentionally separate from ZIP parsing and cloud transport.

## Preconditions
- The authenticated Supabase user ID equals the inventory owner ID.
- The restore journal belongs to that owner and is `SNAPSHOT_DOWNLOADED`.
- The snapshot cursor and expected row counts match the journal.
- No local outbox operation is pending, failed, or in flight. If there are unsent operations, the transaction refuses to replace the live inventory.

## Transaction behavior
1. Read staged chunk indexes and verify contiguous indexes and exact row counts.
2. Validate each part and movement chunk before any destructive statement. Validate UUIDs, unique part IDs/codes, unique movement IDs, quantities, tracking mode, owner-scoped Storage paths, and movement-to-part references.
3. Delete only `parts` and `movements` owned by the current account.
4. Insert snapshot entities in chunks of at most 200 rows.
5. Save the owner sync cursor and mark the restore journal `COMPLETE` in the same Room transaction.

If any step throws, Room rolls back the delete, inserts, cursor update, and journal status update together. Other owners' inventory/history are not touched. Snapshot photo paths remain Storage object paths; this transaction does not copy image bytes to permanent local storage.

## Not yet release-ready
- The existing MainActivity restore entry point still calls the legacy `ZipRestore.restore`; the new cloud pipeline is not wired to the UI.
- The snapshot reader checks that `payload_md5` is a 32-character hex value but does not independently recompute PostgreSQL's JSONB text MD5.
- Android pull handling for the `RESTORE_COMMIT` control event and the admin-global pull RPC migration are outstanding.
- No Gradle/Room compile, database test, Supabase test, APK build, or device test has been performed for this step.


Step 19 adds `verify_inventory_restore_snapshot_chunk`: Android sends its parsed JSON array back to an owner-scoped RPC; PostgreSQL compares JSONB equality, `md5(jsonb::text)`, and row count against the immutable committed chunk before local staging. This resolves serializer byte mismatch without claiming MD5 is a cryptographic signature. Migration 007 still requires execution in a test Supabase project.
