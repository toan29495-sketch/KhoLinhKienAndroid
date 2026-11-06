#!/usr/bin/env python3
"""Static policy checks for safe delete/stock conflict decisions."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
PLANNER = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncConflictResolutionPlanner.kt').read_text()
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
UI = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
checks = {
    'only metadata upsert exposes keep-local/server choices': '"PART_UPSERT" -> setOf("KEEP_LOCAL", "KEEP_SERVER", "MANUAL_REVIEW")' in PLANNER,
    'delete conflicts are manual-only': '"PART_DELETE", "STOCK_CHANGE" -> setOf("MANUAL_REVIEW")' in PLANNER,
    'unknown operation types default to manual-only': 'else -> setOf("MANUAL_REVIEW")' in PLANNER,
    'DAO reloads blocked operation under owner before decision': 'blockedOperationById(ownerId, operationId)' in DATA and 'target.operationType' in DATA,
    'DAO rejects unsupported conflict decision at persistence boundary': 'decisionKind in SyncConflictResolutionPlanner.allowedDecisions(target.operationType)' in DATA,
    'UI disables keep-local when operation type is not eligible': '"KEEP_LOCAL" in allowedDecisions' in UI,
    'UI disables keep-server when operation type is not eligible': '"KEEP_SERVER" in allowedDecisions' in UI,
    'UI leaves manual review available for delete/stock': '"MANUAL_REVIEW" in allowedDecisions' in UI,
    'UI explains no automatic tombstone resurrection or movement loss': 'Không tự hồi sinh linh kiện' in UI and 'bỏ lịch sử' in UI,
    'existing resolver remains restricted to PART_UPSERT': 'require(preflight.blocker.operationType == "PART_UPSERT")' in (ROOT / 'app/src/main/java/vn/kholinhkien/SyncConflictKeepLocalResolver.kt').read_text(),
}
failed = [k for k,v in checks.items() if not v]
for k,v in checks.items(): print(('PASS' if v else 'FAIL') + ' ' + k)
print(f'RESULT: {len(checks)-len(failed)}/{len(checks)} PASS')
if failed: raise SystemExit(1)
