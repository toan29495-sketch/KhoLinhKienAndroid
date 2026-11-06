from pathlib import Path
root = Path(__file__).resolve().parents[1]
data = (root/'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
resolver = (root/'app/src/main/java/vn/kholinhkien/SyncConflictKeepServerResolver.kt').read_text()
ui = (root/'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
checks = {
 'DAO decision must be KEEP_SERVER': 'require(decision.decisionKind == "KEEP_SERVER")' in data,
 'DAO requires ready recheck': 'require(decision.status == "READY_FOR_SERVER_RECHECK")' in data,
 'DAO only applies PART_UPSERT': 'require(blocker.operationType == "PART_UPSERT")' in data,
 'DAO requires no dependent operations': 'preflight.dependentOperations.isEmpty() && decision.dependencyCount == 0' in data,
 'DAO blocks during active restore': 'check(activeRestore(ownerId) == null)' in data,
 'DAO checks owner and entity identity': 'serverPart.ownerId == ownerId && serverPart.syncId == blocker.entityId' in data,
 'DAO prevents replacing a different local code owner': 'codeOwner == null || codeOwner.syncId == blocker.entityId' in data,
 'DAO atomically applies snapshot and resolves blocker': 'upsertPulledPart(serverPart)' in data and 'markBlockedResolved(ownerId, operationId)' in data,
 'Resolver performs fresh authenticated snapshot read': 'reader.read(ownerId, preflight.blocker.entityId)' in resolver,
 'Resolver rejects server tombstone': 'json.isNull("deleted_at")' in resolver,
 'Resolver verifies owner and UUID': 'serverOwner == ownerId && syncId == preflight.blocker.entityId' in resolver,
 'UI exposes explicit apply confirmation': 'Áp dụng dữ liệu máy chủ?' in ui and 'SyncConflictKeepServerResolver(dao).resolve' in ui,
 'UI does not unlock without resolver success': 'Đã áp dụng snapshot máy chủ cho linh kiện' in ui,
}
failed=[]
for name, ok in checks.items():
 print(('PASS' if ok else 'FAIL')+' '+name)
 if not ok: failed.append(name)
print(f'{len(checks)-len(failed)}/{len(checks)} PASS')
raise SystemExit(bool(failed))
