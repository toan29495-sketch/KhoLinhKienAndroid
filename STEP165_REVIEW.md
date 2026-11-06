# Step165 Review — Weekly backup identity and lifecycle safety

## Scope
- Repair the shared identity-check visibility used by weekly backup recovery and WorkManager worker.
- Add owner-specific periodic-work cancellation to explicit logout paths.
- Keep automatic weekly scheduling disabled; no backend activation or enqueue occurs.

## Changes
- Added `WeeklyBackupIdentity.kt` with top-level `internal` helpers:
  - `isCanonicalOwnerId`: validates UUID shape and canonical UUID representation.
  - `requireActiveOwner`: checks the current Supabase authenticated user ID equals the requested owner.
- `WeeklyBackupRecovery.kt` and `WeeklyBackupWorker.kt` now resolve these shared helpers rather than depending on inaccessible private members of other classes.
- `MainActivity.kt` cancels only `inventory-weekly-backup-<ownerId>` before explicit logout, alongside existing owner-scoped sync cancellation. Session-restore sign-out also cancels when a current profile owner is available.
- Admin→KTV role downgrade intentionally does not cancel the account's own weekly backup: Admin remains the owner of their own KTV warehouse.
- Scheduler remains opt-in and is not wired to login/startup/foreground refresh. No periodic work was enqueued.

## Verification
- `verify_step165_weekly_backup_identity_lifecycle.py`: 12/12 PASS.
- Step163 recovery verifier: 19/19 PASS.
- Step164 worker verifier: 22/22 PASS.
- Regression Step140–165: 428/428 PASS across 26 verifier outputs; Step141 SQLite smoke PASS.
- `python3 -m compileall -q tools`: PASS.
- One combined verifier loop hit the command time limit while running Step161; Step161–165 were rerun individually and all passed. This was a command timeout, not a verifier failure.

## Not verified / not done
- No Kotlin/Android type-check or Android compile; no APK build/install or phone test.
- No WorkManager runtime/process-death/network/account-switch device test.
- No live Supabase SQL, bucket, RLS, RPC, TUS upload/download, or staging test.
- No backend candidate applied, no scheduler activation, no Storage object deletion, no user/profile/inventory mutation, no GitHub push.
- No Gradle used.

## Safety
- No Room schema change (still v14).
- No Supabase project/server data changes.
- No service-role key requested or used.
