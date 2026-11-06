#!/usr/bin/env python3
"""Step181: audit a transactionally consistent, fully-synced weekly snapshot."""
from pathlib import Path
import sqlite3
import tempfile
ROOT = Path(__file__).resolve().parents[1]
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
BACKUP = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
checks = {
    'snapshot capture is a Room transaction': '@Transaction\n    suspend fun captureAutomaticBackupSnapshot' in DATA,
    'snapshot rejects active restore before reads': 'if (activeRestore(ownerId) != null) return null' in DATA,
    'snapshot rejects all unresolved outbox states': "status IN ('PENDING', 'FAILED', 'IN_FLIGHT', 'BLOCKED')" in DATA,
    'snapshot reads owner-scoped parts': 'backupPartsForOwner(ownerId)' in DATA,
    'snapshot reads owner-scoped movements': 'backupMovementsForOwner(ownerId)' in DATA,
    'snapshot reads sync cursor in same transaction': 'val cursor = getSyncCursor(ownerId) ?: 0L' in DATA,
    'snapshot rechecks restore fence after reads': DATA.count('if (activeRestore(ownerId) != null') >= 2,
    'snapshot rechecks outbox after reads': DATA.count('countUnsentOutbox(ownerId) != 0') >= 2,
    'part pull application is transactional and restore-fenced': '@Transaction\n    suspend fun applyPulledPart' in DATA and 'if (activeRestore(ownerId) != null) return false' in DATA,
    'movement pull application is transactional and restore-fenced': '@Transaction\n    suspend fun applyPulledMovement' in DATA and 'if (activeRestore(ownerId) != null) return false' in DATA,
    'local stock mutation is transactional': '@Transaction\n    suspend fun changeStock' in DATA,
    'backup refuses unsafe snapshot before archive creation': 'captureAutomaticBackupSnapshot(ownerId)' in BACKUP and 'archive.delete() // PREPARING' in BACKUP,
    'ZIP parts come from captured snapshot only': 'parts = snapshot.parts' in BACKUP,
    'ZIP movements come from captured snapshot only': 'movements = snapshot.movements' in BACKUP,
    'manifest cursor comes from captured snapshot': 'snapshotCursor = snapshot.syncCursor' in BACKUP,
    'manifest counts come from captured snapshot': 'snapshot.parts.size' in BACKUP and 'snapshot.movements.size' in BACKUP,
}
# SQLite WAL smoke: a reader keeps its original snapshot while a second
# connection commits a concurrent write.
with tempfile.NamedTemporaryFile(suffix=".sqlite") as db_file:
    reader = sqlite3.connect(db_file.name, timeout=2)
    writer = sqlite3.connect(db_file.name, timeout=2)
    reader.execute('PRAGMA journal_mode=WAL')
    reader.execute('CREATE TABLE inventory(value INTEGER)')
    reader.execute('INSERT INTO inventory VALUES (1)')
    reader.commit()
    reader.execute('BEGIN')
    first = reader.execute('SELECT value FROM inventory').fetchone()[0]
    writer.execute('UPDATE inventory SET value=2')
    writer.commit()
    second = reader.execute('SELECT value FROM inventory').fetchone()[0]
    reader.commit()
    third = reader.execute('SELECT value FROM inventory').fetchone()[0]
    reader.close()
    writer.close()
checks['SQLite transaction reads a stable inventory view'] = first == 1 and second == 1 and third == 2
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'RESULT: {sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()):
    raise SystemExit(1)
