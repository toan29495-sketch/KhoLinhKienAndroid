from pathlib import Path
root=Path(__file__).resolve().parents[1]
d=(root/"app/src/main/java/vn/kholinhkien/Data.kt").read_text()
m=(root/"app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text()
checks={
"owner-scoped failed query":"failedOutboxDetails(ownerId: String): List<SyncOutbox>" in d and "ownerId = :ownerId AND status = 'FAILED'" in d,
"ordered and bounded":"ORDER BY createdAt ASC, rowid ASC LIMIT 10" in d,
"details loaded":"dao.failedOutboxDetails(ownerId)" in m,
"operation and entity IDs shown":"failed.operationId" in m and "failed.entityId" in m,
"attempt count and last error shown":"failed.attemptCount" in m and "failed.lastError" in m,
"payload not exposed":"failed.payloadJson" not in m,
}
for k,v in checks.items(): print(("PASS" if v else "FAIL")+" - "+k)
print(f"RESULT: {sum(checks.values())}/{len(checks)} PASS")
if not all(checks.values()): raise SystemExit(1)
