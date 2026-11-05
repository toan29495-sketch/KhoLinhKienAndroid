#!/usr/bin/env python3
"""Step218: expose actionable details for failed sync outbox operations."""
from pathlib import Path
root = Path(__file__).resolve().parent
data = root / "app/src/main/java/vn/kholinhkien/Data.kt"
main = root / "app/src/main/java/vn/kholinhkien/MainActivity.kt"
d = data.read_text(encoding="utf-8")
m = main.read_text(encoding="utf-8")
anchor = '''    @Query("SELECT COUNT(*) FROM sync_outbox WHERE ownerId = :ownerId AND status = 'FAILED'")
    suspend fun countFailedOutbox(ownerId: String): Int
'''
if anchor not in d: raise SystemExit("Step218: failed-count DAO anchor not found")
query = anchor + '''
    @Query("SELECT * FROM sync_outbox WHERE ownerId = :ownerId AND status = 'FAILED' ORDER BY createdAt ASC, rowid ASC LIMIT 10")
    suspend fun failedOutboxDetails(ownerId: String): List<SyncOutbox>
'''
if "suspend fun failedOutboxDetails(ownerId: String)" not in d: d=d.replace(anchor,query,1)
state='    var syncStatusError by remember(ownerId) { mutableStateOf("") }\n'
if state not in m: raise SystemExit("Step218: state anchor not found")
if "var failedOutboxDetails by remember(ownerId)" not in m:
 m=m.replace(state,state+'    var failedOutboxDetails by remember(ownerId) { mutableStateOf<List<SyncOutbox>>(emptyList()) }\n',1)
old='''        try {
            withContext(Dispatchers.IO) {
                syncPending = dao.countPendingOutbox(ownerId)
                syncFailed = dao.countFailedOutbox(ownerId)
                syncInFlight = dao.countInFlightOutbox(ownerId)
                syncBlocked = dao.countBlockedOutbox(ownerId)
                lastSyncedAt = dao.lastSyncedOutboxAt(ownerId)
            }
'''
new='''        try {
            val snapshot = withContext(Dispatchers.IO) {
                listOf(dao.countPendingOutbox(ownerId), dao.countFailedOutbox(ownerId),
                    dao.countInFlightOutbox(ownerId), dao.countBlockedOutbox(ownerId)) to
                    (dao.lastSyncedOutboxAt(ownerId) to dao.failedOutboxDetails(ownerId))
            }
            syncPending = snapshot.first[0]
            syncFailed = snapshot.first[1]
            syncInFlight = snapshot.first[2]
            syncBlocked = snapshot.first[3]
            lastSyncedAt = snapshot.second.first
            failedOutboxDetails = snapshot.second.second
'''
if old in m: m=m.replace(old,new,1)
elif "dao.failedOutboxDetails(ownerId)" not in m: raise SystemExit("Step218: refresh block not found")
ui='''                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Gửi thất bại"); Text(syncFailed.toString(), color = if (syncFailed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
'''
if ui not in m: raise SystemExit("Step218: failed count UI row not found")
detail=ui+'''                if (failedOutboxDetails.isNotEmpty()) {
                    Divider()
                    Text("Chi tiết giao dịch gửi lỗi", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    failedOutboxDetails.forEach { failed ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${failed.operationType} · Mã: ${failed.entityId}", style = MaterialTheme.typography.bodyMedium)
                            Text("Operation ID: ${failed.operationId}", style = MaterialTheme.typography.bodySmall)
                            Text("Số lần thử: ${failed.attemptCount} · Tạo lúc: ${SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(failed.createdAt))}", style = MaterialTheme.typography.bodySmall)
                            Text(failed.lastError.ifBlank { "Không có nội dung lỗi được lưu; cần kiểm tra log đồng bộ." }.take(1000),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Divider()
                    }
                }
'''
m=m.replace(ui,detail,1)
data.write_text(d,encoding="utf-8"); main.write_text(m,encoding="utf-8")
progress=root/"PROGRESS.md"
if progress.exists():
 log=progress.read_text(encoding="utf-8"); heading="## Step218 — Failed outbox diagnostic details (05/11/2026)"
 if heading not in log:
  log+="""\n\n## Step218 — Failed outbox diagnostic details (05/11/2026)
- Added owner-scoped query for up to 10 FAILED outbox rows in enqueue order.
- Added operation type, entity ID, operation ID, attempt count, creation time, and persisted lastError to the read-only diagnostics panel.
- Room reads run on IO; Compose state updates run on main.
- Payload JSON is not exposed. No retry, delete, reclassification, or restore-gate bypass is performed.
- Build and device verification pending.
"""
  progress.write_text(log,encoding="utf-8")
print("Step218 failed outbox diagnostics applied.")
