#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/java/vn/kholinhkien'
APPLIER = (BASE / 'RestoreCloudSnapshotApplier.kt').read_text()
LIFECYCLE = (BASE / 'RestoreStagingLifecycle.kt').read_text()
COORD = (BASE / 'RestoreCloudCoordinator.kt').read_text()
MAIN = (BASE / 'MainActivity.kt').read_text()
checks = []
def check(name, condition): checks.append((name, bool(condition)))

check('snapshot applier imports coroutine cancellation type',
      'import kotlinx.coroutines.CancellationException' in APPLIER)
check('Room committed snapshot is applied before post-commit cleanup',
      APPLIER.index('dao.applyCommittedRestoreSnapshot(') < APPLIER.index('RestoreStagingLifecycle.cleanupCompleted('))
check('post-commit cleanup explicitly rethrows CancellationException',
      'catch (cancelled: CancellationException)' in APPLIER and
      'throw cancelled' in APPLIER)
check('ordinary cleanup exceptions remain best-effort and report incomplete cleanup',
      'catch (_: Exception)' in APPLIER and 'false' in APPLIER and
      'val cleanupComplete = try' in APPLIER)
check('post-commit cleanup no longer wraps suspend call in runCatching',
      'runCatching {\n            RestoreStagingLifecycle.cleanupCompleted' not in APPLIER)
check('COMPLETE journal remains the retry marker after cleanup interruption',
      'journal.status == "COMPLETE"' in COORD and
      'localCleanupComplete = cleanupComplete' in COORD)
check('cleanup requires COMPLETE journal and matching owner cursor',
      'journal.status == "COMPLETE"' in LIFECYCLE and
      'dao.getSyncCursor(ownerId) == journal.snapshotCursor' in LIFECYCLE)
check('cleanup retains COMPLETE journal while clearing only staged rows/files',
      'dao.clearCompletedRestoreStagedRows(ownerId, restoreId)' in LIFECYCLE and
      'dao.deleteAbortableRestoreJournal' not in LIFECYCLE[LIFECYCLE.index('suspend fun cleanupCompleted'):LIFECYCLE.index('/** Abort is intentionally')])
check('UI reports completion only from coordinator result after applier returns',
      'RestoreCloudCoordinator(context, dao).importAndRestore(selectedUri, profile.id)' in MAIN and
      'Khôi phục hoàn tất:' in MAIN)

for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
print(f'STEP124_RESTORE_APPLIER_RESULT={sum(ok for _, ok in checks)}/{len(checks)}')
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
