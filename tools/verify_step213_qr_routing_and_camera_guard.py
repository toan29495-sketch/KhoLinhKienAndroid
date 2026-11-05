from pathlib import Path
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
    "Step213 progress record": "## Step213 — Safe QR scan routing and duplicate-callback guard" in (root / "PROGRESS.md").read_text(encoding="utf-8"),
}
for label, ok in checks.items(): print(("PASS" if ok else "FAIL") + " - " + label)
print(f"RESULT: {sum(checks.values())}/{len(checks)} PASS")
if not all(checks.values()): raise SystemExit(1)
