#!/usr/bin/env python3
"""Step177: owner-scoped in-process single-flight protection for weekly backup."""
from pathlib import Path
import threading, time
ROOT=Path(__file__).resolve().parents[1]
WORK=(ROOT/'app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt').read_text()
checks={
 'worker acquires owner lock before backup workflow': WORK.index('WeeklyBackupExecutionLock.withOwner(ownerId)') < WORK.index('executeOwnerBackup(ownerId, client)'),
 'session is rechecked after lock wait': 'Re-check after waiting' in WORK and 'currentUserOrNull()?.id != ownerId) return@withOwner Result.success()' in WORK,
 'mutex registry is keyed by owner': 'ConcurrentHashMap<String, Mutex>()' in WORK and 'locks.getOrPut(ownerId)' in WORK,
 'critical section uses coroutine Mutex.withLock': 'mutex.withLock { block() }' in WORK,
 'owner id is validated before lock acquisition': 'require(isCanonicalOwnerId(ownerId))' in WORK,
 'cancellation still propagates': 'catch (cancelled: CancellationException)' in WORK and 'throw cancelled' in WORK,
 'durable scheduler still uses unique periodic work KEEP': 'enqueueUniquePeriodicWork(' in WORK and 'ExistingPeriodicWorkPolicy.KEEP' in WORK,
 'scheduler unique name remains owner-scoped': 'inventory-weekly-backup-$ownerId' in WORK,
 'no login/startup activation introduced': 'WeeklyBackupWorkScheduler.schedule(' not in WORK,
 'DAO transaction still prevents second active journal': 'check(activeWeeklyBackup(journal.ownerId) == null)' in (ROOT/'app/src/main/java/vn/kholinhkien/Data.kt').read_text(),
}
for n,ok in checks.items(): print(('PASS' if ok else 'FAIL')+' - '+n)
# Threaded model: same owner serializes; different owners are not globally blocked.
locks={}; guard=threading.Lock(); events=[]
def lock_for(owner):
 with guard: return locks.setdefault(owner, threading.Lock())
def task(owner, label, delay):
 with lock_for(owner):
  events.append(('start',label,time.monotonic())); time.sleep(delay); events.append(('end',label,time.monotonic()))
threads=[threading.Thread(target=task,args=('A','A1',.08)),threading.Thread(target=task,args=('A','A2',.01)),threading.Thread(target=task,args=('B','B1',.01))]
for t in threads:t.start()
for t in threads:t.join()
by={}
for kind,label,ts in events: by.setdefault(label,{})[kind]=ts
checks2={
 'same-owner jobs do not overlap': by['A1']['end'] <= by['A2']['start'] or by['A2']['end'] <= by['A1']['start'],
 'different-owner job can overlap same-owner job': any(by['B1']['start'] < by[x]['end'] and by['B1']['end'] > by[x]['start'] for x in ('A1','A2')),
 'all simulated jobs finish': all('end' in v for v in by.values()),
}
for n,ok in checks2.items(): print(('PASS' if ok else 'FAIL')+' - '+n)
count=len(checks)+len(checks2); passed=sum(checks.values())+sum(checks2.values())
print(f'RESULT: {passed}/{count} PASS')
if passed!=count: raise SystemExit(1)
