# Step114 – Restore journal and staging isolation across KTV accounts

Date: 2026-11-03

## Scope
Audit the same-device account-switch path: KTV A signs out while a restore journal/staging workspace exists, KTV B signs in, and later KTV A signs back in.

## Findings and changes
- Restore journal lookups previously used `restoreId` alone, with owner validation performed by callers afterward. Changed the DAO contract to require both `ownerId` and `restoreId` in the SQL predicate.
- Updated every restore phase and lifecycle lookup to use the owner-scoped DAO method: ZIP import/retry, resume coordinator, cloud payload preparation, chunk staging, commit, snapshot download/apply, cleanup, and abort.
- The transactional Room snapshot apply now reads its journal with both owner and restore ID in the query, before any destructive owner-scoped inventory operation.
- Hardened staging directory resolution: the canonical staging directory must be the exact direct child `filesDir/restore_staging/<restoreId>`, not merely any nested path below the root.
- Existing UI query remains `restoreJournals(profile.id)` and the resume callback independently matches `restoreId` plus the current profile owner before invoking the coordinator.
- Staged row reads, counts, clears and cloud photo paths remain owner-scoped.

## Verification
- `tools/verify_step114_restore_account_isolation.py`: 11/11 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 PASS plus SQLite schema/atomic replacement smoke PASS.
- All 42 `tools/verify_step*.py` scripts passed.
- `python3 -m compileall -q tools` passed.

## Limitations / not performed
- No Android compilation, APK build/install, or device test.
- No actual Android account-switch, process-kill, or lifecycle fault-injection test.
- No live Supabase/PostgreSQL staging test and no SQL/Edge Function deployment.
- No GitHub push.
- The source lineage is reconstructed from preserved checkpoints and is not a byte-for-byte restoration of the lost original source.

## Safety
No live inventory, movement history, Outbox, sync cursor, user photo, or cloud object was intentionally modified by these source-level changes. Existing installed APK remains untouched.
