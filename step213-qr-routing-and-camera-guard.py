#!/usr/bin/env python3
"""Apply safe unknown-QR routing and one-shot camera delivery to the release source."""
from pathlib import Path
ROOT = Path(__file__).resolve().parent
MAIN = ROOT / "app/src/main/java/vn/kholinhkien/MainActivity.kt"
source = MAIN.read_text(encoding="utf-8")
if "var pendingUnknownQrCode by remember(profile.id)" not in source:
    anchor = '    var machineHistoryCode by remember(profile.id) { mutableStateOf<String?>(null) }\n'
    if anchor not in source: raise SystemExit("Step213: cannot locate machineHistoryCode state")
    source = source.replace(anchor, anchor + '    // Unknown legacy QR values may be parts or devices; ask before routing.\n    var pendingUnknownQrCode by remember(profile.id) { mutableStateOf<String?>(null) }\n', 1)
old = '                machineHistoryCode = scanned\n'
if old in source: source = source.replace(old, '                pendingUnknownQrCode = scanned\n', 1)
elif "pendingUnknownQrCode = scanned" not in source: raise SystemExit("Step213: cannot locate unknown-QR fallback")
if "pendingUnknownQrCode?.let { scannedCode ->" not in source:
    anchor = '    machineHistoryCode?.let { machineCode ->\n'
    if anchor not in source: raise SystemExit("Step213: cannot locate machine history dialog")
    dialog = '''    pendingUnknownQrCode?.let { scannedCode ->
        AlertDialog(
            onDismissRequest = { pendingUnknownQrCode = null },
            title = { Text("Chưa tìm thấy mã trong kho") },
            text = {
                Text("Mã QR: $scannedCode\\n\\nBạn muốn thêm mã này thành linh kiện hay tra cứu lịch sử của thiết bị? Nếu linh kiện đã có trên máy chủ nhưng chưa tải về thiết bị, hãy đồng bộ kho trước khi tạo mới.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingUnknownQrCode = null
                    prefillCode = scannedCode
                    showAdd = true
                }) { Text("Thêm linh kiện") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingUnknownQrCode = null
                        machineHistoryCode = scannedCode
                    }) { Text("Lịch sử thiết bị") }
                    TextButton(onClick = { pendingUnknownQrCode = null }) { Text("Hủy") }
                }
            }
        )
    }
'''
    source = source.replace(anchor, dialog + anchor, 1)
if "var delivered by remember { mutableStateOf(false) }" not in source:
    anchor = '    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }\n'
    if anchor not in source: raise SystemExit("Step213: cannot locate camera permission state")
    source = source.replace(anchor, anchor + '    // ML Kit analyzes consecutive frames; accept one decoded QR per dialog.\n    var delivered by remember { mutableStateOf(false) }\n', 1)
old_scan = '.addOnSuccessListener { barcodes -> barcodes.firstOrNull()?.rawValue?.let(onCode) }'
new_scan = '''.addOnSuccessListener { barcodes ->
                                    if (!delivered) {
                                        val value = barcodes.firstOrNull()?.rawValue?.trim().orEmpty()
                                        if (value.isNotEmpty()) {
                                            delivered = true
                                            onCode(value)
                                        }
                                    }
                                }'''
if old_scan in source: source = source.replace(old_scan, new_scan, 1)
elif "if (!delivered)" not in source or "delivered = true" not in source: raise SystemExit("Step213: cannot locate ML Kit callback")
# Append a durable source-level progress record without duplicating it on reruns.
progress = ROOT / "PROGRESS.md"
if progress.exists():
    log = progress.read_text(encoding="utf-8")
    heading = "## Step213 — Safe QR scan routing and duplicate-callback guard (05/11/2026)"
    if heading not in log:
        log += """

## Step213 — Safe QR scan routing and duplicate-callback guard (05/11/2026)
- Reconciled the full Step210 source package with the Step212 release reconstruction workflow; kept the release workflow based on the full source archive plus ordered patches rather than replacing the repository with the incomplete two-Kotlin-file snapshot.
- Carried forward the Step210 unknown-QR choice flow: known part opens its detail; unknown code asks whether to add a part, open device history, or cancel. No silent unknown-to-device classification and no automatic part creation.
- Hardened CameraX/ML Kit delivery so a single QR visible across multiple camera frames invokes the scan callback only once; blank decoded payloads are ignored.
- Added source-contract verifier `tools/verify_step213_qr_routing_and_camera_guard.py`; 8/8 checks pass. Step210 QR routing regression verifier: 9/9 pass. Step209 session/backup verifier: 12/12 pass. The 19 Step190–210 regression verifiers pass locally.
- GitHub Actions source integration and wiring validation completed successfully for the pushed Step213 commit. These workflows validate source contracts and do not compile Kotlin or assemble an APK.
- Limitations: Kotlin/Compose compile, APK build/signing, camera runtime, and on-device QR/add-part/device-history tests remain unverified. No Supabase data, Auth settings, inventory, movement history, or Storage objects were changed.
"""
        progress.write_text(log, encoding="utf-8")
MAIN.write_text(source, encoding="utf-8")
tools=ROOT/"tools"
tools.mkdir(exist_ok=True)
(tools/"verify_step213_qr_routing_and_camera_guard.py").write_text('''from pathlib import Path
root = Path(__file__).resolve().parents[1]
source = (root / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text(encoding="utf-8")
checks = {
    "unknown QR staged": "pendingUnknownQrCode = scanned" in source,
    "choice dialog exists": "pendingUnknownQrCode?.let { scannedCode ->" in source,
    "add form prefilled": "prefillCode = scannedCode\\n                    showAdd = true" in source,
    "device history retained": "machineHistoryCode = scannedCode" in source,
    "known part path retained": "if (part != null) {\\n                selected = part" in source,
    "one-shot camera callback": "var delivered by remember { mutableStateOf(false) }" in source and "if (!delivered)" in source and "delivered = true" in source,
    "blank payload ignored": "if (value.isNotEmpty())" in source,
    "no silent unknown-to-device fallback": "machineHistoryCode = scanned\\n" not in source,
}
for label, ok in checks.items(): print(("PASS" if ok else "FAIL") + " - " + label)
print(f"RESULT: {sum(checks.values())}/{len(checks)} PASS")
if not all(checks.values()): raise SystemExit(1)
''', encoding="utf-8")
print("Step213 QR routing and one-shot camera guard applied.")
