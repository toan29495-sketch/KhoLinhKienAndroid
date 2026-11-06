from pathlib import Path

source = Path(__file__).resolve().parents[1] / "app/src/main/java/vn/kholinhkien/MainActivity.kt"
text = source.read_text(encoding="utf-8")
checks = {
    "known part still opens part detail": "if (part != null) {\n                selected = part",
    "unknown code is staged for explicit choice": "pendingUnknownQrCode = scanned",
    "unknown QR dialog is present": "pendingUnknownQrCode?.let { scannedCode ->",
    "unknown QR can open add-part form": "prefillCode = scannedCode\n                    showAdd = true",
    "unknown QR can open device history": "machineHistoryCode = scannedCode",
    "device history dialog remains intact": "MachineHistoryDialog(\n            machineCode = machineCode",
    "no silent unknown-to-device fallback": "machineHistoryCode = scanned\n" not in text,
}
failed = []
for name, needle in checks.items():
    ok = needle if isinstance(needle, bool) else needle in text
    print(("PASS" if ok else "FAIL") + ": " + name)
    if not ok:
        failed.append(name)
if failed:
    raise SystemExit(f"{len(failed)} checks failed")
print(f"{len(checks)}/{len(checks)} checks passed")
