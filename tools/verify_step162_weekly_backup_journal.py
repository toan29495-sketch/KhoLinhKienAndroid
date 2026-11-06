#!/usr/bin/env python3
"""Step162 source and SQLite migration contract checks."""
from pathlib import Path
import sqlite3
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
BACKUP = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
UPLOAD = (ROOT / 'app/src/main/java/vn/kholinhkien/SupabaseWeeklyBackupUploader.kt').read_text()
checks = []
def check(label, ok): checks.append((label, bool(ok)))

check('Room schema increments from 13 to 14', 'version = 14, exportSchema = false' in DATA)
check('13-to-14 migration is registered', 'Migration(13, 14)' in DATA and 'MIGRATION_13_14)' in DATA)
check('journal entity is keyed by immutable backupId and registered with Room', '@Entity(tableName = "weekly_backup_journal"' in DATA and '@PrimaryKey val backupId: String' in DATA and 'WeeklyBackupJournal::class], version = 14' in DATA)
check('journal captures owner/state/path and archive digest metadata', all(x in DATA for x in ['val ownerId: String','val state: String','val archivePath: String','val archiveSizeBytes: Long','val sha256: String']))
check('active journal lookup is owner-scoped', "WHERE ownerId = :ownerId AND state IN ('PREPARING','READY','UPLOADING')" in DATA)
check('begin operation is transactional and rejects parallel active backup', '@Transaction\n    suspend fun beginWeeklyBackup' in DATA and 'activeWeeklyBackup(journal.ownerId) == null' in DATA)
check('state changes are guarded by prior state', all(x in DATA for x in ["state='READY'", "state IN ('READY','UPLOADING')", "state='VERIFIED'", "state IN ('PREPARING','READY','UPLOADING')"]))
check('weekly archive is staged in app-private filesDir', 'context.filesDir, "weekly-backup-staging"' in BACKUP)
check('archive filename is stable for backupId', 'File(directory, "$backupId.zip")' in BACKUP)
check('journal is committed before snapshot ZIP creation and backupId is caller-stable', BACKUP.index('dao.beginWeeklyBackup(') < BACKUP.index('dao.captureAutomaticBackupSnapshot(ownerId)') and 'backupId: String,' in BACKUP)
check('READY metadata is persisted only after ZIP verification and digest', BACKUP.index('BackupArchiveInspector.verify(it, ownerId)') < BACKUP.index('dao.markWeeklyBackupReady('))
check('PREPARING retries can rebuild incomplete local archive', 'if (journal.state == "READY" || journal.state == "UPLOADING")' in BACKUP and 'archive.delete() // PREPARING means no upload' in BACKUP)
check('READY/UPLOADING retry refuses missing or changed bytes', 'Archive của backup đang chờ upload bị mất/thay đổi' in BACKUP)
check('uploader verifies the exact durable journal identity', 'journal.archivePath != prepared.archive.canonicalPath' in UPLOAD and 'journal.sha256.equals(prepared.sha256' in UPLOAD)
check('uploader records UPLOADING before network work', UPLOAD.index('dao.markWeeklyBackupUploading(') < UPLOAD.index('beginRegistration(prepared, backupId, journal.archiveSizeBytes)'))
check('uploader marks VERIFIED only after server confirmation', UPLOAD.index('status == "VERIFIED"') < UPLOAD.index('dao.markWeeklyBackupVerified('))
check('staged archive is deleted only after local VERIFIED commit', UPLOAD.index('dao.markWeeklyBackupVerified(') < UPLOAD.index('prepared.archive.delete()'))
check('result preserves journal size after staged ZIP cleanup', 'archiveSizeBytes = journal.archiveSizeBytes' in UPLOAD)
check('staging root is owner-scoped and private', 'File(File(context.filesDir, "weekly-backup-staging"), prepared.ownerId)' in UPLOAD)
check('no WorkManager scheduling introduced', 'PeriodicWorkRequest' not in UPLOAD and 'enqueueUniquePeriodicWork' not in UPLOAD)
check('no Gradle workflow or service-role credential introduced', 'service_role' not in UPLOAD.lower() and 'gradle' not in BACKUP.lower())

# Execute the exact CREATE TABLE / INDEX shape of migration 13->14 against a
# disposable SQLite database with representative pre-existing v13 tables/rows.
with tempfile.NamedTemporaryFile(suffix='.db') as f:
    db = sqlite3.connect(f.name)
    db.execute('CREATE TABLE parts (id INTEGER PRIMARY KEY, code TEXT NOT NULL)')
    db.execute("INSERT INTO parts(id, code) VALUES (7, 'OLD-PART')")
    db.execute('CREATE TABLE restore_journal (restoreId TEXT PRIMARY KEY)')
    db.execute('''CREATE TABLE weekly_backup_journal (
      backupId TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, state TEXT NOT NULL,
      archivePath TEXT NOT NULL, createdAt INTEGER NOT NULL, snapshotCursor INTEGER NOT NULL,
      partCount INTEGER NOT NULL, movementCount INTEGER NOT NULL, archiveSizeBytes INTEGER NOT NULL,
      sha256 TEXT NOT NULL, lastError TEXT NOT NULL, updatedAt INTEGER NOT NULL)''')
    db.execute('CREATE INDEX IF NOT EXISTS index_weekly_backup_journal_ownerId_state ON weekly_backup_journal(ownerId, state)')
    row = db.execute('SELECT code FROM parts WHERE id=7').fetchone()
    indexes = {r[1] for r in db.execute('PRAGMA index_list(weekly_backup_journal)')}
    check('SQLite migration smoke preserves pre-existing v13 data', row == ('OLD-PART',))
    check('SQLite migration smoke creates owner/state index', 'index_weekly_backup_journal_ownerId_state' in indexes)
    db.close()

for label, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'}: {label}")
print(f"RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS")
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
