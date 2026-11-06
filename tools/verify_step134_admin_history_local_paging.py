#!/usr/bin/env python3
"""Step134: bounded Admin history UI pages and owner-scoped machine history checks."""
from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text(encoding='utf-8')
MAIN = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text(encoding='utf-8')

checks = {
    'Admin first page is bounded': 'latestAdminMovementPage(limit: Int)' in DATA and 'ORDER BY time DESC, id DESC LIMIT :limit' in DATA,
    'Admin older page uses strict timestamp plus local-ID keyset': 'time < :beforeTime OR (time = :beforeTime AND id < :beforeId)' in DATA and 'beforeId: Long' in DATA,
    'Admin older page is bounded': 'olderAdminMovementPage(beforeTime: Long, beforeId: Long, limit: Int)' in DATA and 'LIMIT :limit' in DATA,
    'Admin global history no longer has an unbounded allMovements DAO query': 'fun allMovements()' not in DATA,
    'Admin UI stores only loaded pages in a state list': 'mutableStateListOf<Movement>()' in MAIN,
    'Admin UI loads first page on profile/role/refresh change': 'LaunchedEffect(profile.id, isAdmin, adminHistoryRefresh)' in MAIN and 'dao.latestAdminMovementPage(ADMIN_HISTORY_PAGE_SIZE)' in MAIN,
    'Admin UI uses IO dispatcher for database page reads': 'withContext(Dispatchers.IO)' in MAIN and 'dao.olderAdminMovementPage' in MAIN,
    'Admin UI exposes explicit refresh action': 'Text("Làm mới")' in MAIN and 'adminHistoryRefresh++' in MAIN,
    'Opening Admin history tab triggers a fresh first-page read': 'if (isAdmin && tab != 1) adminHistoryRefresh++; tab = 1' in MAIN,
    'Admin UI exposes bounded load-more action': 'Tải thêm $ADMIN_HISTORY_PAGE_SIZE giao dịch' in MAIN,
    'Admin UI reports loaded row count rather than total unbounded count': 'Đã tải ${movements.size} giao dịch' in MAIN,
    'Admin UI deduplicates appended pages by stable sync ID': 'adminHistoryRows.mapTo(HashSet()) { it.syncId }' in MAIN,
    'Admin UI does not append a second page while one is loading': '!adminHistoryLoading && adminHistoryHasMore' in MAIN,
    'Admin UI handles cancellation without swallowing it': 'catch (error: Exception)' in MAIN and 'if (error !is CancellationException)' in MAIN,
    'KTV movement flow remains owner-scoped': 'dao.movementsForOwner(profile.id).collectAsState(initial = emptyList())' in MAIN,
    'Machine history has a dedicated bounded-by-code database query rather than filtering loaded Admin page': 'dao.machineHistoryMovements(if (isAdmin) null else profile.id, code)' in MAIN,
    'Machine history query filters exports and exact machine/store case-insensitively': "type = 'XUẤT' AND lower(machineOrStore) = lower(:machineCode)" in DATA,
    'Machine history query scopes KTV and allows Admin global view': '(:ownerId IS NULL OR ownerId = :ownerId)' in DATA,
    'Machine history results have deterministic tie ordering': 'ORDER BY time DESC, id DESC' in DATA,
    'Admin backup still receives own movement list, not paged global history': 'ToolsScreen(\n                    parts, ownMovements, profile.id' in MAIN,
    'Used QR state still derives only from own movements': 'ownMovements.asSequence().filter { it.type == "XUẤT" }' in MAIN,
}

# Execute the same keyset predicates against SQLite with equal timestamps and two owners.
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE movements(id INTEGER PRIMARY KEY AUTOINCREMENT, syncId TEXT UNIQUE, ownerId TEXT, code TEXT, partName TEXT, type TEXT, amount INTEGER, beforeQty INTEGER, afterQty INTEGER, reason TEXT, machineOrStore TEXT, time INTEGER, ownerName TEXT)')
rows = []
for i in range(1, 1251):
    # Many ties deliberately exercise id DESC as the stable secondary key.
    timestamp = 1_700_000_000_000 + (i // 5)
    owner = 'admin' if i % 3 == 0 else ('ktv-a' if i % 3 == 1 else 'ktv-b')
    machine = 'MACHINE-01' if i % 7 == 0 else 'STORE-02'
    rows.append((f'sync-{i}', owner, f'P{i%31}', f'Part {i%31}', 'XUẤT' if i % 2 == 0 else 'NHẬP', 1, 2, 1, '', machine, timestamp, owner.upper()))
db.executemany('INSERT INTO movements(syncId,ownerId,code,partName,type,amount,beforeQty,afterQty,reason,machineOrStore,time,ownerName) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)', rows)
db.commit()

page_size = 200
page = db.execute('SELECT * FROM movements ORDER BY time DESC, id DESC LIMIT ?', (page_size,)).fetchall()
loaded = list(page)
checks['SQLite first page contains at most 200 rows'] = len(page) == 200
while len(page) == page_size:
    oldest = loaded[-1]
    page = db.execute('SELECT * FROM movements WHERE time < ? OR (time = ? AND id < ?) ORDER BY time DESC, id DESC LIMIT ?', (oldest[11], oldest[11], oldest[0], page_size)).fetchall()
    known = {row[1] for row in loaded}
    loaded.extend(row for row in page if row[1] not in known)
checks['SQLite keyset paging reads all 1,250 rows'] = len(loaded) == 1250
checks['SQLite keyset paging has no duplicate sync IDs'] = len({row[1] for row in loaded}) == 1250
checks['SQLite keyset paging preserves deterministic descending order'] = loaded == sorted(loaded, key=lambda row: (row[11], row[0]), reverse=True)
checks['SQLite keyset paging has no missing rows'] = {row[1] for row in loaded} == {f'sync-{i}' for i in range(1, 1251)}

admin_machine = db.execute("SELECT * FROM movements WHERE type='XUẤT' AND lower(machineOrStore)=lower(?) AND (? IS NULL OR ownerId=?) ORDER BY time DESC,id DESC", ('machine-01', None, None)).fetchall()
ktv_machine = db.execute("SELECT * FROM movements WHERE type='XUẤT' AND lower(machineOrStore)=lower(?) AND (? IS NULL OR ownerId=?) ORDER BY time DESC,id DESC", ('machine-01', 'ktv-a', 'ktv-a')).fetchall()
checks['SQLite Admin machine history returns all matching owners'] = len(admin_machine) > len(ktv_machine) > 0
checks['SQLite KTV machine history returns only its own rows'] = all(row[2] == 'ktv-a' for row in ktv_machine)
checks['SQLite Admin machine history is deterministically sorted'] = admin_machine == sorted(admin_machine, key=lambda row: (row[11], row[0]), reverse=True)

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP134_RESULT={len(checks)-len(failed)}/{len(checks)}')
print(f'SQLITE_PAGING_ROWS={len(loaded)} PAGE_SIZE={page_size} PAGES={(len(loaded)+page_size-1)//page_size}')
print(f'SQLITE_MACHINE_HISTORY_ADMIN={len(admin_machine)} KTV_A={len(ktv_machine)}')
if failed:
    raise SystemExit(1)
