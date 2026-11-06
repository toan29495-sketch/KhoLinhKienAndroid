# Step134 – Bounded Admin history UI pagination

Date: 2026-11-03

## Baseline and scope

- Continued from the Step132-derived Step133 source tree. Step133 ZIP had been SHA-256 and ZIP-integrity verified before extraction.
- Step133 identified that the server/worker feed was paginated but the Admin UI still called `allMovements(): Flow<List<Movement>>`, materializing every local movement row. Step134 closes that UI-side gap.
- No database schema or SQL/RPC changes. Room database version remains unchanged because this step adds DAO queries only.

## Changes

- Removed the unbounded `allMovements()` DAO query.
- Added `latestAdminMovementPage(limit)` and `olderAdminMovementPage(beforeTime, beforeId, limit)` with a stable descending `(time, id)` keyset. The local Room primary key is the deterministic tie-breaker when multiple movements share the same timestamp.
- Admin history initially loads at most 200 rows. A `Tải thêm 200 giao dịch` action appends the next page and deduplicates by `syncId`. The page request runs on `Dispatchers.IO`, prevents overlapping loads, and preserves cancellation propagation.
- Admin history has an explicit refresh action and refreshes again when the user opens the History tab, so newly synced events are incorporated without keeping an unbounded Room Flow active.
- KTV history remains owner-scoped through `movementsForOwner(profile.id)`.
- Machine QR history now uses a dedicated Room query filtered by machine/store code and export type. Admin can query all owners; KTV remains restricted to its own owner ID. Results use deterministic `(time DESC, id DESC)` order and no longer depend on which Admin history pages happen to be loaded.
- Admin backup/tools still receive `ownMovements`, not the paged system-wide history.

## Verification

- `tools/verify_step134_admin_history_local_paging.py`: 29/29 PASS.
- SQLite smoke: 1,250 rows with tied timestamps loaded in seven pages of 200; no missing or duplicate sync IDs; stable descending ordering verified.
- SQLite machine-history smoke: Admin received all matching owners (89 rows in fixture); KTV A received only its own matching rows (30 rows).
- Step133 pagination/RESTORE/session audit: 45/45 PASS.
- Step132 multi-device/Admin integration: 53/53 PASS.
- Step131 monotonic cursor + SQLite interleaving smoke: PASS.
- Step130 commit-order source/model: PASS.
- Step82 movement idempotency: 16/16; Step90 session fencing: 10/10; Step91 Admin role NULL guard: 10/10; Step96 RPC grants: 21/21; Step99 restore fence: 13/13.
- Step124–129 regressions: all PASS (124 9/9, 125 20/20, 126 17/17, 127 18/18, 128 19/19, 129 18/18).
- Step49–72: 24/24 plus SQLite schema/atomic replacement smoke PASS.
- `MovementOwnerResolver` Kotlin/JVM smoke: 8/8 PASS. Python `compileall`: PASS.

## Behavior and limitations

- Admin history is no longer loaded in full on first display. It grows only as the user requests more pages; a user can still intentionally load the complete history over multiple pages.
- Refresh is explicit while already on the History tab; opening the tab triggers a fresh first-page read. The UI does not continuously observe every global history insert while remaining open.
- Machine-specific history still returns all matching rows for that one machine, because the product requirement is to show its complete history. This is narrower than loading all system movements, but an unusually high-volume machine may warrant its own paging in a later step.
- SQLite query behavior was smoke-tested against equivalent SQL. Android Room annotation processing, Compose rendering, scrolling, WorkManager-driven refresh and real device performance were not run.

## Verification boundaries

- No Android SDK/emulator, Room instrumentation or physical device test.
- No live PostgreSQL/Supabase staging, SQL/Edge deployment, APK build/install, or GitHub push.
- No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte recovery of the original source archive.
