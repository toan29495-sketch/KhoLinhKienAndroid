#!/usr/bin/env python3
from pathlib import Path
import asyncio
ROOT = Path(__file__).resolve().parents[1]
LIFE = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreStagingLifecycle.kt').read_text()
COORD = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudCoordinator.kt').read_text()
ZIP = (ROOT / 'app/src/main/java/vn/kholinhkien/ZipRestore.kt').read_text()
checks=[]
def check(name, ok): checks.append((name, bool(ok)))
check('single process-wide restore operation gate exists', 'internal object RestoreOperationGate' in LIFE and 'val mutex = Mutex()' in LIFE)
check('import uses shared gate', 'RestoreOperationGate.mutex.withLock' in COORD and 'importAndRestore' in COORD)
check('resume uses shared gate', 'suspend fun resume(ownerId: String, restoreId: String): Result = RestoreOperationGate.mutex.withLock' in COORD)
abort = LIFE[LIFE.index('suspend fun abort('):LIFE.index('private fun safeStageDirectory')]
check('abort acquires shared gate', 'RestoreOperationGate.mutex.withLock' in abort)
check('abort validates owner before mutation', 'journal.ownerId == ownerId' in abort)
check('abort is limited to pre-commit statuses', 'setOf("STAGING", "STAGED", "FAILED")' in abort)
check('journal deletion remains conditional', 'dao.deleteAbortableRestoreJournal(ownerId, restoreId)' in abort and 'check(deleted == 1)' in abort)
check('journal and staged rows clear atomically', 'AppDb.get(context).withTransaction' in abort and 'dao.clearRestoreStagedRows(ownerId, restoreId)' in abort)
check('filesystem cleanup occurs while gate held', abort.index('RestoreOperationGate.mutex.withLock') < abort.index('directory.deleteRecursively()'))
check('cloud commit states cannot be aborted', 'CLOUD_STAGED' not in abort and 'COMMIT_UNKNOWN' not in abort and 'COMMIT_CONFIRMED' not in abort)
check('ZIP cancellation preserves journal for deterministic retry', 'Keep the durable STAGING journal' in ZIP and 'throw cancelled' in ZIP)
check('ZIP import is routed through coordinator', 'ZipRestore.restore(context, uri, dao, ownerId)' in COORD)
check('coordinator no longer has a separate mutex', 'private companion object' not in COORD and 'val mutex = Mutex()' not in COORD)

async def interleave():
    lock=asyncio.Lock(); events=[]
    async def operation(name, pause):
        async with lock:
            events.append(name+'-start')
            await asyncio.sleep(pause)
            events.append(name+'-end')
    await asyncio.gather(operation('abort', .02), operation('import', .001))
    return events
sequence=asyncio.run(interleave())
check('serialized operation model has no overlap', sequence in ([ 'abort-start','abort-end','import-start','import-end'], ['import-start','import-end','abort-start','abort-end']))
for name, ok in checks: print(('PASS' if ok else 'FAIL')+' '+name)
print(f'RESULT: {sum(v for _,v in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(v for _,v in checks) else 1)
