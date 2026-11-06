#!/usr/bin/env python3
"""Step188 source-contract checks for owner-isolated Cloud catalog UI wiring."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text()
CATALOG = (ROOT / "app/src/main/java/vn/kholinhkien/WeeklyBackupCloudCatalog.kt").read_text()
WORKER = (ROOT / "app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt").read_text()
checks = [
    ("ToolsScreen exposes explicit Cloud catalog action", 'Tải danh sách backup Cloud' in MAIN),
    ("catalog request is user-triggered, not LaunchedEffect", 'onClick = {' in MAIN and 'WeeklyBackupCloudCatalog().listForOwner(ownerId)' in MAIN and 'LaunchedEffect' not in MAIN[MAIN.index('fun ToolsScreen('):MAIN.index('fun MachineHistoryDialog(')]),
    ("catalog call is dispatched off the main thread", 'withContext(Dispatchers.IO)' in MAIN[MAIN.index('fun ToolsScreen('):MAIN.index('fun MachineHistoryDialog(')]),
    ("catalog state is keyed by authenticated owner", all(f'remember(ownerId) {{ mutableStateOf' in MAIN for _ in range(1)) and 'cloudCatalogEntries by remember(ownerId)' in MAIN and 'cloudCatalogError by remember(ownerId)' in MAIN),
    ("loading disables duplicate refresh", 'enabled = !cloudCatalogLoading' in MAIN),
    ("cancellation propagates rather than becoming empty result", 'catch (cancelled: CancellationException)' in MAIN and 'throw cancelled' in MAIN),
    ("RPC failure is shown separately from empty catalog", 'cloudCatalogError.isNotBlank()' in MAIN and 'cloudCatalogLoaded && cloudCatalogEntries.isEmpty()' in MAIN and 'không có nghĩa là tài khoản chưa từng sao lưu' in MAIN),
    ("catalog shows all backend states distinctly", all(x in MAIN for x in ['Status.VERIFIED', 'Status.PENDING', 'Status.FAILED', 'Status.DELETED'])),
    ("only verified non-deleted entries are marked restore candidates", 'entry.isRestoreCandidate' in MAIN and 'status == Status.VERIFIED && verifiedAt != null && deletedAt == null' in CATALOG),
    ("catalog list remains read-only; download is delegated to a separate verified gateway", 'WeeklyBackupCloudCatalog().listForOwner(ownerId)' in MAIN and 'WeeklyBackupCloudDownloader(context).downloadAndVerify(ownerId, entry)' in MAIN),
    ("catalog adapter checks owner before and after RPC", CATALOG.count('currentUserOrNull()?.id == ownerId') >= 2),
    ("catalog adapter calls deployed owner-only RPC", 'rpc("list_my_inventory_backups")' in CATALOG),
    ("automatic worker remains untouched by catalog UI", 'WeeklyBackupCloudCatalog' not in WORKER),
]
failed=[]
for name, ok in checks:
    print(("PASS" if ok else "FAIL") + " - " + name)
    if not ok: failed.append(name)
print(f"STEP188: {len(checks)-len(failed)}/{len(checks)} PASS")
if failed: raise SystemExit(1)
