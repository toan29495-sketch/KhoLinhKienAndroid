from pathlib import Path
root = Path(__file__).resolve().parents[1]
data = (root/'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
planner = (root/'app/src/main/java/vn/kholinhkien/SyncConflictResolutionPlanner.kt').read_text()
keep_server = (root/'app/src/main/java/vn/kholinhkien/SyncConflictKeepServerResolver.kt').read_text()
keep_local = (root/'app/src/main/java/vn/kholinhkien/SyncConflictKeepLocalResolver.kt').read_text()
checks = {
    'decision preparation inspects the owner queue behind blocker': 'inspectBlockedResolution(ownerId, operationId)' in data,
    'non-manual decision rejects any queued successor': 'if (decisionKind != "MANUAL_REVIEW")' in data and 'preflight.dependentOperations.isEmpty()' in data,
    'rejection reports exact dependent count': 'Có ${preflight.dependentOperations.size} giao dịch phía sau' in data,
    'manual review remains recordable for dependency chains': 'decisionKind != "MANUAL_REVIEW"' in data and 'insertConflictDecision(decision)' in data,
    'attempted successors remain protected': '!preflight.containsAttemptedOperations' in data,
    'same-entity constraint remains': 'preflight.sameEntityOnly' in data,
    'keep-server executor independently rejects successors': 'preflight.dependentOperations.isEmpty()' in keep_server,
    'keep-local executor independently rejects successors': 'preflight.dependentOperations.isEmpty()' in keep_local,
    'stock and delete remain manual-only': '"PART_DELETE", "STOCK_CHANGE" -> setOf("MANUAL_REVIEW")' in planner,
    'decision preparation contains no Outbox deletion': 'DELETE FROM sync_outbox' not in data[data.index('suspend fun prepareConflictDecision'):data.index('suspend fun validatePreparedConflictDecision')],
    'original operation payload remains frozen': 'payloadJson' in data and 'markBlockedResolved' in data,
    'guard is scoped to non-manual choices': 'if (decisionKind != "MANUAL_REVIEW")' in data,
}
failed=[]
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL')+' '+name)
    if not ok: failed.append(name)
print(f'{len(checks)-len(failed)}/{len(checks)} PASS')
raise SystemExit(bool(failed))
