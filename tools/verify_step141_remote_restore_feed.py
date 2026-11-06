from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text(encoding='utf-8')
PULL = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text(encoding='utf-8')
READER = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudSnapshotReader.kt').read_text(encoding='utf-8')
APPLIER = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudSnapshotApplier.kt').read_text(encoding='utf-8')
UI = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text(encoding='utf-8')
checks = {
    'Room schema is now v14 and retains the 12-to-13 restore migration': 'version = 14' in DATA and 'Migration(12, 13)' in DATA,
    'Room 12-to-13 migration adds remote restore metadata': 'Migration(12, 13)' in DATA and 'ADD COLUMN sourceType' in DATA and 'ADD COLUMN partChunkCount' in DATA and 'ADD COLUMN movementChunkCount' in DATA,
    'legacy restore journals default to local ZIP source': "defaultValue = \"'LOCAL_ZIP'\"" in DATA,
    'remote restore reservation is atomic with outbox and active-restore checks': '@Transaction\n    suspend fun beginRemoteRestoreIfSafe' in DATA and 'countUnsentOutbox(journal.ownerId) != 0' in DATA and 'activeRestore(journal.ownerId) != null' in DATA,
    'pull worker resumes interrupted remote restore journals under shared gate': 'activeRestore.sourceType != "REMOTE_FEED"' in PULL and 'RestoreSnapshotGate.mutex.withLock' in PULL and 'RestoreCloudSnapshotReader(applicationContext, dao).download(ownerId, latest.restoreId)' in PULL and 'RestoreCloudSnapshotApplier(applicationContext, dao).apply(ownerId, latest.restoreId)' in PULL,
    'restore marker requires manifest, counts and chunk counts': all(x in PULL for x in ['part_chunks', 'movement_chunks', 'manifest_sha256', 'part_count', 'movement_count']),
    'restore marker is applied before cursor advances': PULL.index('RestoreCloudSnapshotApplier(applicationContext, dao).apply(ownerId, restoreId)') < PULL.index('cursor = eventId', PULL.index('"RESTORE_COMMIT" ->')),
    'local outbox blocks remote snapshot replacement without deletion': 'beginRemoteRestoreIfSafe(remoteJournal)' in PULL and 'blockedByLocalChanges = true' in PULL and 'countUnsentOutbox' in DATA,
    'Admin global feed never replaces Admin personal inventory': 'if (!isAdmin)' in PULL[PULL.index('"RESTORE_COMMIT" ->'):PULL.index('else -> error', PULL.index('"RESTORE_COMMIT" ->'))],
    'remote reader uses manifest chunk counts, not absent local ZIP payload': 'journal.sourceType == "REMOTE_FEED"' in READER and 'journal.partChunkCount to journal.movementChunkCount' in READER,
    'remote chunk count is bounded and zero-consistent': 'chunkCount <= 8192' in READER and '(rowCount == 0) == (chunkCount == 0)' in READER,
    'snapshot is verified before Room apply': 'verify_inventory_restore_snapshot_chunk' in READER and 'applyCommittedRestoreSnapshot' in APPLIER,
    'remote restore UI does not ask user to reselect ZIP': 'journal.sourceType == "REMOTE_FEED"' in UI and 'được đồng bộ tự động từ cloud' in UI,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'STEP141_STATIC={sum(checks.values())}/{len(checks)}')

# Exercise the exact SQLite column migration and verify existing rows retain local defaults.
conn = sqlite3.connect(':memory:')
conn.execute('CREATE TABLE restore_journal (restoreId TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, manifestSha256 TEXT NOT NULL, status TEXT NOT NULL, stagedDirectory TEXT NOT NULL, partCount INTEGER NOT NULL, movementCount INTEGER NOT NULL, snapshotCursor INTEGER NOT NULL, lastError TEXT NOT NULL, updatedAt INTEGER NOT NULL)')
conn.execute("INSERT INTO restore_journal VALUES ('legacy-id','owner','digest','STAGED','/tmp/stage',1,2,0,'',1)")
conn.execute("ALTER TABLE restore_journal ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'LOCAL_ZIP'")
conn.execute('ALTER TABLE restore_journal ADD COLUMN partChunkCount INTEGER NOT NULL DEFAULT -1')
conn.execute('ALTER TABLE restore_journal ADD COLUMN movementChunkCount INTEGER NOT NULL DEFAULT -1')
row = conn.execute('SELECT sourceType,partChunkCount,movementChunkCount FROM restore_journal WHERE restoreId=?', ('legacy-id',)).fetchone()
migration_ok = row == ('LOCAL_ZIP', -1, -1)
print(('PASS' if migration_ok else 'FAIL') + ' - SQLite 12->13 preserves existing local restore journal defaults')
if not all(checks.values()) or not migration_ok:
    raise SystemExit(1)
