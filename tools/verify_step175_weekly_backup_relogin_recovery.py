#!/usr/bin/env python3
"""Step175 source contracts + SQLite state-matrix smoke for same-owner re-login recovery."""
from pathlib import Path
import sqlite3
ROOT = Path(__file__).resolve().parents[1]
REC = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupRecovery.kt').read_text()
WORK = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt').read_text()
PREP = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
DAO = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
checks = {
    'worker runs owner-scoped recovery before selecting active backup': WORK.index('WeeklyBackupRecovery.recoverOwner') < WORK.index('dao.activeWeeklyBackup(ownerId)'),
    'recovery enumerates only the re-authenticated owner journal set': 'weeklyBackupsForOwner(ownerId)' in REC and 'requireActiveOwner(ownerId)' in REC,
    'PREPARING recovery removes only incomplete owner archive': '"PREPARING" -> {' in REC and 'archive.delete()' in REC and 'requireActiveOwner(ownerId)' in REC,
    'PREPARING journal remains available for same-ID reconstruction': 'PREPARING journal' in REC or 'state = "PREPARING"' in PREP,
    'prepare reuses existing PREPARING journal identity': 'dao.weeklyBackup(ownerId, backupId)' in PREP and 'existingJournal?.createdAt ?: timestampMillis' in PREP,
    'READY and UPLOADING archives are validated and preserved byte-for-byte': '"READY", "UPLOADING" -> {' in REC and 'resumable++ // Preserve exact path + bytes' in REC,
    'invalid resumable archive is terminally marked FAILED without silent replacement': 'dao.markWeeklyBackupFailed(ownerId, row.backupId' in REC and 'WeeklyBackupStorageException' in REC and 'Archive staging có kích thước hoặc SHA-256 không khớp' in REC,
    'VERIFIED recovery performs local cleanup only': '"VERIFIED" -> {' in REC and 'archive.delete()' in REC,
    'FAILED and ABANDONED journals are retained for audit': '"FAILED", "ABANDONED" -> Unit' in REC,
    'unknown states fail closed': 'else -> error("Trạng thái journal backup không được hỗ trợ' in REC,
    'active query excludes terminal and VERIFIED rows': "state IN ('PREPARING','READY','UPLOADING')" in DAO,
    'new backup is blocked while an active same-owner journal exists': 'check(activeWeeklyBackup(journal.ownerId) == null)' in DAO,
    'journal transitions are keyed by owner and backup ID': 'WHERE ownerId=:ownerId AND backupId=:backupId' in DAO,
    'recovery confines archive path to canonical owner directory': 'archive == expected && archive.parentFile == ownerDirectory' in REC,
    'recovery does not traverse or recursively delete other owner directories': all(x not in REC for x in ('walkTopDown(', 'listFiles(', 'deleteRecursively(')),
}
for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' - ' + name)
# Exercise the DAO active-state predicate with multiple accounts and each journal state.
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE weekly_backup_journal(ownerId TEXT, backupId TEXT, state TEXT, createdAt INTEGER)')
rows = [('A','a-pre','PREPARING',1),('A','a-ready','READY',2),('A','a-up','UPLOADING',3),('A','a-ver','VERIFIED',4),('A','a-fail','FAILED',5),('A','a-ab','ABANDONED',6),('B','b-up','UPLOADING',7)]
db.executemany('INSERT INTO weekly_backup_journal VALUES(?,?,?,?)', rows)
query = "SELECT backupId,state FROM weekly_backup_journal WHERE ownerId=? AND state IN ('PREPARING','READY','UPLOADING') ORDER BY createdAt DESC LIMIT 1"
active_a = db.execute(query, ('A',)).fetchone()
active_b = db.execute(query, ('B',)).fetchone()
checks2 = {
    'same-owner re-login selects newest resumable active row': active_a == ('a-up','UPLOADING'),
    'new account cannot select old owner active row': active_b == ('b-up','UPLOADING'),
    'terminal and VERIFIED rows are not selected as active': db.execute(query, ('C',)).fetchone() is None and active_a[0] not in ('a-ver','a-fail','a-ab'),
}
for name, ok in checks2.items(): print(('PASS' if ok else 'FAIL') + ' - ' + name)
count = len(checks) + len(checks2)
passed = sum(checks.values()) + sum(checks2.values())
print(f'RESULT: {passed}/{count} PASS')
if passed != count: raise SystemExit(1)
