# Step176 Review — Weekly backup duplicate active-journal fail-closed handling

## Scope
Audit multiple `PREPARING` / `READY` / `UPLOADING` journal rows for the same owner. The expected invariant is at most one in-flight weekly backup per owner.

## Changes
- `WeeklyBackupRecovery.recoverOwner` now preflights the complete owner-scoped journal list before any archive cleanup, hash validation, or state transition.
- If more than one active row exists, recovery throws a retryable exception before touching any row or archive. It does not choose the newest row, mark duplicates FAILED, delete files, or create a replacement backupId.
- The active DAO query now has a deterministic `backupId ASC` tie-breaker for equal `createdAt`; this is not used to resolve a detected conflict because recovery fails closed first.
- Transactional `beginWeeklyBackup` continues to block insertion when an active row already exists.

## Verification
- `verify_step176_weekly_backup_multiple_active_journals.py`: 14/14 PASS, including a SQLite model showing conflict detection leaves all rows/archive references unchanged and another owner remains isolated.
- Regression checks are recorded in PROGRESS.md.

## Limits
Static source contracts and SQLite model only. No Room/WorkManager concurrency runtime test, no Android logout/re-login test, no Kotlin compile/APK/device test, no Supabase/TUS/RLS/staging test. No server/data mutation, no scheduling activation, no GitHub push. No Gradle used.
