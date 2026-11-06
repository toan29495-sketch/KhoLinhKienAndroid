# Step131 Review – Monotonic local sync cursor under overlapping workers

Date: 2026-11-03

## Baseline
- Continued from exact Step130 ZIP after SHA-256 verification and ZIP integrity check.
- Step130 archive SHA-256: `58d02a770eedede6f34de8d637dd8b8cc4f02bdbbf4547b264236c14e6eb20e1`.

## Finding
- `SyncWorkScheduler` uses separate unique names for one-time and periodic pull work. WorkManager's uniqueness is per name, so a one-time pull and its periodic counterpart can overlap. The owner feed and Admin global feed also have separate cursor keys.
- `SyncPullWorker` reads a cursor once at startup and persists after each applied event. `savePulledCursorIfUnlocked` previously used `INSERT OR REPLACE` semantics through `saveSyncCursor`.
- A slower worker can therefore finish an older page after a faster worker has advanced the same feed cursor. The stale write can move `lastEventId` backwards. This is not normally a permanent data-loss condition because PART version/tombstone and MOVEMENT sync-ID guards make replay safe, but it causes avoidable reprocessing and weakens the durable cursor invariant.

## Changes
- Added `updateSyncCursorMaximum`, a Room `UPDATE` that writes `MAX(existing, incoming)` using a `CASE` expression.
- Added `insertSyncCursorIfAbsent` using `OnConflictStrategy.IGNORE` for the first cursor row.
- `savePulledCursorIfUnlocked` now performs active-restore check, cursor-key ownership validation, monotonic update, and insert-if-absent in one Room transaction.
- Accepted cursor keys are exactly the owner key or `admin-global:<ownerId>`; negative event IDs are rejected.
- Used UPDATE + INSERT IGNORE rather than SQLite UPSERT syntax for compatibility with older Android SQLite versions.
- Kept `saveSyncCursor(REPLACE)` only for the authoritative restore snapshot path. RESTORE intentionally sets its exact snapshot cursor atomically with inventory replacement and COMPLETE journal state.
- No SQL migration, change-feed format, Outbox, inventory data, or scheduling policy changed.

## Verification
- `tools/verify_step131_monotonic_cursor_concurrency.py`: 18/18 source-contract checks PASS.
- SQLite smoke: stale worker advancing 200 after faster worker 400 leaves cursor at 400; duplicate advancement is idempotent; owner and Admin-global keys remain isolated; authoritative RESTORE can still set the exact snapshot cursor.
- Step130 29/29; Step129 18/18; Step128 19/19; Step127 18/18; Step126 17/17; Step125 20/20; Step124 9/9; Step123 20/20; Step122 15/15; Step121 15/15; Step120 13/13; Step113 14/14; Step90 10/10; Step85 13/13; Step49–72 24/24 + SQLite schema/atomic replacement smoke PASS.
- Python tooling compileall PASS; archive ZIP integrity and SHA-256 checked.

## Not verified
- The Room DAO Kotlin source was not compiled against Android/Room because Android SDK/toolchain is unavailable and Gradle is intentionally not used.
- SQLite smoke reproduces the DAO's SQL update/insert algorithm but is not an Android SQLite/Room instrumentation test.
- No real two-worker WorkManager race, process-kill, multi-device pull, Admin/KTV live session, PostgreSQL/Supabase feed or restore concurrency test.
- No APK build/install, emulator/physical-device test, SQL/Edge deployment, or GitHub push.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte recovery of the original source archive.

## Deployment/test gate
- Before release, run Room instrumentation with two workers deliberately completing cursor writes out of order and verify the cursor never decreases.
- Exercise both normal owner and Admin-global cursor keys, including overlap of one-time and periodic work.
- Test restore snapshot cursor replacement while a pull is in flight; verify snapshot rows, journal COMPLETE state and exact snapshot cursor remain atomic.
