# Step99 Review – Fence live inventory mutations against restore (2026-11-03)

## Finding

The owner-wide restore RPCs acquire an exclusive transaction advisory lock using `hashtextextended(auth.uid()::text, 0)`. The ordinary `apply_inventory_operation()` RPC previously did not participate in that lock domain. Row locks and optimistic server-version checks protect individual part rows, but they do not by themselves define a clean owner-wide ordering between a live mutation and a snapshot replacement.

## Change

Added additive migration `202611030011_fence_inventory_mutations_against_restore.sql`, replacing `apply_inventory_operation()` with the Step75 CAS implementation plus one transaction-scoped shared advisory lock:

`pg_advisory_xact_lock_shared(hashtextextended(v_owner::text, 0))`

It is acquired after authentication and active-profile validation, before the idempotency ledger insert/lock and before any inventory mutation. Both staged and legacy restore commit paths already take the matching exclusive transaction lock with key `0` for the same owner.

## Concurrency semantics

- Multiple ordinary inventory operations for the same owner may hold the shared lock concurrently. Different-part mutations are not globally serialized by this fence.
- Existing `SELECT ... FOR UPDATE` and server-version CAS continue to serialize/conflict operations on the same part.
- A restore's exclusive lock waits for all in-flight ordinary mutations to finish.
- Once a restore is waiting/holds the exclusive lock, later ordinary mutations wait until the restore transaction completes.
- Owner IDs are part of the advisory key, so different owners do not block each other.
- Transaction-scoped locks release automatically on commit or rollback.

## Preserved contracts

- `auth.uid()` owner binding and active-profile check.
- Idempotency ledger lock and exact immutable payload comparison.
- PART_UPSERT/PART_DELETE/STOCK_CHANGE semantics and optimistic server-version checks.
- Row-level lock for stock mutations.
- Atomic part + movement + event + sync ledger updates.
- Authenticated-only RPC execute grant.

## Verification

- `tools/verify_step99_restore_inventory_fence.py`: 13/13 PASS (static contract checks + reader/writer interleaving model).
- `tools/verify_sync_step49_72.py`: 24/24 PASS + SQLite smoke test.
- All `tools/verify_step73_*.py` through `tools/verify_step99_*.py` passed in the full regression run.
- ZIP integrity and SHA-256 verified for the Step99 checkpoint.

## Limitations / deployment gates

- No PostgreSQL/Supabase staging execution or actual two-device concurrency/fault-injection test was performed.
- The interleaving test is a deterministic Python reader/writer model, not a PostgreSQL integration test.
- Before deployment, verify that every live inventory write path uses `apply_inventory_operation()` or otherwise participates in the same advisory-lock protocol. Inspect SECURITY DEFINER ownership/grants and restore function definitions in the live Supabase catalog.
- No SQL deployment, APK build, Android device test, Edge Function deployment, GitHub push, or production change.
- Source lineage remains the reconstructed Step49–72 branch, not byte-for-byte restoration of the lost original source.
