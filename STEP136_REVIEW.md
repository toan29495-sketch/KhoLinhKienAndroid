# Step136 – Stable Admin history freshness watermark

Date: 2026-11-03

## Baseline and scope

- Continued from the exact Step135 ZIP after SHA-256 verification (`d268e62ddec5a37d011a3185754f6e06701a636f1554e56491bf0dacbc65836e`) and successful ZIP integrity validation.
- No Room schema version, SQL migration, Supabase RPC, Edge Function, Storage policy, sync payload, or restore transaction changes.
- Changes are limited to one scalar DAO snapshot query, Admin history UI state, and source-level regression coverage.

## Finding and fix

- Step135 compared the live `MAX(movements.id)` with the maximum ID among the currently displayed time-ordered page. These are different orderings: a delayed sync can have a newer local insertion ID but an old event timestamp, placing it outside the first 200 rows. After refresh, the page-derived maximum could therefore remain lower than the live watermark and keep the stale-history warning visible indefinitely.
- Added `currentMovementMaxId()` as a one-shot Room scalar query.
- Admin refresh now snapshots the insertion watermark BEFORE querying the first page and stores it separately from visible page rows. The live Flow is compared to this snapshot. New rows inserted after the snapshot—including delayed rows with old event timestamps—cause a refresh warning. A delayed row already present before refresh no longer causes a permanent warning merely because it sorts beyond page 1.
- The snapshot is cleared on role downgrade. Existing owner-scoped KTV history, bounded Admin keyset paging, manual refresh, RESTORE owner isolation, and role downgrade navigation guard remain unchanged.

## Verification

- Step136 verifier: 14/14 PASS. Includes a SQLite reproduction of the Step135 false-positive and tests for inserts both before and after the watermark snapshot.
- Updated Step135 verifier: 15/15 PASS.
- Step134 local paging: 29/29 PASS; 1,250-row / 7-page SQLite smoke, no gaps or duplicates; machine-history owner isolation PASS.
- Step133 Admin pagination/RESTORE: 45/45 PASS; 10,137-event model drains without gaps.
- Step132 multi-device integration: 53/53 PASS.
- Step131 monotonic cursor interleaving; Step130 commit-order model 29/29; Step129 photo delete contract 18/18; Step128 photo lifecycle 19/19; Step127 retry idempotency 18/18; Step126 photo readback 17/17; Step125 restore history 20/20; Step124 cancellation 9/9; Step99 restore fence 13/13; Step96 RPC grants 21/21; Step91 role guard 10/10; Step90 session fencing 10/10; Step82 movement idempotency 16/16; Step49–72 sync 24/24 + SQLite schema/atomic replacement smoke all PASS.
- Python tooling compileall: PASS.

## Verification boundaries

- Source/static checks and SQLite-equivalent query smoke only. No Android Room annotation processing, Compose runtime/rendering, WorkManager execution, emulator or physical-device test.
- No live Supabase/PostgreSQL staging, cross-device sync, SQL/Edge deployment, APK build/install, or GitHub push.
- No Gradle used, in accordance with the project instruction.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte recovery of the original source archive.
