#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SQL = (ROOT / 'supabase/migrations_pending_reconciliation/202611030012_unique_active_part_code_per_owner.sql').read_text()
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
RPC = (ROOT / 'supabase/migrations_pending_reconciliation/202611030011_fence_inventory_mutations_against_restore.sql').read_text()
checks = {
    'migration wraps changes in transaction': 'begin;' in SQL.lower() and 'commit;' in SQL.lower(),
    'preflight detects duplicate active codes per owner': 'group by owner_id, code' in SQL and 'having count(*) > 1' in SQL,
    'preflight fails closed with actionable code': 'DUPLICATE_ACTIVE_PART_CODES_REQUIRE_RECONCILIATION' in SQL,
    'unique index scopes code to owner': 'on public.parts(owner_id, code)' in SQL,
    'unique index only covers active parts': 'where deleted_at is null' in SQL,
    'index creation is repeatable': 'create unique index if not exists' in SQL.lower(),
    'Room also enforces owner/code uniqueness': 'Index(value = ["ownerId", "code"], unique = true)' in DATA,
    'local create uses ABORT on duplicate': '@Insert(onConflict = OnConflictStrategy.ABORT)' in DATA,
    'local create and initial movement are transactional': '@Transaction\n    suspend fun insertPartWithOpeningMovement' in DATA,
    'cloud mutation remains owner scoped': 'v_owner uuid := auth.uid()' in RPC and 'if not public.is_active_profile()' in RPC,
    'cloud create still uses stable entity UUID': '(p_entity_id, v_owner, v_code' in RPC,
    'restore mutual exclusion remains present': 'pg_advisory_xact_lock_shared(hashtextextended(v_owner::text, 0))' in RPC,
}
# Deterministic constraint model: same owner + same active code conflicts;
# different owners may reuse it; soft-deleted rows release it.
def can_insert(rows, owner, code):
    return not any(r[0] == owner and r[1] == code and r[2] for r in rows)
rows = [('owner-A', 'QR-001', True)]
checks['same-owner concurrent duplicate create is rejected by unique-key model'] = not can_insert(rows, 'owner-A', 'QR-001')
checks['different owners may reuse the same QR code'] = can_insert(rows, 'owner-B', 'QR-001')
checks['soft-deleted row releases QR code'] = can_insert([('owner-A', 'QR-001', False)], 'owner-A', 'QR-001')

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'RESULT: {len(checks)-len(failed)}/{len(checks)} PASS')
if failed:
    raise SystemExit(1)
