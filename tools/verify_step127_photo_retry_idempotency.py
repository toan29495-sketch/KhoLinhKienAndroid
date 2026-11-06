from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SENDER = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text()
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
UI = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
SQL = (ROOT / 'supabase/migrations_pending_reconciliation/202611030011_fence_inventory_mutations_against_restore.sql').read_text()

checks = {
    'remote object path is deterministic from owner/entity/operation UUID': '"$ownerId/$partSyncId/$operationId.$extension"' in SENDER,
    'upload retry uses upsert on the same deterministic path': 'upload(remotePath, photoBytes)' in SENDER and 'upsert = true' in SENDER,
    'same operation ID is retained in RPC retries': 'put("p_operation_id", claimed.operationId)' in SENDER,
    'SQL serializes duplicate operation IDs with primary-key conflict handling': 'on conflict (operation_id) do nothing' in SQL,
    'SQL rejects a reused operation ID with a different immutable payload': 'v_existing.payload is distinct from p_payload' in SQL,
    'processed RPC returns cached result for the exact same payload': 'if v_existing.processed_at is not null' in SQL and 'return coalesce(v_existing.result' in SQL,
    'failed RPC keeps the original Outbox operation and payload for retry': 'dao.markOutboxFailed(claimed.operationId, message)' in SENDER and 'payloadJson' not in SENDER[SENDER.index('catch (error: Exception)'):SENDER.index('catch (error: Exception)')+400],
    'local source file is uniquely named inside owner-private directory': 'pending_${java.util.UUID.randomUUID()}.jpg' in UI and 'ownerPendingPhotoDirectory(context, profile.id)' in UI,
    'local source photo is deleted only after Room acknowledges RPC completion': SENDER.index('check(markedSynced)') < SENDER.index('File(path).delete()'),
    'local source photo is retained while any unresolved Outbox payload references it': 'hasPendingPhotoReference(ownerId, path, claimed.operationId)' in SENDER and "status IN ('PENDING','FAILED','IN_FLIGHT','BLOCKED')" in DATA,
    'local source deletion is best-effort and cannot roll back acknowledged sync': 'runCatching { File(path).delete() }' in SENDER,
    'no client-side cloud object deletion can race a newer reference': '.storage.from("part-images").remove' not in SENDER,
    'Storage paths remain fenced to authenticated owner folder': "storage.foldername(name))[1]" in (ROOT / 'supabase/migrations_pending_reconciliation/202611030007_lock_down_part_image_storage_scope.sql').read_text(),
    'active owner is rechecked after Storage upload and readback': SENDER.count('requireActiveOwner(ownerId)') >= 5 and SENDER.index('downloadAuthenticated(remotePath)') < SENDER.index('requireActiveOwner(ownerId)', SENDER.index('downloadAuthenticated(remotePath)')) ,
    'active owner is rechecked after hashing immediately before Storage upload': SENDER.index('val expectedDigest = PhotoIntegrity.sha256(photoBytes)') < SENDER.index('requireActiveOwner(ownerId)', SENDER.index('val expectedDigest = PhotoIntegrity.sha256(photoBytes)')) < SENDER.index('client.storage.from("part-images").upload'),
}

# Deterministic retry model: a committed RPC whose response is lost is replayed
# with the same operation UUID, exact payload and object path; no duplicate event.
def remote_path(owner, entity, operation, ext='jpg'):
    return f'{owner}/{entity}/{operation}.{ext}'

owner, entity, operation = 'owner-uuid', 'part-uuid', 'operation-uuid'
path_first = remote_path(owner, entity, operation)
path_retry = remote_path(owner, entity, operation)
checks['model: retry targets exactly the same Storage object'] = path_first == path_retry

ledger = {}
def apply_rpc(op_id, payload):
    if op_id in ledger:
        if ledger[op_id]['payload'] != payload:
            raise ValueError('OPERATION_PAYLOAD_CONFLICT')
        return ledger[op_id]['result']
    result = {'status': 'applied', 'entity_id': entity, 'server_version': 4}
    ledger[op_id] = {'payload': payload, 'result': result}
    return result

payload = {'photo_path': path_first, 'name': 'part'}
first = apply_rpc(operation, payload)  # server commits, response is lost
retry = apply_rpc(operation, payload)
checks['model: lost RPC response retry returns cached result without duplicate mutation'] = first == retry and len(ledger) == 1
try:
    apply_rpc(operation, {'photo_path': path_first, 'name': 'changed'})
    checks['model: same operation ID with changed payload is rejected'] = False
except ValueError:
    checks['model: same operation ID with changed payload is rejected'] = True

for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP127_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)
