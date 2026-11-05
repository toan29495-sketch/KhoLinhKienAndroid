#!/usr/bin/env python3
"""Step217: expose owner-scoped sync outbox status in Tools & Backup."""
from pathlib import Path
root = Path(__file__).resolve().parent
data = root / "app/src/main/java/vn/kholinhkien/Data.kt"
main = root / "app/src/main/java/vn/kholinhkien/MainActivity.kt"
d = data.read_text(encoding="utf-8")
m = main.read_text(encoding="utf-8")

anchor = '    @Query("SELECT COUNT(*) FROM sync_outbox WHERE ownerId = :ownerId AND status IN (\'PENDING\', \'FAILED\', \'IN_FLIGHT\', \'BLOCKED\')")\n    suspend fun countUnsentOutbox(ownerId: String): Int\n'
if anchor not in d:
    raise SystemExit("Step217: cannot find countUnsentOutbox DAO anchor")
extra = anchor + '''
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE ownerId = :ownerId AND status = 'PENDING'")
    suspend fun countPendingOutbox(ownerId: String): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE ownerId = :ownerId AND status = 'FAILED'")
    suspend fun countFailedOutbox(ownerId: String): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE ownerId = :ownerId AND status = 'IN_FLIGHT'")
    suspend fun countInFlightOutbox(ownerId: String): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE ownerId = :ownerId AND status = 'BLOCKED'")
    suspend fun countBlockedOutbox(ownerId: String): Int

    @Query("SELECT MAX(createdAt) FROM sync_outbox WHERE ownerId = :ownerId AND status = 'SYNCED'")
    suspend fun lastSyncedOutboxAt(ownerId: String): Long?
'''
d = d.replace(anchor, extra, 1)

# Pass DAO to the tools screen and add a refreshable status panel.
call = '                2 -> ToolsScreen(\n                    parts, ownMovements, profile.id, profile.username,'
if call not in m:
    raise SystemExit("Step217: cannot locate ToolsScreen call")
m = m.replace(call, '                2 -> ToolsScreen(\n                    parts, ownMovements, profile.id, profile.username, dao,', 1)

sig = '''fun ToolsScreen(
    parts: List<Part>, movements: List<Movement>, ownerId: String, username: String,
'''
if sig not in m:
    raise SystemExit("Step217: cannot locate ToolsScreen signature")
m = m.replace(sig, '''fun ToolsScreen(
    parts: List<Part>, movements: List<Movement>, ownerId: String, username: String, dao: InventoryDao,
''', 1)

state_anchor = '    var backupBusy by remember { mutableStateOf(false) }\n'
if state_anchor not in m:
    raise SystemExit("Step217: cannot locate ToolsScreen state anchor")
states = state_anchor + '''    var syncStatusLoading by remember(ownerId) { mutableStateOf(false) }
    var syncPending by remember(ownerId) { mutableIntStateOf(0) }
    var syncFailed by remember(ownerId) { mutableIntStateOf(0) }
    var syncInFlight by remember(ownerId) { mutableIntStateOf(0) }
    var syncBlocked by remember(ownerId) { mutableIntStateOf(0) }
    var lastSyncedAt by remember(ownerId) { mutableStateOf<Long?>(null) }
    var syncStatusError by remember(ownerId) { mutableStateOf("") }
    suspend fun refreshSyncStatus() {
        if (syncStatusLoading) return
        syncStatusLoading = true
        syncStatusError = ""
        try {
            withContext(Dispatchers.IO) {
                syncPending = dao.countPendingOutbox(ownerId)
                syncFailed = dao.countFailedOutbox(ownerId)
                syncInFlight = dao.countInFlightOutbox(ownerId)
                syncBlocked = dao.countBlockedOutbox(ownerId)
                lastSyncedAt = dao.lastSyncedOutboxAt(ownerId)
            }
        } catch (error: Exception) {
            syncStatusError = error.message ?: "Không đọc được trạng thái đồng bộ"
        } finally {
            syncStatusLoading = false
        }
    }
    LaunchedEffect(ownerId) { refreshSyncStatus() }
'''
m = m.replace(state_anchor, states, 1)

ui_anchor = '        Text("Công cụ", style = MaterialTheme.typography.titleLarge)\n'
if ui_anchor not in m:
    raise SystemExit("Step217: cannot locate ToolsScreen header")
panel = ui_anchor + '''        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Trạng thái đồng bộ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { refreshSyncStatus() } }, enabled = !syncStatusLoading) {
                        if (syncStatusLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(4.dp)); Text("Làm mới")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Đang chờ"); Text(syncPending.toString())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Gửi thất bại"); Text(syncFailed.toString(), color = if (syncFailed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Đang gửi"); Text(syncInFlight.toString())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Bị khóa / cần đối soát"); Text(syncBlocked.toString(), color = if (syncBlocked > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    "Tổng chưa đồng bộ: ${syncPending + syncFailed + syncInFlight + syncBlocked}",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    lastSyncedAt?.let { "Đồng bộ thành công gần nhất: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it))}" }
                        ?: "Chưa có giao dịch đồng bộ thành công trên thiết bị này",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (syncStatusError.isNotBlank()) Text("Không đọc được trạng thái: $syncStatusError", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("Số liệu lấy trực tiếp từ hàng đợi cục bộ của tài khoản đang đăng nhập. Không tự xóa hoặc bỏ qua giao dịch lỗi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
'''
m = m.replace(ui_anchor, panel, 1)

data.write_text(d, encoding="utf-8")
main.write_text(m, encoding="utf-8")

progress = root / "PROGRESS.md"
if progress.exists():
    log = progress.read_text(encoding="utf-8")
    heading = "## Step217 — Owner-scoped sync status panel (05/11/2026)"
    if heading not in log:
        log += """

## Step217 — Owner-scoped sync status panel (05/11/2026)
- Added live local outbox counts for PENDING, FAILED, IN_FLIGHT, and BLOCKED, scoped to the signed-in owner.
- Added last successful local outbox acknowledgement timestamp and a manual refresh action.
- Status panel is placed at the top of Tools & Backup so restore blockers can be diagnosed before retrying restore.
- The panel is read-only: it does not clear, retry, reclassify, or bypass any outbox operation and does not relax restore safety gates.
- Cloud connectivity/session health is not inferred from local outbox counts; a separate successful server acknowledgement is required.
- APK build and device verification remain pending.
"""
        progress.write_text(log, encoding="utf-8")
print("Step217 sync status panel applied.")
