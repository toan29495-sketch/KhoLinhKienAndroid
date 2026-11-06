from pathlib import Path

sql = Path('supabase/migrations_pending_reconciliation/202611030001_enforce_inventory_version_cas.sql').read_text()
checks = {
    'payload mismatch is checked before cached processed result': sql.index('if v_existing.payload is distinct from p_payload then') < sql.index('if v_existing.processed_at is not null then'),
    'payload mismatch raises stable conflict token': "raise exception 'OPERATION_PAYLOAD_CONFLICT'" in sql,
    'operation id is inserted with owner/type/entity/payload': 'insert into public.sync_operations' in sql and '(operation_id, owner_id, entity_type, entity_id, payload)' in sql,
    'existing operation row is locked for retry serialization': 'where operation_id = p_operation_id\n     for update' in sql,
    'cached result returned only after exact payload validation': sql.index('if v_existing.processed_at is not null then') > sql.index('if v_existing.payload is distinct from p_payload then'),
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STATIC_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)
