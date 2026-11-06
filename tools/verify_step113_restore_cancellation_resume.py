#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/java/vn/kholinhkien'
ZIP = (BASE / 'ZipRestore.kt').read_text()
SENDER = (BASE / 'RestoreCloudSessionSender.kt').read_text()
COMMIT = (BASE / 'RestoreCloudCommitter.kt').read_text()
READER = (BASE / 'RestoreCloudSnapshotReader.kt').read_text()
COORD = (BASE / 'RestoreCloudCoordinator.kt').read_text()
checks = []
def check(name, condition): checks.append((name, bool(condition)))

def cancellation_precedes_generic(source):
    return ('catch (cancelled: CancellationException)' in source and
            source.index('catch (cancelled: CancellationException)') < source.index('catch (error: Exception)'))

check('ZIP staging imports and propagates coroutine cancellation',
      'import kotlinx.coroutines.CancellationException' in ZIP and cancellation_precedes_generic(ZIP))
check('ZIP cancellation does not write FAILED journal',
      'catch (cancelled: CancellationException)' in ZIP and
      ZIP[ZIP.index('catch (cancelled: CancellationException)'):ZIP.index('catch (error: Exception)')].find('status = "FAILED"') == -1)
check('ZIP cancellation preserves persisted STAGING journal for safe re-import',
      'journalPersisted = true' in ZIP and 'status = "STAGING"' in ZIP and
      'if (!journalPersisted) runCatching { durableStage.deleteRecursively() }' in ZIP and
      'preserve durable STAGING rows' in ZIP)
check('cloud sender propagates cancellation without rewriting CLOUD_STAGING journal',
      cancellation_precedes_generic(SENDER) and 'Keep CLOUD_STAGING' in SENDER and
      SENDER[SENDER.index('catch (cancelled: CancellationException)'):SENDER.index('catch (error: Exception)')].find('saveRestoreJournal') == -1)
check('cloud sender retains idempotent same-chunk retry rationale',
      'same restore/chunk key' in SENDER)
check('committer persists COMMIT_UNKNOWN before calling commit RPC',
      COMMIT.index('status = "COMMIT_UNKNOWN"') < COMMIT.index('"commit_inventory_restore"'))
check('committer propagates cancellation and leaves ambiguous commit state intact',
      cancellation_precedes_generic(COMMIT) and 'Keep it unchanged' in COMMIT and
      COMMIT[COMMIT.index('catch (cancelled: CancellationException)'):COMMIT.index('catch (error: Exception)')].find('saveRestoreJournal') == -1)
check('snapshot reader propagates cancellation without marking a normal error',
      cancellation_precedes_generic(READER) and 'Keep SNAPSHOT_DOWNLOADING' in READER and
      READER[READER.index('catch (cancelled: CancellationException)'):READER.index('catch (error: Exception)')].find('saveRestoreJournal') == -1)
check('snapshot retry clears only snapshot staging buckets',
      'clearRestoreStagedRowsByType(ownerId, restoreId, SNAPSHOT_PART)' in READER and
      'clearRestoreStagedRowsByType(ownerId, restoreId, SNAPSHOT_MOVEMENT)' in READER)
check('coordinator imports cancellation type',
      'import kotlinx.coroutines.CancellationException' in COORD)
check('completed-journal cleanup propagates cancellation',
      COORD.count('catch (cancelled: CancellationException)') == 2)
check('coordinator no longer uses runCatching for suspend cleanup',
      'runCatching {\n                RestoreStagingLifecycle.cleanupCompleted' not in COORD and
      'runCatching {\n            RestoreStagingLifecycle.cleanupCompleted' not in COORD)
check('resume remains bound to exact owner and journal',
      'require(journal.ownerId == ownerId)' in COORD and 'resumeLocked(ownerId, restoreId)' in COORD)
check('live inventory apply remains gated on fully downloaded snapshot',
      'if (journal.status == "SNAPSHOT_DOWNLOADED")' in COORD and
      'RestoreCloudSnapshotApplier(context.applicationContext, dao).apply(ownerId, restoreId)' in COORD)

for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
print(f'RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
