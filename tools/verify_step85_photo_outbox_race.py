from pathlib import Path
root = Path(__file__).resolve().parents[1]
data = (root / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
sender = (root / 'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text()
start = data.index('suspend fun completePhotoOutbox(')
end = data.index('\n    }', start) + 6
body = data[start:end]
checks = {
    'photo completion validates exact owner/entity/type/in-flight row': 'operation.ownerId == ownerId' in body and 'operation.entityId == entityId' in body and 'operation.operationType == "PART_UPSERT"' in body and 'operation.status == "IN_FLIGHT"' in body,
    'photo completion does not require old path to remain current': 'replaceLocalPhotoWithCloudPath(ownerId, entityId, localPath, remotePath)' in body and 'check(updated == 1)' not in body,
    'newer local photo is not overwritten': 'val currentPart = partBySyncId(ownerId, entityId)' in body and 'if (currentPart != null)' in body,
    'older upload can complete after local part deletion only with queued delete': 'laterDelete' in body and 'it.operationType == "PART_DELETE"' in body,
    'server version is still validated and recorded for live part': 'serverVersion > 0L' in body and 'updatePartServerVersion(ownerId, entityId, serverVersion) == 1' in body,
    'later queued mutations are rebased sequentially': 'queuedPartOperations(ownerId, entityId).forEach' in body and 'Math.addExact(nextExpectedVersion, 1L)' in body,
    'exact in-flight operation is acknowledged after safe reconciliation': 'markOutboxSynced(operationId) == 1' in body,
    'local photo deletion still waits until no other pending payload references it': 'hasPendingPhotoReference(ownerId, path, claimed.operationId)' in sender,
    'no cloud Storage object deletion is attempted': '.storage.from("part-images").remove' not in sender,
}

def reconcile(part_exists, current_photo, uploaded_photo, has_later_delete=False):
    if part_exists:
        return current_photo if current_photo != uploaded_photo else 'cloud:' + uploaded_photo
    if has_later_delete:
        return None
    raise ValueError('missing part without queued delete')
checks['model: old upload leaves newer photo untouched'] = reconcile(True, '/local/new.jpg', '/local/old.jpg') == '/local/new.jpg'
checks['model: current photo path is promoted to cloud path'] = reconcile(True, '/local/old.jpg', '/local/old.jpg') == 'cloud:/local/old.jpg'
checks['model: delete-after-upsert sequence may acknowledge old upload'] = reconcile(False, None, '/local/old.jpg', True) is None
try:
    reconcile(False, None, '/local/old.jpg', False)
    checks['model: missing part without delete fails closed'] = False
except ValueError:
    checks['model: missing part without delete fails closed'] = True
for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP85_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()): raise SystemExit(1)
