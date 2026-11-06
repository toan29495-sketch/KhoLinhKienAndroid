# Step175 Review — Weekly backup same-owner re-login recovery

## Scope
Audit the recovery behavior when the original KTV logs out, later logs back into the same account, and a new Worker resumes the durable weekly-backup journal. Verify that another account cannot adopt or clean up that journal.

## Findings
- Worker calls `WeeklyBackupRecovery.recoverOwner(ownerId)` before selecting the active journal, and its input owner must match the authenticated Supabase session.
- `PREPARING`: recovery removes only the incomplete ZIP in that owner's canonical staging directory; the PREPARING journal remains. The next preparation reuses the same backupId/createdAt and safely reconstructs bytes because upload was never permitted before READY.
- `READY` / `UPLOADING`: recovery validates file existence, size and SHA-256. Valid bytes/path are preserved for TUS resume. Invalid/missing bytes transition that owner+backupId to FAILED and are retained for diagnosis; no new bytes are silently written under the old backupId.
- `VERIFIED`: recovery performs local archive cleanup only. It does not create another upload.
- `FAILED` / `ABANDONED`: retained as audit history and excluded from active selection. A later backup can use a new backupId.
- Unknown state fails closed. DAO queries and state transitions remain keyed by ownerId; archive paths are confined to the canonical owner directory.
- A second owner can only select its own active journal; the SQLite smoke confirms owner A cannot select owner B's active upload and vice versa.

## Verification
- Added `tools/verify_step175_weekly_backup_relogin_recovery.py`: 18/18 PASS, including a SQLite state-matrix smoke for PREPARING/READY/UPLOADING/VERIFIED/FAILED/ABANDONED and two owners.
- Static source contracts only. SQLite smoke exercises the active-state predicate, not Room/WorkManager runtime behavior.

## Not verified / not done
- No actual logout/re-login or account-switch test on Android; no WorkManager process-death/reboot execution.
- No Kotlin compile, APK build/install, physical-device test, Supabase Auth/RPC/Storage/TUS/RLS or staging test.
- No backend migration, remote object operation, account/profile/inventory/history mutation, schedule activation, or GitHub push.
- No Room schema change. No Gradle used, per explicit instruction.
