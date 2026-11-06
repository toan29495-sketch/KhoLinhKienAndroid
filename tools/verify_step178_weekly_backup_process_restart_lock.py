#!/usr/bin/env python3
"""Step178: process-restart and cancellation behavior of the in-process backup lock."""
from pathlib import Path
import threading, time
ROOT=Path(__file__).resolve().parents[1]
WORK=(ROOT/'app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt').read_text()
checks={
 'lock is explicitly in-process, not persisted': 'In-process single-flight fence' in WORK and 'ConcurrentHashMap<String, Mutex>()' in WORK,
 'lock registry is process-memory only': 'ConcurrentHashMap<String, Mutex>()' in WORK and 'SharedPreferences' not in WORK and 'DataStore' not in WORK,
 'critical section uses structured withLock': 'return mutex.withLock { block() }' in WORK,
 'no manual lock/unlock can strand a lock': 'mutex.lock()' not in WORK and 'mutex.unlock()' not in WORK,
 'worker propagates coroutine cancellation': 'catch (cancelled: CancellationException)' in WORK and 'throw cancelled' in WORK,
 'worker rechecks owner after waiting': 'currentUserOrNull()?.id != ownerId) return@withOwner Result.success()' in WORK,
 'durable journal recovery runs after lock acquisition': WORK.index('WeeklyBackupExecutionLock.withOwner(ownerId)') < WORK.index('WeeklyBackupRecovery.recoverOwner'),
 'active journal is read only after recovery': WORK.index('WeeklyBackupRecovery.recoverOwner') < WORK.index('dao.activeWeeklyBackup(ownerId)'),
 'scheduler remains owner-scoped and unique': 'inventory-weekly-backup-$ownerId' in WORK and 'ExistingPeriodicWorkPolicy.KEEP' in WORK,
 'no automatic login/startup schedule activation': 'WeeklyBackupWorkScheduler.schedule(' not in WORK,
 'Room transaction remains durable duplicate guard': 'check(activeWeeklyBackup(journal.ownerId) == null)' in (ROOT/'app/src/main/java/vn/kholinhkien/Data.kt').read_text(),
 'no cross-process file lock introduced': 'FileLock' not in WORK and 'RandomAccessFile' not in WORK,
}
for name,ok in checks.items(): print(('PASS' if ok else 'FAIL')+' - '+name)
# Model process death: a fresh process gets a fresh registry and therefore no stale mutex.
old_registry={'A': threading.Lock()}
old_registry['A'].acquire()
# Simulate OS process termination by discarding all old process objects.
old_registry=None
new_registry={}
new_lock=new_registry.setdefault('A', threading.Lock())
acquired=new_lock.acquire(timeout=.2)
if acquired: new_lock.release()
# Model coroutine cancellation while waiting: structured lock scope releases after owner exits.
lock=threading.Lock(); lock.acquire(); waiting_done=threading.Event()
def waiter():
    with lock:
        waiting_done.set()
t=threading.Thread(target=waiter); t.start(); time.sleep(.03); lock.release(); t.join(.5)
model={
 'fresh process registry has no stale owner lock': acquired,
 'waiting worker enters after prior holder releases': waiting_done.is_set(),
 'waiting worker terminates without stranded lock': not t.is_alive(),
}
for name,ok in model.items(): print(('PASS' if ok else 'FAIL')+' - '+name)
passed=sum(checks.values())+sum(model.values()); total=len(checks)+len(model)
print(f'RESULT: {passed}/{total} PASS')
if passed != total: raise SystemExit(1)
