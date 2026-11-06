from pathlib import Path

sql = Path('supabase/migrations_pending_reconciliation/202611030001_enforce_inventory_version_cas.sql').read_text(encoding='utf-8')
stock = sql.split('else -- STOCK_CHANGE', 1)[1].split('-- Publish complete snapshots', 1)[0]
checks = {
    'expected server version has a bigint local variable': 'v_expected_server_version bigint' in sql,
    'missing version token is rejected before part lock': stock.index("if not (p_payload ? 'expected_server_version')") < stock.index('for update'),
    'null and blank version values normalize to null': "nullif(btrim(p_payload->>'expected_server_version'), '')::bigint" in stock,
    'malformed and out-of-range versions map to stable required token': 'when invalid_text_representation or numeric_value_out_of_range' in stock and "raise exception 'PART_VERSION_REQUIRED'" in stock,
    'null and non-positive versions are rejected explicitly': 'if v_expected_server_version is null or v_expected_server_version <= 0 then' in stock,
    'server comparison is null-safe and uses parsed token': 'if v_part.server_version is distinct from v_expected_server_version then' in stock,
    'version conflict remains permanent in client classifier': '"PART_VERSION_CONFLICT", "PART_DELETED", "PART_VERSION_REQUIRED"' in Path('app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text(encoding='utf-8'),
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STATIC_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)
