# Step130 Review – Change-feed cursor commit-order safety

Date: 2026-11-03

## Baseline
- Continued from exact Step129 source ZIP after SHA-256 and ZIP integrity verification.
- Step129 archive SHA-256: `38ddae87c63ba97a44342ac24f26d7441605a263d425978f5ae1533da2ba0c7c`.

## Findings
- `inventory_sync_events.event_id` was a global `bigserial` key used by both owner and Admin keyset cursors.
- PostgreSQL sequences are non-transactional and allocate values before transaction commit. A lower ID can remain uncommitted while a higher ID commits and becomes visible. A client can then advance beyond the lower ID and never see it.
- The Admin feed makes this a cross-owner/global correctness issue; a per-owner advisory lock would not protect its global cursor.
- `SyncPullWorker` also stopped after five pages (up to 1,000 events) with `Result.success()` even when the fifth page was full. The periodic worker would eventually resume, but large backlogs could be unnecessarily delayed.

## Changes
- Added `supabase/migrations/202611030015_serialize_sync_event_commit_order.sql`.
- Removed the `event_id` default while retaining its owned sequence.
- Added a SECURITY DEFINER `BEFORE INSERT` trigger with a pinned `pg_catalog` search path. It takes one transaction-scoped advisory lock before assigning `event_id` from the owned sequence. The lock is held until transaction end, ensuring a later event writer cannot obtain a larger event ID and commit before the earlier writer's outcome is known.
- The trigger is installed at the table boundary, so normal mutation RPCs, RESTORE_COMMIT and future event writers receive the same protection.
- Global lock is intentional because the Admin client stores one cursor across all owners. It may reduce concurrent feed-publication throughput; staging load tests are mandatory before production rollout.
- Updated `SyncPullWorker` to return `Result.retry()` when the last allowed page is full. Cursor is still persisted event-by-event only after the event has been applied/validated. Replays remain protected by existing part version/tombstone and movement sync-ID checks.
- Added the migration contract document and focused verifier.

## Verification
- `tools/verify_step130_sync_cursor_commit_order.py`: 29/29 PASS.
- Deterministic model: demonstrates the pre-fix out-of-order commit skip and serialized assignment ordering.
- Step129 and earlier focused regressions are recorded in the Step130 completion report.

## Not verified
- No PostgreSQL executable/staging database was available. The migration was not parsed or executed by PostgreSQL, and the advisory lock behavior was not concurrency-tested against a live server.
- No live Supabase RPC pagination, KTV multi-device or Admin global-feed test.
- No Android SDK/emulator/device or Android framework compile; no APK build/install; no GitHub push. No Gradle used.
- Source lineage is reconstructed from preserved checkpoints rather than byte-for-byte recovery of the original source archive.

## Deployment gate
Before production: apply migration in staging; inspect trigger/default/sequence ownership; run a two-session concurrency test where writer A pauses after inserting an event while writer B attempts another insert; prove B blocks until A commits/rolls back; verify both owner and Admin feeds observe every committed event; benchmark lock wait and throughput under realistic concurrency.
