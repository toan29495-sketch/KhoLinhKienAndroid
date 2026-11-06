# Step115 – Per-account ZIP extraction temp isolation

Date: 2026-11-03
Baseline: Step114 checkpoint `KhoLinhKienAndroid-step114-restore-account-isolation-20261103.zip`.

## Scope
Ensure one KTV cannot have their transient ZIP extraction workspace reclaimed by another KTV's stale-temp cleanup on a shared device.

## Finding
Step114 owner-scoped durable restore journals, staged Room rows, durable staging directory validation, and cloud photo paths. However, the short-lived ZIP extraction directory still used a global `zip_restore_<UUID>` namespace, and every restore swept all stale directories with that prefix. This left a cross-account cleanup interaction for abandoned temporary workspaces.

## Changes
- Validate the authenticated owner ID format before creating a ZIP temp workspace.
- New temp directories are named `zip_restore_<ownerId>_<randomUUID>`.
- `cleanupStaleTemporaryStages` now requires the active owner ID and selects only that owner's new-format directories, plus legacy unscoped UUID temp directories from older versions.
- Retain canonical direct-child checks and the 24-hour stale threshold. No other KTV's new-format temp directory is selected.
- Retain immediate `finally` cleanup, low-storage preflight, archive limits, checksum validation, durable restore journal/staging behavior, and owner-scoped database operations.

## Verification
- `tools/verify_step115_zip_temp_account_isolation.py`: 10/10 PASS.
- `tools/verify_step112_restore_temp_cleanup.py`: 10/10 PASS.
- `tools/verify_step113_restore_cancellation_resume.py`: 14/14 PASS.
- `tools/verify_step114_restore_account_isolation.py`: 11/11 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 PASS + SQLite schema/atomic replacement smoke PASS.
- `python3 -m compileall -q tools`: PASS.
- Attempted all `verify_step*.py` in one loop; command time limit was reached while running Step92. This is not recorded as a full-suite pass.

## Limitations
- Static/source verification only. No Android compilation, APK build/install, physical device, account-switch, process-kill, or low-storage fault-injection test.
- No live Supabase/PostgreSQL staging, SQL or Edge Function deployment, or GitHub push.
- Legacy unscoped temporary folders are still reclaimed only after the existing 24-hour age threshold because their original owner cannot be determined from the old directory name.
- No live inventory, movement history, Outbox, sync cursor, user photo, or cloud object was intentionally modified.
- Source lineage is reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source.
