# Step105 – Crash-safe Outbox acknowledgement after server commit (2026-11-03)

## Finding
The server-side idempotency design already supports the critical lost-response/crash case: `apply_inventory_operation()` locks the ledger row by `operation_id`, rejects reuse with a changed payload, and returns the exact stored result when `processed_at` is set. Stock quantity, movement insertion, sync event publication, and the ledger result are committed in the same PostgreSQL transaction. The Android sender also preserves the original operation ID/payload when retrying a transient failure or recovering an expired claim.

A local atomicity gap remained in `InventoryDao.completeOutboxWithServerVersion()`: it rebased the local server version and later queued operations, then returned `false` if the Outbox row could not be marked `SYNCED`. A false return does not itself abort a Room `@Transaction`, so local version/rebase changes could commit without the acknowledgement. It also did not validate that the target row still belonged to the expected owner/entity and was the exact `IN_FLIGHT` operation being completed.

## Change
- Validate the Outbox row exists and matches `ownerId`, `entityId`, operation type (`PART_UPSERT`/`STOCK_CHANGE`) and `IN_FLIGHT` status before local completion.
- Require a positive server version.
- Keep server-version update and FIFO successor rebase inside the existing Room transaction.
- Change the final `markOutboxSynced()` result to a checked invariant. If it does not update exactly one row, throw so Room rolls back the version/rebase changes as well.
- No operation ID or immutable payload is regenerated or changed for a retry. No SQL migration was needed.

## Crash/retry behavior
1. If the device dies before the server commits, retrying the same operation ID/payload executes it once.
2. If the server commits but the response is lost, the local row remains retryable/claim-recoverable; retry returns the cached result and does not create another movement.
3. If the device dies during the Room acknowledgement transaction, Room commits all local version/rebase/SYNCED changes or none of them.
4. If local completion validation fails, it throws and the transaction rolls back; the sender does not silently accept a partial local acknowledgement.

## Verification
- `tools/verify_step105_outbox_crash_ack_atomicity.py`: 16/16 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 plus SQLite schema/atomic replacement smoke PASS.
- All existing `tools/verify_step*.py` verifiers Step73–104 passed; Step105 verifier passed.
- ZIP integrity and SHA-256 checked.

## Not performed / limitations
- These are source/static and SQLite smoke checks, not an Android process-kill/network fault-injection test or live PostgreSQL/Supabase test.
- No SQL deployment, APK build, device test, Edge Function deployment, GitHub push, or production change.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source.
