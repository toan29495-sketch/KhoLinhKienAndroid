#!/usr/bin/env python3
"""Step222: owner-scoped manual retry trigger for the inventory outbox."""
from pathlib import Path
import zipfile, tempfile, shutil

archive = Path("Step208-source.zip")
if not archive.is_file():
    raise SystemExit("Missing Step208-source.zip")
with tempfile.TemporaryDirectory() as td:
    root = Path(td)
    with zipfile.ZipFile(archive) as z:
        z.extractall(root)
    project = root / "KhoLinhKienAndroid"
    sync = project / "app/src/main/java/vn/kholinhkien/SyncWork.kt"
    main = project / "app/src/main/java/vn/kholinhkien/MainActivity.kt"
    if not sync.is_file() or not main.is_file():
        raise SystemExit("Expected source files are missing")

    s = sync.read_text()
    anchor = '''    fun schedule(context: Context, ownerId: String, isAdmin: Boolean = false) {'''
    addition = '''    /**
     * Explicit user-triggered retry. It uses a separate unique work name so a
     * periodic/automatic request waiting in backoff cannot swallow the tap.
     * The sender still enforces owner fencing, queue order, idempotency and
     * the process-wide mutex before touching any outbox row.
     */
    fun scheduleManual(context: Context, ownerId: String) {
        if (!Regex("^[0-9a-fA-F-]{36}$").matches(ownerId)) return
        val manager = WorkManager.getInstance(context.applicationContext)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncOutboxWorker>()
            .setInputData(androidx.work.workDataOf(SyncOutboxWorker.KEY_OWNER_ID to ownerId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniqueWork("inventory-sync-manual-$ownerId", ExistingWorkPolicy.KEEP, request)
    }

'''
    if "fun scheduleManual(context: Context, ownerId: String)" not in s:
        if s.count(anchor) != 1:
            raise SystemExit("Could not find unique SyncWorkScheduler.schedule anchor")
        s = s.replace(anchor, addition + anchor, 1)
    sync.write_text(s)

    m = main.read_text()
    anchor = '''        Text("Công cụ", style = MaterialTheme.typography.titleLarge)'''
    ui = '''        Text("Công cụ", style = MaterialTheme.typography.titleLarge)
        var manualSyncBusy by remember(ownerId) { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Đồng bộ kho", style = MaterialTheme.typography.titleMedium)
                Text("Chạy lại hàng đợi của đúng tài khoản đang đăng nhập. Giao dịch lỗi sẽ được đối soát bằng operation ID cũ; các giao dịch phía sau chỉ chạy theo đúng thứ tự.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        if (!manualSyncBusy) scope.launch {
                            manualSyncBusy = true
                            try {
                                require(isCanonicalOwnerId(ownerId)) { "Mã tài khoản không hợp lệ" }
                                require(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) {
                                    "Phiên đăng nhập đã thay đổi hoặc hết hạn; không thể chạy đồng bộ"
                                }
                                SyncWorkScheduler.scheduleManual(context, ownerId)
                                onMessage("Đã yêu cầu đồng bộ thủ công. Ứng dụng sẽ xử lý hàng đợi theo đúng thứ tự khi có mạng.")
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                onMessage("Không thể bắt đầu đồng bộ: ${error.message ?: "Lỗi không xác định"}")
                            } finally {
                                manualSyncBusy = false
                            }
                        }
                    },
                    enabled = !manualSyncBusy && !restoreLocked,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (manualSyncBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Sync, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (manualSyncBusy) "Đang yêu cầu…" else "Đồng bộ ngay")
                }
            }
        }'''
    if "Đồng bộ kho" not in m:
        if m.count(anchor) != 1:
            raise SystemExit("Could not find unique ToolsScreen title anchor")
        m = m.replace(anchor, ui, 1)
    main.write_text(m)

    rebuilt = Path(td) / "Step208-source.updated.zip"
    with zipfile.ZipFile(rebuilt, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(project.rglob("*")):
            if p.is_file():
                z.write(p, Path("KhoLinhKienAndroid") / p.relative_to(project))
    with zipfile.ZipFile(rebuilt) as z:
        bad = z.testzip()
        if bad:
            raise SystemExit(f"ZIP integrity failure: {bad}")
        sync_text = z.read("KhoLinhKienAndroid/app/src/main/java/vn/kholinhkien/SyncWork.kt").decode()
        main_text = z.read("KhoLinhKienAndroid/app/src/main/java/vn/kholinhkien/MainActivity.kt").decode()
        if "scheduleManual(context: Context, ownerId: String)" not in sync_text:
            raise SystemExit("Manual scheduler missing from rebuilt ZIP")
        if "Đồng bộ ngay" not in main_text or "currentUserOrNull()?.id == ownerId" not in main_text:
            raise SystemExit("Manual sync UI/account fence missing from rebuilt ZIP")
    shutil.copy2(rebuilt, archive)
    # Also patch the live workspace source that the release workflow compiles.
    # The archive is the canonical baseline, but Gradle builds from the already
    # extracted workspace; updating only the ZIP would silently omit Step222.
    workspace = Path.cwd()
    live_sync = workspace / "app/src/main/java/vn/kholinhkien/SyncWork.kt"
    live_main = workspace / "app/src/main/java/vn/kholinhkien/MainActivity.kt"
    if not live_sync.is_file() or not live_main.is_file():
        raise SystemExit("Live Gradle source tree is missing Step222 targets")
    # Merge Step222 additions into the live source instead of copying whole
    # files from the older archive. Earlier workflow patches (notably Step212
    # session expiry and backup scheduling) have already modified these files.
    live_sync_text = live_sync.read_text()
    archive_sync_text = sync.read_text()
    scheduler_start = "    /**\n     * Explicit user-triggered retry."
    scheduler_end = "    fun schedule(context: Context, ownerId: String, isAdmin: Boolean = false) {"
    if "fun scheduleManual(context: Context, ownerId: String)" not in live_sync_text:
        start = archive_sync_text.find(scheduler_start)
        end = archive_sync_text.find(scheduler_end, start)
        if start < 0 or end < 0:
            raise SystemExit("Cannot isolate Step222 scheduler block from archive")
        block = archive_sync_text[start:end]
        if live_sync_text.count(scheduler_end) != 1:
            raise SystemExit("Cannot locate unique live scheduler insertion point")
        live_sync_text = live_sync_text.replace(scheduler_end, block + scheduler_end, 1)
    live_sync.write_text(live_sync_text)

    live_main_text = live_main.read_text()
    archive_main_text = main.read_text()
    ui_anchor = '        Text("Công cụ", style = MaterialTheme.typography.titleLarge)'
    if "Đồng bộ ngay" not in live_main_text:
        lines = archive_main_text.splitlines()
        card_start = next((i for i, line in enumerate(lines) if line.startswith("        var manualSyncBusy")), -1)
        if card_start < 0:
            raise SystemExit("Cannot locate Step222 card start")
        depth = 0
        seen_open = False
        card_lines = []
        for line in lines[card_start:]:
            card_lines.append(line)
            depth += line.count("{") - line.count("}")
            seen_open = seen_open or "{" in line
            if seen_open and depth == 0:
                break
        else:
            raise SystemExit("Cannot find balanced end of Step222 card")
        card = "\\n".join(card_lines)
        if live_main_text.count(ui_anchor) != 1:
            raise SystemExit("Cannot locate unique live ToolsScreen title")
        live_main_text = live_main_text.replace(ui_anchor, ui_anchor + "\\n" + card, 1)
    live_main.write_text(live_main_text)

    if "fun scheduleManual(context: Context, ownerId: String)" not in live_sync.read_text():
        raise SystemExit("Step222 scheduler did not reach live Gradle source")
    if "Đồng bộ ngay" not in live_main.read_text():
        raise SystemExit("Step222 UI did not reach live Gradle source")
print("Step222 source patch PASS")
