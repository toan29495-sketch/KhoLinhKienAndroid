#!/usr/bin/env python3
"""Step179: audit duplicate weekly-backup triggers and durable deduplication."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt'
DATA = ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt'
BACKUP = ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt'
work, data, backup = WORK.read_text(), DATA.read_text(), BACKUP.read_text()
# Count production call sites, excluding declarations and test/verifier files.
prod = list((ROOT / 'app/src/main').rglob('*.kt'))
callers = []
for path in prod:
    source = path.read_text(errors='replace')
    if 'prepareWeeklyCloudBackup(' in source and path.name != 'BackupManager.kt':
        callers.append(path.relative_to(ROOT).as_posix())
checks = {
    'periodic work uses owner-scoped unique name': 'inventory-weekly-backup-$ownerId' in work,
    'periodic duplicate schedule updates the existing owner request': 'ExistingPeriodicWorkPolicy.UPDATE' in work,
    'worker input carries exact owner id': 'KEY_OWNER_ID to ownerId' in work,
    'worker validates owner before and after lock wait': work.count('currentUserOrNull()?.id') >= 3 and 'return@withOwner Result.success()' in work,
    'owner-scoped in-process lock surrounds workflow': 'WeeklyBackupExecutionLock.withOwner(ownerId)' in work and 'executeOwnerBackup(ownerId, client)' in work,
    'recovery occurs before active journal selection': work.index('WeeklyBackupRecovery.recoverOwner') < work.index('dao.activeWeeklyBackup(ownerId)'),
    'worker reuses active backup identity': 'WeeklyBackupLifecycle.backupIdForAttempt(' in work and 'return active.backupId' in (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupLifecycle.kt').read_text(),
    'Room transaction blocks second active journal': '@Transaction\n    suspend fun beginWeeklyBackup' in data and 'check(activeWeeklyBackup(journal.ownerId) == null)' in data,
    'existing backupId returns its original journal': 'if (existing != null)' in data and 'return existing' in data,
    'archive preparation is reached only through audited worker call site': callers == ['app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt'],
    'backup preparation commits journal before snapshot creation': backup.index('dao.beginWeeklyBackup(') < backup.index('dao.captureAutomaticBackupSnapshot(ownerId)'),
    'scheduler API is separate from the worker execution body': 'fun schedule(context: Context, ownerId: String)' in work and 'WeeklyBackupWorkScheduler.schedule(' not in work,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
print('Production prepare call sites:', ', '.join(callers) if callers else '(none)')
passed = sum(checks.values()); total = len(checks)
print(f'RESULT: {passed}/{total} PASS')
if passed != total:
    raise SystemExit(1)
