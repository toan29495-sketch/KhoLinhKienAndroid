from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
ZIP = (ROOT / 'app/src/main/java/vn/kholinhkien/ZipRestore.kt').read_text()
COORD = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudCoordinator.kt').read_text()
APPLIER = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudSnapshotApplier.kt').read_text()
checks = []
def check(label, ok): checks.append((label, bool(ok)))
method = DATA[DATA.index('suspend fun beginLocalRestoreIfSafe'):DATA.index('/** Atomically reserve a remote snapshot')]
check('local restore reservation is a Room transaction', '@Transaction' in DATA[DATA.rfind('@Transaction', 0, DATA.index('suspend fun beginLocalRestoreIfSafe')):DATA.index('suspend fun beginLocalRestoreIfSafe')])
check('local reservation accepts only LOCAL_ZIP/STAGING journals', 'journal.sourceType == "LOCAL_ZIP" && journal.status == "STAGING"' in method)
check('local reservation validates owner and restore identifiers', 'journal.ownerId.isNotBlank() && journal.restoreId.isNotBlank()' in method)
check('local reservation rejects an existing same-ID journal', 'restoreJournal(journal.ownerId, journal.restoreId) != null' in method)
check('local reservation atomically rejects any other active restore', 'activeRestore(journal.ownerId) != null' in method)
check('reservation inserts with ABORT semantics rather than REPLACE', '@Insert(onConflict = OnConflictStrategy.ABORT)' in DATA and 'insertRestoreJournalIfAbsent(journal)' in method)
check('ZIP staging uses atomic reservation before persisting staging rows', 'dao.beginLocalRestoreIfSafe(RestoreJournal(' in ZIP and ZIP.index('dao.beginLocalRestoreIfSafe(RestoreJournal(') < ZIP.index('suspend fun persistLines'))
check('ZIP staging fails closed when reservation loses a race', 'require(reserved)' in ZIP and 'Một phiên khôi phục khác vừa được tạo' in ZIP)
check('losing reservation does not claim journal ownership', ZIP.index('require(reserved)') < ZIP.index('journalPersisted = true'))
check('remote restore reservation remains transactional and outbox-aware', 'suspend fun beginRemoteRestoreIfSafe' in DATA and 'countUnsentOutbox(journal.ownerId) != 0' in DATA[DATA.index('suspend fun beginRemoteRestoreIfSafe'):DATA.index('@Insert(onConflict = OnConflictStrategy.ABORT)', DATA.index('suspend fun beginRemoteRestoreIfSafe'))])
check('restore apply remains a single Room transaction with SNAPSHOT_DOWNLOADED precondition', 'suspend fun applyCommittedRestoreSnapshot' in DATA and 'journal.status == "SNAPSHOT_DOWNLOADED"' in DATA[DATA.index('suspend fun applyCommittedRestoreSnapshot'):DATA.index('// Dữ liệu ở các bản cũ')])
check('applier delegates live inventory replacement to the transactional DAO', 'dao.applyCommittedRestoreSnapshot(' in APPLIER and 'clearParts(' not in APPLIER and 'clearMovements(' not in APPLIER)
check('coordinator still serializes import/resume with the process gate', COORD.count('RestoreOperationGate.mutex.withLock') >= 3)
failed = [label for label, ok in checks if not ok]
for label, ok in checks:
    print(('PASS ' if ok else 'FAIL ') + label)
print(f'\n{len(checks)-len(failed)}/{len(checks)} checks PASS')
if failed: raise SystemExit(1)
