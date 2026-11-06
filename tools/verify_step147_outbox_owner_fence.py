#!/usr/bin/env python3
"""Verify outbox failure transitions remain owner-scoped across account switches."""
from pathlib import Path
import sqlite3
ROOT = Path(__file__).resolve().parents[1]
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text(encoding='utf-8')
SENDER = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text(encoding='utf-8')
WORK = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncWork.kt').read_text(encoding='utf-8')
checks=[]
def check(name, condition): checks.append((name, bool(condition)))
check('Pending outbox selection is owner-scoped', "o.ownerId = :ownerId" in DATA and 'pendingOutbox(ownerId: String)' in DATA)
check('Sender validates current session before reading pending operations', SENDER.index('authenticatedUserId != ownerId') < SENDER.index('dao.pendingOutbox(ownerId)'))
check('Sender revalidates owner before each claim and network boundary', SENDER.count('requireActiveOwner(ownerId)') >= 5)
check('Failure transition query includes ownerId and operationId', "WHERE ownerId = :ownerId AND operationId = :operationId AND status = 'IN_FLIGHT'" in DATA)
check('Blocked transition query includes ownerId and operationId', DATA.count("WHERE ownerId = :ownerId AND operationId = :operationId AND status = 'IN_FLIGHT'") >= 2)
check('Sender passes captured owner to failure transition', 'markOutboxFailed(ownerId, claimed.operationId, message)' in SENDER)
check('Sender passes captured owner to blocked transition', 'markOutboxBlocked(ownerId, claimed.operationId, message)' in SENDER)
check('Cancellation is propagated without converting to failure', 'catch (cancelled: CancellationException)' in SENDER and 'throw cancelled' in SENDER)
check('Worker is parameterized by immutable owner ID', 'KEY_OWNER_ID to ownerId' in WORK and 'inputData.getString(KEY_OWNER_ID)' in WORK)
check('Logout cancels owner work before auth sign-out', (ROOT/'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text(encoding='utf-8').index('SyncWorkScheduler.cancelOwner(context, ownerId)') < (ROOT/'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text(encoding='utf-8').index('authRepository.signOut()'))
# SQLite smoke: a stale A worker must not change B's outbox row even if operation IDs collide.
con=sqlite3.connect(':memory:')
con.execute('CREATE TABLE sync_outbox(ownerId TEXT, operationId TEXT, status TEXT, lastError TEXT, claimStartedAt INTEGER, PRIMARY KEY(ownerId, operationId))')
con.executemany('INSERT INTO sync_outbox VALUES(?,?,?,?,?)', [('A','same-op','IN_FLIGHT','',1),('B','same-op','IN_FLIGHT','',1)])
con.execute("UPDATE sync_outbox SET status='FAILED',lastError=?,claimStartedAt=0 WHERE ownerId=? AND operationId=? AND status='IN_FLIGHT'", ('network error','A','same-op'))
rows=dict((r[0],r[1]) for r in con.execute('SELECT ownerId,status FROM sync_outbox'))
check('SQLite smoke: stale A worker changes only A row', rows == {'A':'FAILED','B':'IN_FLIGHT'})
con.execute("UPDATE sync_outbox SET status='BLOCKED',lastError=?,claimStartedAt=0 WHERE ownerId=? AND operationId=? AND status='IN_FLIGHT'", ('conflict','A','same-op'))
rows=dict((r[0],r[1]) for r in con.execute('SELECT ownerId,status FROM sync_outbox'))
check('SQLite smoke: owner-scoped BLOCKED transition leaves B unchanged', rows == {'A':'FAILED','B':'IN_FLIGHT'})
for name,ok in checks: print(f"{'PASS' if ok else 'FAIL'}: {name}")
print(f'RESULT: {sum(ok for _,ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _,ok in checks) else 1)
