#!/usr/bin/env python3
"""Verify legacy absolute photo files cannot cross owner boundaries."""
from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text(encoding='utf-8')
SENDER = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text(encoding='utf-8')
BACKUP = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text(encoding='utf-8')
PREVIEW = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text(encoding='utf-8')
checks = []
def check(name, ok): checks.append((name, bool(ok)))

check('DAO proves an owner-local reference exists', 'EXISTS(SELECT 1 FROM parts WHERE ownerId = :ownerId AND photoPath = :photoPath)' in DATA and 'EXISTS(SELECT 1 FROM sync_outbox WHERE ownerId = :ownerId AND instr(payloadJson, :photoPath) > 0)' in DATA)
check('DAO rejects foreign part references', 'NOT EXISTS(SELECT 1 FROM parts WHERE ownerId <> :ownerId AND photoPath = :photoPath)' in DATA)
check('DAO rejects foreign outbox references', 'NOT EXISTS(SELECT 1 FROM sync_outbox WHERE ownerId <> :ownerId AND instr(payloadJson, :photoPath) > 0)' in DATA)
check('Sender gates unscoped legacy pending path with DAO ownership proof', 'if (isLegacyPending)' in SENDER and 'dao.isLocalPhotoReferencedOnlyByOwner(ownerId, canonicalFile.path)' in SENDER)
check('Sender still requires owner-scoped directory for new pending photos', 'canonicalFile.parentFile == ownerPendingDir' in SENDER)
check('Backup validation is suspend and checks owner exclusivity', 'private suspend fun validateLocalPhotoFile' in BACKUP and 'AppDb.get(context).dao().isLocalPhotoReferencedOnlyByOwner(ownerId, canonicalSource.path)' in BACKUP)
check('Backup rejects local files outside app-private storage', 'canonicalSource.path.startsWith(privateRoot.path + File.separator)' in BACKUP)
check('UI preview continues to reject unscoped legacy pending photos', 'Ảnh chờ đồng bộ không thuộc tài khoản đang đăng nhập' in PREVIEW)

# Exercise the same ownership predicate against a minimal Room-equivalent schema.
con = sqlite3.connect(':memory:')
con.executescript('''
CREATE TABLE parts(ownerId TEXT, photoPath TEXT);
CREATE TABLE sync_outbox(ownerId TEXT, payloadJson TEXT);
''')
sql = '''SELECT (
 (EXISTS(SELECT 1 FROM parts WHERE ownerId = ? AND photoPath = ?)
  OR EXISTS(SELECT 1 FROM sync_outbox WHERE ownerId = ? AND instr(payloadJson, ?) > 0))
 AND NOT EXISTS(SELECT 1 FROM parts WHERE ownerId <> ? AND photoPath = ?)
 AND NOT EXISTS(SELECT 1 FROM sync_outbox WHERE ownerId <> ? AND instr(payloadJson, ?) > 0)
)'''
def owned(owner, path):
    return bool(con.execute(sql, (owner,path,owner,path,owner,path,owner,path)).fetchone()[0])

con.execute('INSERT INTO parts VALUES(?,?)', ('A','/files/pending_1.jpg'))
check('SQLite: legacy photo referenced only by A is allowed for A', owned('A','/files/pending_1.jpg'))
check('SQLite: same legacy photo is denied for B without an own reference', not owned('B','/files/pending_1.jpg'))
con.execute('INSERT INTO parts VALUES(?,?)', ('B','/files/pending_1.jpg'))
check('SQLite: shared part reference is denied for both owners', not owned('A','/files/pending_1.jpg') and not owned('B','/files/pending_1.jpg'))
con.execute('DELETE FROM parts WHERE ownerId=?', ('B',))
con.execute('INSERT INTO sync_outbox VALUES(?,?)', ('B','{"photo_path":"/files/pending_1.jpg"}'))
check('SQLite: foreign outbox payload blocks A even if only A part references file', not owned('A','/files/pending_1.jpg'))
con.execute('DELETE FROM sync_outbox')
con.execute('DELETE FROM parts')
con.execute('INSERT INTO sync_outbox VALUES(?,?)', ('A','{"photo_path":"/files/pending_2.jpg"}'))
check('SQLite: owner-only outbox reference supports retry for A', owned('A','/files/pending_2.jpg'))
check('SQLite: unreferenced legacy file is denied', not owned('A','/files/orphan.jpg'))

for name, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'}: {name}")
print(f'RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
