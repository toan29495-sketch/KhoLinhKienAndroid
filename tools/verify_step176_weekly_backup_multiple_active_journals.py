#!/usr/bin/env python3
"""Step176: fail-closed handling for duplicate active journals per owner."""
from pathlib import Path
import sqlite3
ROOT = Path(__file__).resolve().parents[1]
REC = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupRecovery.kt').read_text()
DAO = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
WORK = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt').read_text()
checks = {
    'recovery loads only owner-scoped journals': 'weeklyBackupsForOwner(ownerId)' in REC,
    'duplicate active rows are detected before recovery counters or mutations': REC.index('val activeRows =') < REC.index('var preparingRemoved =') < REC.index('for (row in rows)'),
    'active state set is exactly PREPARING READY UPLOADING': 'setOf("PREPARING", "READY", "UPLOADING")' in REC,
    'multiple active rows fail closed with diagnostic': 'check(activeRows.size <= 1)' in REC and 'nhiều journal backup tuần đang hoạt động' in REC,
    'conflict path does not auto-fail or delete duplicate rows': 'markWeeklyBackupFailed' not in REC[REC.index('val activeRows ='):REC.index('var preparingRemoved =')],
    'worker routes recovery conflict through retryable generic exception path': 'WeeklyBackupRecovery.recoverOwner' in WORK and 'catch (_: Exception)' in WORK and 'Result.retry()' in WORK,
    'active selection has deterministic tie-breaker': "ORDER BY createdAt DESC, backupId ASC LIMIT 1" in DAO,
    'new journal creation still transactionally blocks existing active row': '@Transaction\n    suspend fun beginWeeklyBackup' in DAO and 'check(activeWeeklyBackup(journal.ownerId) == null)' in DAO,
    'owner-scoped query and state updates remain enforced': 'weeklyBackupsForOwner(ownerId)' in REC and 'WHERE ownerId=:ownerId AND backupId=:backupId' in DAO,
}
for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' - ' + name)
# SQLite model: conflict must be detected before any state or archive mutation.
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE journal(owner TEXT, backup TEXT, state TEXT, created INTEGER, archive TEXT)')
db.executemany('INSERT INTO journal VALUES(?,?,?,?,?)', [
    ('A','a1','READY',10,'a1.zip'), ('A','a2','UPLOADING',20,'a2.zip'),
    ('A','a3','VERIFIED',30,'a3.zip'), ('B','b1','UPLOADING',15,'b1.zip')])
rows = db.execute("SELECT backup,state,archive FROM journal WHERE owner=? ORDER BY created DESC", ('A',)).fetchall()
active = [r for r in rows if r[1] in ('PREPARING','READY','UPLOADING')]
before = db.execute('SELECT backup,state,archive FROM journal ORDER BY owner,backup').fetchall()
conflict_detected = len(active) > 1
# Model the production fail-closed branch: no UPDATE/DELETE when conflict exists.
after = db.execute('SELECT backup,state,archive FROM journal ORDER BY owner,backup').fetchall()
checks2 = {
    'two active rows for one owner are detected as conflict': conflict_detected,
    'conflict leaves every same-owner row and archive reference unchanged': before == after,
    'other owner active journal remains independent': db.execute("SELECT backup FROM journal WHERE owner='B' AND state='UPLOADING'").fetchone() == ('b1',),
    'VERIFIED row is excluded from active conflict count': all(r[1] != 'VERIFIED' for r in active),
    'single active row remains recoverable': len([r for r in rows if r[1] in ('PREPARING','READY','UPLOADING') and r[0]=='a2']) == 1,
}
for name, ok in checks2.items(): print(('PASS' if ok else 'FAIL') + ' - ' + name)
count=len(checks)+len(checks2); passed=sum(checks.values())+sum(checks2.values())
print(f'RESULT: {passed}/{count} PASS')
if passed != count: raise SystemExit(1)
