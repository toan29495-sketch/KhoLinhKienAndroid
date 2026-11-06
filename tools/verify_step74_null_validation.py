from pathlib import Path

sql = Path('supabase/migrations_pending_reconciliation/202611030001_enforce_inventory_version_cas.sql').read_text()
checks = {
    'null entity type is explicitly rejected': "if p_entity_type is null or p_entity_type not in ('PART_UPSERT', 'PART_DELETE', 'STOCK_CHANGE') then" in sql,
    'unsupported entity type has a stable error': "raise exception 'UNSUPPORTED_OPERATION_TYPE'" in sql,
    'null movement type is explicitly rejected': "if v_type is null or v_type not in ('NHẬP', 'XUẤT', 'KIỂM KÊ', 'ĐẦU KỲ') or v_amount is null" in sql,
    'invalid movement type maps to stable invalid payload error': "raise exception 'INVALID_STOCK_CHANGE'" in sql,
    'payload equality remains before cached result': sql.index('if v_existing.payload is distinct from p_payload then') < sql.index('if v_existing.processed_at is not null then'),
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STATIC_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)
