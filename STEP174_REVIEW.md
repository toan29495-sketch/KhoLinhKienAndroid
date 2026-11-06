# Step174 Review — Weekly backup account-switch isolation

## Scope
Close the remaining local account-switch window in the weekly-backup Worker: a permanent backup error can be raised for owner A, then the shared Supabase session can switch to owner B before the Worker catch handler records the terminal state.

## Change
- The `PermanentWeeklyBackupException` handler now checks that the authenticated Supabase user still equals the scheduled `ownerId` before opening the DAO, checks again before reading the active journal, and checks once more immediately before marking that journal FAILED.
- If the session has changed, the handler leaves the previous owner's journal untouched. The worker remains keyed by the original owner ID; it does not adopt the new session's owner.
- Existing uploader fences remain in place before the local `VERIFIED` transition and again before deleting the staged ZIP. If account identity changes between those operations, the journal/ZIP is preserved for the original owner to reconcile on a later session.
- Recovery remains owner-scoped: it reads only `weeklyBackupsForOwner(ownerId)`, validates the row owner, confines the archive path to that owner's staging directory, and never scans/deletes other owner directories.
- No changes to Room schema, archive bytes/path, TUS fingerprint, Supabase candidate SQL, or scheduling activation.

## Verification
- Added `verify_step174_weekly_backup_account_switch_isolation.py` with explicit contracts for worker start, catch-handler owner fences, uploader commit/cleanup ordering, and owner-scoped recovery.
- Static source verification only. This does not prove atomicity against an actual concurrent session switch; runtime testing is still required when an Android build/device test becomes available.

## Not verified / not done
- No live Supabase RPC/Storage, auth account-switch, WorkManager, force-stop/reboot, or concurrency test.
- No Kotlin/Android compilation, APK build/install, or physical-device test.
- No backend migration execution, remote object operation, account/profile/inventory/history mutation, schedule activation, or GitHub push.
- No Gradle used, per explicit instruction.

## Safety
- Room remains schema v14.
- No journal from another owner is queried or mutated by this change.
- No staging archive is deleted on identity mismatch; cleanup remains deferred to a later recovery under its original owner session.
