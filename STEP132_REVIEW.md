# Step132 Review – Multi-device conflict safety and Admin global history

Date: 2026-11-03

## Baseline
- Continued from the exact Step131 ZIP after verifying its SHA-256 and ZIP integrity.
- Step131 SHA-256: `c874b1d880f1b5f9f65571b9e7e6eaafb406b8a6e5a5479e9b161629ef93576e`.

## Findings
1. Multi-device mutations already use server-side `expected_server_version` CAS. Concurrent devices using the same stale version cannot both apply a stock change, metadata update, or delete. Operation ID locking and exact payload comparison make retries idempotent. Stock/delete conflicts are deliberately manual-only to avoid manufacturing or dropping movements.
2. Admin global history had two connected integration defects: MainActivity always scheduled the default owner-only pull, never the Admin global feed; and the global feed mapper/DAO required movement.ownerId to equal the signed-in Admin owner, rejecting legitimate KTV movements.
3. Admin's own movement can arrive through both its personal feed and global feed. The personal feed may have blank `owner_name`; the global feed enriches it from the current profile. Strictly comparing `ownerName` as immutable caused an order-dependent false syncId collision.

## Changes
- `SyncWorkScheduler.schedule` now always schedules the signed-in owner's personal pull feed. For Admin it also schedules a separately named global movement feed, with its own cursor. If role is no longer Admin, obsolete global work is cancelled.
- All MainActivity scheduling call sites pass the authenticated profile's Admin role; failed logout restores the correct role-specific schedule.
- `MovementOwnerResolver` is a pure Kotlin helper. Owner-scoped pulls remain bound to the signed-in owner. Admin-global pulls require a valid server-supplied owner UUID and preserve the actual KTV owner ID.
- `SyncPullWorker` routes Admin-global movement events to a dedicated DAO transaction. The transaction keeps the restore fence bound to the signed-in Admin while storing the movement under its actual owner.
- Owner and Admin feed duplicates compare every immutable business-event field, excluding only mutable profile display metadata `ownerName`. A nonblank Admin feed name enriches the row; a blank owner-feed replay cannot erase it. Unique movement `syncId` still prevents duplicate rows, and immutable mismatches fail closed.
- Added `docs/ADMIN_GLOBAL_MOVEMENT_SYNC.md`.
- No SQL migration was added; the existing Admin-only RPC already provides role-gated, redacted movement events with owner ID/name.

## Verification
- `tools/verify_step132_multidevice_conflict_safety.py`: 53/53 PASS. Includes static checks for CAS, idempotency, local FIFO, owner isolation, Admin feed scheduling/routing, restore fencing and deterministic two-device operation order models.
- Actual Kotlin/JVM `MovementOwnerResolver` smoke: 8/8 PASS.
- Updated Step82 movement idempotency verifier: 16/16 PASS, reflecting that `ownerName` is mutable profile metadata while business-event fields remain immutable.
- Step101 delete/stock concurrency: 16/16 PASS.
- Step102 conflict choice guard: 10/10 PASS.
- Step103 Keep Server: 13/13 PASS.
- Step104 Outbox chain guard: 12/12 PASS.
- Step105 crash/ack atomicity: 16/16 PASS.
- Step106 logout/session fence: 13/13 PASS.
- Step130 event commit order: 29/29 PASS.
- Step131 monotonic cursor source: 18/18 PASS; SQLite interleaving smoke PASS.
- Step49–72 sync contract: 24/24 PASS; SQLite schema/atomic replacement smoke PASS.

## Not verified
- No Android/Room compile or instrumentation; no actual WorkManager scheduling/overlap test.
- No live Supabase/PostgreSQL test for Admin role RPC, two-device CAS race, global feed payloads or profile changes.
- No Android emulator/device, APK build/install, SQL/Edge deployment or GitHub push.
- No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte recovery of the original source archive.

## Deployment gate
- Deploy the already-existing Admin feed migrations in order, then test with a real Admin and at least two KTV accounts.
- Verify Admin receives own PART changes through personal feed and all KTV movement rows through global feed, while KTV accounts cannot call the global RPC.
- Test both feed arrival orders for Admin's own movement and verify exactly one local movement row with the expected owner name.
- Run concurrent stock/metadata/delete mutations from two devices against the same version; exactly one stale-version mutation may commit and the other must enter conflict handling.
