# Step133 – Admin global history pagination, RESTORE partition and session audit

Date: 2026-11-03

## Baseline and scope

- Continued from the exact Step132 archive after SHA-256 verification (`a7cc4637464300c4a107df0ba7871d7f2c70f8f5286c4057bd7be22b7623d5f3`) and ZIP integrity validation.
- Verification-only step. No production Kotlin, SQL, or RPC behavior was changed because the inspected contracts were consistent under the source/model checks.
- Added `tools/verify_step133_admin_history_pagination_restore.py`.

## Findings

- The Admin RPC is keyset-paginated by strictly increasing `event_id`, orders ascending, and caps each server page at 500. The Android global pull requests 200 rows, processes at most five pages per worker run, persists cursor after each applied event or redacted `SKIP`, and retries after a full fifth page.
- PART events are redacted to `SKIP` in the global feed so Admin can advance one contiguous global cursor without receiving other KTV inventory payloads.
- Movement rows are stored with the actual KTV `owner_id`; Admin authorization and restore fencing remain tied to the signed-in Admin.
- The Admin personal feed and Admin global feed use separate cursor keys. RESTORE replaces only rows for the restored owner and sets that owner's personal snapshot cursor; it does not clear foreign KTV movement rows or the `admin-global:<adminId>` cursor.
- Sign-out cancels account-specific push/pull work before ending the Supabase session and does not clear Room movement rows. KTV history queries remain owner-scoped; only Admin UI selects `allMovements()`. Admin backup/tools continue receiving only the Admin's own movement feed.
- Deterministic pagination model processed 10,137 sequential events (including redacted PART and RESTORE_COMMIT markers) in bounded 200-row pages and five-page runs: 11 runs, no skipped IDs, no duplicates.

## Important remaining limitation

- `InventoryDao.allMovements()` still exposes a `Flow<List<Movement>>`, and the Admin history UI collects all local movement rows at once. Therefore, this step verifies **server/RPC and worker pagination correctness**, not UI scalability for very large local histories. Large on-device history can still increase Room query, memory and Compose rendering costs. The current DAO query also orders by `time DESC` without an explicit stable secondary key. A later UI-focused step should implement deterministic local keyset paging and a bounded list, while preserving live updates and machine-history behavior. No claim is made that thousands of UI rows have been performance-tested.

## Verification

- `tools/verify_step133_admin_history_pagination_restore.py`: 45/45 PASS.
- 10,137-event deterministic pagination model: 11 bounded runs; no gaps or duplicate event IDs.
- Step132 multi-device/Admin integration: 53/53 PASS.
- Step131 monotonic cursor source checks and SQLite interleaving smoke: PASS.
- Step130 commit-order source/model checks: PASS.
- Step82 movement idempotency: 16/16 PASS.
- Step90 session fencing: 10/10 PASS.
- Step91 null-safe Admin role guard: 10/10 PASS.
- Step96 restore RPC grants: 21/21 PASS.
- Step99 restore inventory fence: 13/13 PASS.
- Step107–123 backup/restore regressions: all PASS (Step107 14/14, 108 11/11, 109 13/13, 110 20/20, 111 14/14, 112 10/10, 113 14/14, 114 11/11, 115 10/10, 116 11/11, 117 11/11, 118 12/12, 119 12/12, 120 13/13, 121 15/15, 122 15/15, 123 20/20).
- Step124–129 regressions: all PASS (124 9/9, 125 20/20, 126 17/17, 127 18/18, 128 19/19, 129 18/18).
- Step49–72: 24/24 plus SQLite schema/atomic replacement smoke PASS.
- `MovementOwnerResolver` actual Kotlin/JVM smoke: 8/8 PASS.
- Python `compileall`: PASS.

## Verification boundaries

- The 10,137-event exercise is a deterministic model, not a live PostgreSQL query/concurrency or WorkManager test.
- No PostgreSQL/Supabase staging, Admin/KTV account-role transition, live multi-device, Room instrumentation, Android emulator/device, APK build/install, SQL/Edge deployment, or GitHub push was performed.
- No Gradle was run.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte recovery of the original source archive.
