from pathlib import Path

sender = Path('app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text(encoding='utf-8')
sql = Path('supabase/migrations_pending_reconciliation/202611030001_enforce_inventory_version_cas.sql').read_text(encoding='utf-8')
checks = {
    'RPC acknowledgement requires applied status': 'check(responseStatus == "applied")' in sender,
    'RPC acknowledgement entity must match claimed entity': 'check(responseEntityId.equals(claimed.entityId, ignoreCase = true))' in sender,
    'RPC acknowledgement requires positive server version': 'check(serverVersion != null && serverVersion > 0L)' in sender,
    'outbox completion occurs only after response validation': sender.index('check(responseStatus == "applied")') < sender.index('dao.completeOutboxWithServerVersion'),
    'SQL cached replay returns the original stored result': 'return coalesce(v_existing.result, jsonb_build_object(\'status\', \'already_processed\'))' in sql,
    'SQL exact payload check precedes cached replay': sql.index('v_existing.payload is distinct from p_payload') < sql.index('if v_existing.processed_at is not null then'),
    'stock operation response contains entity id and server version': "'entity_id', v_part.id" in sql and "'server_version', v_part.server_version" in sql,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STATIC_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)
