from pathlib import Path
s = Path("app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt").read_text(encoding="utf-8")
checks = {
    "scalar RPC response triggers authoritative row lookup": 'client.from("sync_operations").select' in s,
    "lookup is scoped to operation and owner": 'eq("operation_id", claimed.operationId)' in s and 'eq("owner_id", ownerId)' in s,
    "exactly one idempotency row required": "operationRows.singleOrNull()" in s,
    "operation identity and type are checked": "rowOperationId == claimed.operationId" in s and "rowEntityType == operation.operationType" in s and "rowEntityId == claimed.entityId" in s,
    "owner and processed timestamp are checked": "rowOwnerId == ownerId" in s and "processedAt != null && processedAt !is JsonNull" in s,
    "result must be JsonObject": 'operationRow["result"] as? JsonObject' in s,
    "native JSON object path remains": "is JsonObject -> responseElement" in s,
    "invalid reconciliation remains blocked": "Bản ghi đối soát không khớp giao dịch hoặc chưa xử lý" in s,
    "does not delete queue rows": "deleteOutbox" not in s,
}
for label, passed in checks.items():
    print(("PASS" if passed else "FAIL") + " - " + label)
print(f"RESULT: {sum(checks.values())}/{len(checks)} PASS")
if not all(checks.values()):
    raise SystemExit(1)
