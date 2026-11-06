from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
WORKER = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text()
COORD = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudCoordinator.kt').read_text()
OUTBOX = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncWork.kt').read_text()
LIFE = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreStagingLifecycle.kt').read_text()
checks=[]
def check(label, ok): checks.append((label, bool(ok)))
entry = WORKER[WORKER.index('override suspend fun doWork()'):WORKER.index('private suspend fun doWorkWhileRestoreGateHeld')]
body = WORKER[WORKER.index('private suspend fun doWorkWhileRestoreGateHeld'):WORKER.index('private suspend fun hasActiveAdminProfile')]
check('SyncPullWorker acquires shared restore-operation gate before any work', entry.index('RestoreOperationGate.mutex.tryLock()') < entry.index('doWorkWhileRestoreGateHeld()'))
check('busy restore causes WorkManager retry instead of concurrent pull', 'if (!RestoreOperationGate.mutex.tryLock()) return Result.retry()' in entry)
check('gate is released in finally for success, retry, failure and cancellation', 'finally {\n            RestoreOperationGate.mutex.unlock()' in entry)
check('all pull cursor reads occur only inside gate-held worker body', 'dao.getSyncCursor(cursorKey)' in body)
check('all remote feed RPCs occur only inside gate-held worker body', 'client.postgrest.rpc(' in body)
check('owner event application remains inside gate-held worker body', 'dao.applyPulledMovement(ownerId, movement)' in body and 'dao.applyPulledAdminMovement(ownerId, movement)' in body)
check('cursor advancement remains conditional on unlocked owner partition', 'dao.savePulledCursorIfUnlocked(ownerId, SyncCursor(cursorKey, cursor))' in body)
check('remote restore snapshot work still uses shared snapshot gate', 'RestoreSnapshotGate.mutex.withLock' in body)
check('coordinator import and resume continue to hold same operation gate', COORD.count('RestoreOperationGate.mutex.withLock') >= 3)
check('abort lifecycle remains serialized by same operation gate', 'RestoreOperationGate.mutex.withLock' in LIFE)
check('busy-worker path does not mutate cursor or DAO before retry', entry.index('if (!RestoreOperationGate.mutex.tryLock())') < entry.index('doWorkWhileRestoreGateHeld()') and 'dao.' not in entry)
check('worker retains cancellation propagation inside gated body', 'catch (cancelled: CancellationException)' in body and 'throw cancelled' in body)
outbox_entry = OUTBOX[OUTBOX.index('class SyncOutboxWorker'):OUTBOX.index('companion object { const val KEY_OWNER_ID') ]
outbox_body = outbox_entry[outbox_entry.index('private suspend fun doWorkWhileRestoreGateHeld'): ]
check('outbox uploader acquires shared restore-operation gate before sending', 'RestoreOperationGate.mutex.tryLock()' in outbox_entry and outbox_entry.index('tryLock()') < outbox_entry.index('doWorkWhileRestoreGateHeld()'))
check('outbox worker retries without DAO/network work while restore owns gate', 'if (!RestoreOperationGate.mutex.tryLock()) return Result.retry()' in outbox_entry and 'dao.' not in outbox_entry.split('if (!RestoreOperationGate.mutex.tryLock())')[0])
check('outbox gate releases in finally across all worker outcomes', 'finally {\n            RestoreOperationGate.mutex.unlock()' in outbox_entry)
check('outbox sender invocation is inside the gate-held body', 'SyncOutboxSender(' in outbox_body and '.sendPending(ownerId)' in outbox_body)
check('outbox worker preserves cancellation propagation', 'catch (cancelled: CancellationException)' in outbox_body and 'throw cancelled' in outbox_body)
failed=[x for x in checks if not x[1]]
for label,ok in checks: print(('PASS ' if ok else 'FAIL ')+label)
print(f'\n{len(checks)-len(failed)}/{len(checks)} checks PASS')
if failed: raise SystemExit(1)
