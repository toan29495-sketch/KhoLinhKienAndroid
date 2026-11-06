# Admin personal inventory and global movement synchronization

Date: 2026-11-03

## Required behavior

The Admin account is also a normal inventory owner/KTV. It therefore needs two independent pull feeds:

1. `pull_inventory_changes` for the Admin's own PART and MOVEMENT rows, using the cursor key `<adminOwnerId>`.
2. `pull_admin_movement_changes` for system-wide movement history, using `admin-global:<adminOwnerId>`. The server redacts PART events to `SKIP` markers and includes the actual movement `owner_id` and current `owner_name`.

These feeds must not replace one another. The Admin personal inventory remains owner-scoped; the global feed adds history only.

## Step132 findings and fixes

- All app scheduling call sites previously used the default `isAdmin=false`. The global feed RPC existed, but the Admin global worker was not actually scheduled.
- The worker's movement mapper required `payload.owner_id == signedInOwnerId`, which rejected legitimate KTV movements returned by the Admin-only global feed.
- A single Admin device receives its own movement from both feeds. The owner feed can omit/enclose an empty `owner_name`, while the global feed enriches it from `profiles`. Treating `owner_name` as immutable caused the same movement `syncId` to be reported as a payload collision depending on which feed arrived first.

Changes:

- Every account always schedules its personal owner feed.
- Admin additionally schedules a separately named one-time and periodic global movement feed. Role downgrade cancels those global jobs.
- The global-feed mapper requires a valid UUID `owner_id` from the server and stores the movement under that actual KTV owner, never under the Admin's owner ID.
- A dedicated DAO transaction uses the Admin's restore fence but stores the actual movement owner. `syncId` remains globally unique and exact business-event fields must match on replay.
- `owner_name` is treated as mutable profile display metadata only. A nonblank global-feed value enriches an existing row; a later owner-feed copy with a blank name cannot erase it. Differences in code, part name, type, amount, before/after quantity, reason, machine/store, timestamp, owner ID or sync ID still fail closed.
- Admin's own movement can arrive via both feeds and is deduplicated by the globally unique movement `syncId`.

## Multi-device conflict contract retained

- Every mutation uses `expected_server_version` compare-and-set.
- Stock update, movement insert, version increment, change-feed events and idempotency ledger result remain in one server transaction.
- Two devices using the same stale version cannot both commit. The losing stock/delete operation is blocked for manual review; it is not silently rebased into a different stock movement.
- A retry with the same operation ID and exact payload returns the cached result. Reusing the operation ID with a changed payload is rejected.
- Local Outbox remains FIFO with one in-flight operation per owner; successor version rebasing and local acknowledgement remain atomic.

## Verification boundaries

`tools/verify_step132_multidevice_conflict_safety.py` is a source-contract and deterministic model test, not a PostgreSQL concurrency test. `tools/MovementOwnerResolverSmoke.kt` compiles and executes the pure Kotlin owner resolver on JVM. Android Room/WorkManager, live Supabase authorization/feed behavior, multi-device races, and actual profile-role transitions still require staging/device verification.
