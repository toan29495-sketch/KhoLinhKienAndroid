#!/usr/bin/env python3
from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/java/vn/kholinhkien'
DATA = (BASE / 'Data.kt').read_text()
LIFE = (BASE / 'RestoreStagingLifecycle.kt').read_text()
MAIN = (BASE / 'MainActivity.kt').read_text()
checks = []
def check(name, condition): checks.append((name, bool(condition)))

# No unscoped DAO read may remain anywhere in the restore implementation.
restore_files = [
    'RestoreCloudCommitter.kt', 'RestoreStagingLifecycle.kt',
    'RestoreCloudSessionSender.kt', 'RestoreCloudSnapshotApplier.kt',
    'RestoreCloudCoordinator.kt', 'RestoreCloudPayloadPreparer.kt',
    'RestoreCloudSnapshotReader.kt', 'ZipRestore.kt'
]
all_restore = '\n'.join((BASE / name).read_text() for name in restore_files)
check('journal DAO read requires both ownerId and restoreId',
      '@Query("SELECT * FROM restore_journal WHERE ownerId = :ownerId AND restoreId = :restoreId LIMIT 1")' in DATA and
      'suspend fun restoreJournal(ownerId: String, restoreId: String)' in DATA)
check('no unscoped restoreJournal call remains in restore implementation',
      not re.search(r'(?:dao\.)?restoreJournal\(restoreId\)', all_restore + DATA))
check('ZIP import resolves existing journal using authenticated archive owner',
      'dao.restoreJournal(ownerId, restoreId)' in (BASE / 'ZipRestore.kt').read_text())
check('coordinator resolves each phase checkpoint under requested owner',
      'dao.restoreJournal(ownerId, restoreId)' in (BASE / 'RestoreCloudCoordinator.kt').read_text())
check('Room snapshot apply reads journal with owner scope',
      'restoreJournal(ownerId, restoreId)' in DATA[DATA.index('suspend fun applyCommittedRestoreSnapshot'):])
check('resume and cleanup reject owner mismatch before touching stage data',
      LIFE.count('require(journal.ownerId == ownerId)') >= 2 and
      'require(journal.ownerId == ownerId)' in LIFE[LIFE.index('suspend fun abort'):])
check('staging directory must be exact direct child of canonical restore root',
      'directory.parentFile?.canonicalFile == root' in LIFE and
      'directory.name == journal.restoreId' in LIFE)
check('UI only renders journals returned for active profile owner',
      'dao.restoreJournals(profile.id)' in MAIN and
      'it.restoreId == restoreId && it.ownerId == profile.id' in MAIN)
check('restore action passes current profile owner to coordinator',
      'RestoreCloudCoordinator(context, dao).resume(profile.id, journal.restoreId)' in MAIN)
check('staged row read/write/delete DAO operations remain owner-scoped',
      'WHERE ownerId = :ownerId AND restoreId = :restoreId' in DATA and
      'DELETE FROM restore_staged_rows WHERE ownerId = :ownerId AND restoreId = :restoreId' in DATA)
check('cloud photo restore path remains owner-prefixed',
      '"$ownerId/restore-$restoreId/$digest.$extension"' in (BASE / 'RestorePhotoUploadCoordinator.kt').read_text())

for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
print(f'RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
