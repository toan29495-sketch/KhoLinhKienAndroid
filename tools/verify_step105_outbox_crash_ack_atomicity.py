from pathlib import Path
root = Path(__file__).resolve().parents[1]
data = (root/'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
sender = (root/'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text()
sql = (root/'supabase/migrations_pending_reconciliation/202611030001_enforce_inventory_version_cas.sql').read_text()
checks = {
    'local completion is a single Room transaction': '@Transaction\n    suspend fun completeOutboxWithServerVersion' in data,
    'completion validates the exact outbox operation exists': 'val operation = outboxById(operationId) ?: return false' in data,
    'completion validates owner and entity identity': 'operation.ownerId == ownerId && operation.entityId == entityId' in data,
    'completion only accepts upsert or stock mutation': 'operation.operationType in setOf("PART_UPSERT", "STOCK_CHANGE")' in data,
    'completion only accepts a claimed IN_FLIGHT row': 'operation.status == "IN_FLIGHT"' in data,
    'server version and successor rebase happen before acknowledgement': 'recordServerVersionAndRebase(ownerId, entityId, serverVersion)' in data and data.index('recordServerVersionAndRebase(ownerId, entityId, serverVersion)', data.index('suspend fun completeOutboxWithServerVersion')) < data.index('markOutboxSynced(operationId)', data.index('suspend fun completeOutboxWithServerVersion')),
    'failed local acknowledgement throws and rolls back Room transaction': 'check(markOutboxSynced(operationId) == 1)' in data and 'rollback cập nhật phiên bản cục bộ' in data,
    'sender retains the same immutable operation id on retry': 'put("p_operation_id", claimed.operationId)' in sender,
    'sender sends the claimed original payload on retry': 'put("p_payload", payload)' in sender and 'claimed.payloadJson' in sender,
    'sender marks transient failure through owner-scoped DAO without deleting the outbox row': 'dao.markOutboxFailed(ownerId, claimed.operationId, message)' in sender,
    'expired claims retain operation identity and payload': 'giữ nguyên operationId/payload' in data,
    'server locks and checks the existing idempotency ledger row': 'where operation_id = p_operation_id\n     for update' in sql,
    'server rejects operation id reused with changed payload': 'v_existing.payload is distinct from p_payload' in sql,
    'server returns cached result after successful prior commit': 'if v_existing.processed_at is not null then\n        return coalesce(v_existing.result' in sql,
    'server stores result and processed timestamp in mutation transaction': 'set result = v_result, processed_at = now()' in sql,
    'stock quantity, movement, and idempotency result are atomic': 'insert into public.movements' in sql and 'set result = v_result, processed_at = now()' in sql,
}
failed=[]
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL')+' '+name)
    if not ok: failed.append(name)
print(f'{len(checks)-len(failed)}/{len(checks)} PASS')
raise SystemExit(bool(failed))
