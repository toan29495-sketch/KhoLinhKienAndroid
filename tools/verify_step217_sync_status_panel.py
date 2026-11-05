from pathlib import Path
root = Path(__file__).resolve().parents[1]
data = (root / "app/src/main/java/vn/kholinhkien/Data.kt").read_text(encoding="utf-8")
main = (root / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text(encoding="utf-8")
checks = {
 "owner-scoped pending count": "countPendingOutbox(ownerId: String)" in data and "status = 'PENDING'" in data,
 "owner-scoped failed count": "countFailedOutbox(ownerId: String)" in data,
 "owner-scoped in-flight count": "countInFlightOutbox(ownerId: String)" in data,
 "owner-scoped blocked count": "countBlockedOutbox(ownerId: String)" in data,
 "last successful acknowledgement timestamp": "lastSyncedOutboxAt(ownerId: String)" in data,
 "DAO passed to ToolsScreen": "profile.username, dao," in main and "username: String, dao: InventoryDao" in main,
 "refreshable status panel visible in tools": "Trạng thái đồng bộ" in main and "onClick = { scope.launch { refreshSyncStatus() } }" in main,
 "restore safety gate unchanged": "restoreLocked = restoreLocked" in main,
}
for label, ok in checks.items():
 print(("PASS" if ok else "FAIL") + " - " + label)
print(f"RESULT: {sum(checks.values())}/{len(checks)} PASS")
if not all(checks.values()): raise SystemExit(1)
