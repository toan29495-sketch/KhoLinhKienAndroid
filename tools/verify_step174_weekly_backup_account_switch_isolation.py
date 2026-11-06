#!/usr/bin/env python3
"""Step174 contracts for account-switch isolation during weekly-backup reconciliation."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
UP = (ROOT / 'app/src/main/java/vn/kholinhkien/SupabaseWeeklyBackupUploader.kt').read_text()
REC = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupRecovery.kt').read_text()
WORK = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt').read_text()
DAO = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
checks = {
    'worker refuses to start when authenticated owner differs from scheduled owner': 'if (authenticatedId == null || authenticatedId != ownerId) return Result.success()' in WORK,
    'worker rechecks owner after profile validation and recovery': 'if (client.auth.currentUserOrNull()?.id != ownerId) return Result.success()' in WORK,
    'permanent-failure handler checks owner before opening DAO': 'if (client.auth.currentUserOrNull()?.id == ownerId) {\n                    val failedDao' in WORK,
    'permanent-failure handler rechecks owner before journal read and mutation': WORK.count('client.auth.currentUserOrNull()?.id == ownerId') >= 3 and WORK.index('val active = failedDao.activeWeeklyBackup(ownerId)') < WORK.index('failedDao.markWeeklyBackupFailed(ownerId, active.backupId'),
    'identity mismatch does not mark journal failed': 'catch (_: WeeklyBackupIdentityException) {\n            // Session/account changed: stop this owner\'s attempt without touching another account.\n            Result.success()' in WORK,
    'local VERIFIED commit is fenced by cancellation and authenticated owner': 'uploadCoroutineContext.ensureActive()\n            requireActiveOwner(prepared.ownerId)\n            require(dao.markWeeklyBackupVerified' in UP,
    'staged archive deletion has a second owner fence after local commit': UP.index('dao.markWeeklyBackupVerified') < UP.index('requireActiveOwner(prepared.ownerId)\n            check(prepared.archive.delete()'),
    'if account changes before local commit, staged archive is not deleted in uploader finally': 'finally {\n            // The verification copy is disposable' in UP and 'prepared.archive.delete()' not in UP[UP.index('finally {\n            // The verification copy is disposable'):],
    'recovery enumerates only the supplied owner journals': 'weeklyBackupsForOwner(ownerId)' in REC and 'SELECT * FROM weekly_backup_journal WHERE ownerId = :ownerId' in DAO,
    'recovery validates every journal row owner before touching its path': 'check(row.ownerId == ownerId)' in REC and REC.index('check(row.ownerId == ownerId)') < REC.index('val archive = File(row.archivePath)'),
    'recovery confines archive path to that owner directory': 'archive == expected && archive.parentFile == ownerDirectory' in REC,
    'recovery does not scan or delete other owner directories': 'listFiles(' not in REC and 'walkTopDown' not in REC and 'deleteRecursively' not in REC,
    'VERIFIED archive cleanup is owner-fenced and only for matching local journal': '"VERIFIED" -> {' in REC and 'requireActiveOwner(ownerId)' in REC and 'archive.delete()' in REC,
    'Room transitions remain keyed by owner and backup ID': "WHERE ownerId=:ownerId AND backupId=:backupId AND state='UPLOADING'" in DAO,
    'old owner journal remains resumable and is not rewritten by a new owner recovery': '"READY", "UPLOADING" -> {' in REC and 'resumable++ // Preserve exact path + bytes' in REC,
}
for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'RESULT: {sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()): raise SystemExit(1)
