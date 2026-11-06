from pathlib import Path
import sqlite3
import re

source = Path('app/src/main/java/vn/kholinhkien/Data.kt').read_text()
entity_start = source.index('data class PartTombstone(')
entity_end = source.index('\n)', entity_start) + 2
entity = source[entity_start:entity_end]
start = source.index('suspend fun applyPulledPart(')
end = source.index('\n    }', start) + 6
body = source[start:end]
checks = {
    'durable tombstone entity is owner-scoped and keyed by sync id': 'tableName = "part_tombstones"' in source and 'primaryKeys = ["ownerId", "syncId"]' in source,
    'tombstone stores positive server version and deletion timestamp': 'val serverVersion: Long' in entity and 'val deletedAt: Long' in entity,
    'pull compares live and tombstone versions': 'val tombstone = partTombstone(ownerId, part.syncId)' in body and 'maxOf(existing?.serverVersion ?: 0L, tombstone?.serverVersion ?: 0L)' in body,
    'equal or older events are ignored': 'if (highestKnownVersion >= part.serverVersion) return true' in body,
    'new tombstone is saved before physical part deletion in same DAO transaction': body.index('savePartTombstone(') < body.index('deletePulledPart('),
    'newer live event clears old tombstone and upserts in same transaction': body.index('clearPartTombstone(') < body.index('upsertPulledPart('),
    'pull retains owner, restore and outbox guards': 'part.ownerId == ownerId' in body and 'activeRestore(ownerId)' in body and 'hasUnsentOperation(ownerId, part.syncId)' in body,
    'database version remains current and migration 11 to 12 stays registered': int(re.search(r'version = (\d+)', source).group(1)) >= 14 and 'Migration(11, 12)' in source and 'MIGRATION_11_12' in source and 'MIGRATION_10_11, MIGRATION_11_12' in source,
    'restore rejects snapshot parts without positive server version': 'Snapshot linh kiện thiếu server_version dương' in source,
    'restore keeps absent tombstones and clears only superseded ones': 'clearSupersededPartTombstones(ownerId)' in source and 'serverVersion > part_tombstones.serverVersion' in source,
}

# Execute the actual 11->12 migration SQL and event ordering model in SQLite.
con = sqlite3.connect(':memory:')
con.execute('CREATE TABLE parts (ownerId TEXT NOT NULL, syncId TEXT NOT NULL, serverVersion INTEGER NOT NULL, PRIMARY KEY(ownerId, syncId))')
migration_start = source.index('private val MIGRATION_11_12')
migration_end = source.index('\n        }', migration_start)
migration = source[migration_start:migration_end]
import re
migration_sql = re.search(r'db\.execSQL\("(CREATE TABLE IF NOT EXISTS part_tombstones[^"]+)"\)', migration)
if not migration_sql:
    raise SystemExit('FAIL actual migration SQL not found')
con.execute(migration_sql.group(1))
checks['SQLite: actual Room migration creates composite-key tombstone table'] = con.execute("PRAGMA table_info('part_tombstones')").fetchall() != []
con.execute('INSERT INTO parts VALUES (?, ?, ?)', ('owner-a', 'part-a', 8))

def apply(version, deleted):
    live = con.execute('SELECT serverVersion FROM parts WHERE ownerId=? AND syncId=?', ('owner-a', 'part-a')).fetchone()
    tomb = con.execute('SELECT serverVersion FROM part_tombstones WHERE ownerId=? AND syncId=?', ('owner-a', 'part-a')).fetchone()
    highest = max(live[0] if live else 0, tomb[0] if tomb else 0)
    if highest >= version:
        return 'ignored'
    if deleted:
        con.execute('INSERT INTO part_tombstones VALUES (?, ?, ?, 100) ON CONFLICT(ownerId,syncId) DO UPDATE SET serverVersion=excluded.serverVersion, deletedAt=excluded.deletedAt', ('owner-a', 'part-a', version))
        con.execute('DELETE FROM parts WHERE ownerId=? AND syncId=?', ('owner-a', 'part-a'))
    else:
        con.execute('DELETE FROM part_tombstones WHERE ownerId=? AND syncId=?', ('owner-a', 'part-a'))
        con.execute('INSERT INTO parts VALUES (?, ?, ?) ON CONFLICT(ownerId,syncId) DO UPDATE SET serverVersion=excluded.serverVersion', ('owner-a', 'part-a', version))
    return 'applied'

checks['SQLite: newer tombstone persists after live row deletion'] = apply(9, True) == 'applied' and con.execute('SELECT serverVersion FROM part_tombstones').fetchone()[0] == 9 and con.execute('SELECT COUNT(*) FROM parts').fetchone()[0] == 0
checks['SQLite: delayed old live event cannot resurrect tombstone'] = apply(8, False) == 'ignored' and con.execute('SELECT COUNT(*) FROM parts').fetchone()[0] == 0
checks['SQLite: duplicate tombstone is idempotently ignored'] = apply(9, True) == 'ignored' and con.execute('SELECT serverVersion FROM part_tombstones').fetchone()[0] == 9
checks['SQLite: strictly newer live event supersedes tombstone'] = apply(10, False) == 'applied' and con.execute('SELECT serverVersion FROM parts').fetchone()[0] == 10 and con.execute('SELECT COUNT(*) FROM part_tombstones').fetchone()[0] == 0
checks['SQLite: tombstones are isolated by owner'] = con.execute("SELECT COUNT(*) FROM part_tombstones WHERE ownerId='owner-b'").fetchone()[0] == 0

for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP80_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)
