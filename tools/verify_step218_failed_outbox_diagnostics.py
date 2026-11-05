from pathlib import Path
root = Path(__file__).resolve().parents[1]
data = (root / "app/src/main/java/vn/kholinhkien/Data.kt").read_text(encoding="utf-8")
main = (root / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text(encoding="utf-8")
checks = {
 "failed rows query is owner scoped": "failedOutboxDetails(ownerId: String): List<SyncOutbox>" in data and "ownerId = :ownerId AND status = 'FAILED'" in data,
 "failed details ordered oldest first and bounded": "ORDER BY createdAt ASC, rowid ASC LIMIT 10" in data,
 "failed row detail state exists": "var failedOutboxDetails by remember(ownerId)" in main,
 "refresh loads failed details": "dao.failedOutboxDetails(ownerId)" in main,
 "operation ID and entity ID visible": "failed.operationId" in main and "failed.entityId" in main,
 "attempt count and last error visible": "failed.attemptCount" in main and "failed.lastError" in main,
 "payload JSON is not exposed": "failed.payloadJson" not in main,
 "diagnostic is read-only": "failedOutboxDetails.forEach" in main,
}
for label, ok in checks.items():
 print(("PASS" if ok else "FAIL") + " - " + label)
print(f"RESULT: {sum(checks.values())}/{len(checks)} PASS")
if not all(checks.values()): raise SystemExit(1)
