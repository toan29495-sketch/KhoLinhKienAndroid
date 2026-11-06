from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BACKUP = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
UI = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
READER = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudSnapshotReader.kt').read_text()
PULL = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text()
SQL = (ROOT / 'supabase/migrations_pending_reconciliation/202611030013_restore_preserve_deleted_part_history.sql').read_text()

checks = []
def check(label, condition):
    checks.append((label, bool(condition)))

check('backup serializes every supplied movement', 'movements.forEach { m ->' in BACKUP)
check('backup stores movement code and part name independently', '.put("code", m.code).put("partName", m.partName)' in BACKUP)
check('backup manifest records full movement count', '.put("movementCount", movements.size)' in BACKUP)
check('delete UI explicitly retains movement history', 'Lịch sử giao dịch vẫn được giữ' in UI)
check('Room validates movement code without requiring live part row', 'require(code.isNotEmpty()) { "Lịch sử thiếu mã linh kiện" }' in DATA)
check('Room no longer rejects movement absent from current parts catalog', 'require(code in partCodes)' not in DATA)
check('Room still validates movement transaction arithmetic', 'Lịch sử nhập kho không khớp số lượng trước/sau' in DATA and 'Lịch sử xuất kho không khớp số lượng trước/sau' in DATA)
check('Room still rejects duplicate movement sync IDs', 'require(movementIds.add(syncId))' in DATA)
check('migration makes historical movement relation nullable', 'alter column part_id drop not null' in SQL.lower())
check('migration retains owner authentication and active-profile checks', 'auth.uid()' in SQL and 'public.is_active_profile()' in SQL)
check('migration explicitly revokes anon/public access before authenticated grant', 'from public, anon, authenticated' in SQL and 'to authenticated' in SQL)
check('server no longer requires every movement code in restored parts', 'not exists (' not in SQL[SQL.index('-- Movement history is independent'):SQL.index("then raise exception 'INVALID_RESTORE_MOVEMENT'")])
check('server inserts movement rows with a LEFT JOIN', 'left join public.parts p on p.owner_id = v_owner and p.code = x.code' in SQL.lower())
check('server does not inner-join away historical orphan movements', '\n      join public.parts p on p.owner_id = v_owner and p.code = x.code' not in SQL.lower())
check('server retains movement code and name when part relation is null', 'x.sync_id, v_owner, p.id, x.code, x.part_name, x.type' in SQL)
check('server still checks movement arithmetic and transaction types', "coalesce(x.type, '') not in ('NHẬP', 'XUẤT', 'KIỂM KÊ', 'ĐẦU KỲ')" in SQL and 'x.after_qty::bigint <> x.before_qty::bigint + x.amount::bigint' in SQL)
check('server still checks duplicate movement IDs', 'DUPLICATE_RESTORE_MOVEMENT_ID' in SQL)
check('server keeps restore commit scoped to authenticated owner', 'delete from public.movements where owner_id = v_owner' in SQL and 'delete from public.parts where owner_id = v_owner' in SQL)
check('snapshot reader verifies exact movement row count', 'require(movementRows == journal.movementCount)' in READER)
check('pull parser maps movement from its own code/name fields', 'code = requiredString("code"), partName = requiredString("part_name")' in PULL and 'part_id' not in PULL[PULL.index('private fun kotlinx.serialization.json.JsonObject.toLocalMovement'):])

failed = [label for label, ok in checks if not ok]
for label, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' ' + label)
print(f'RESULT: {len(checks)-len(failed)}/{len(checks)} passed')
if failed:
    raise SystemExit(1)
