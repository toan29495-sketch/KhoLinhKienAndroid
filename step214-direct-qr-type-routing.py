#!/usr/bin/env python3
"""Step214: classify unknown QR codes by the established device/part code formats."""
from pathlib import Path
ROOT = Path(__file__).resolve().parent
MAIN = ROOT / "app/src/main/java/vn/kholinhkien/MainActivity.kt"
source = MAIN.read_text(encoding="utf-8")
classifier = ROOT / "app/src/main/java/vn/kholinhkien/QrCodeType.kt"
classifier.write_text("""package vn.kholinhkien

/** Explicit discriminator for the two established QR payload formats. */
internal enum class QrCodeType { DEVICE, PART }

internal fun classifyQrCodeType(value: String): QrCodeType =
    if (value.isNotEmpty() && value.all(Char::isDigit)) QrCodeType.DEVICE else QrCodeType.PART
""", encoding="utf-8")
state = '    // Unknown legacy QR values may be parts or devices; ask before routing.\n    var pendingUnknownQrCode by remember(profile.id) { mutableStateOf<String?>(null) }\n'
if state in source:
    source = source.replace(state, "", 1)
elif "pendingUnknownQrCode by remember" in source:
    raise SystemExit("Step214: unexpected pending QR state; refusing partial removal")
start = '    pendingUnknownQrCode?.let { scannedCode ->\n'
if start in source:
    end = '    machineHistoryCode?.let { machineCode ->\n'
    a = source.index(start)
    b = source.index(end, a)
    source = source[:a] + source[b:]
elif "Chưa tìm thấy mã trong kho" in source:
    raise SystemExit("Step214: found unknown-QR dialog but could not locate its full block")
old = '                pendingUnknownQrCode = scanned\\n'
new = '''                if classifyQrCodeType(scanned) == QrCodeType.DEVICE:
                    machineHistoryCode = scanned
                else:
                    prefillCode = scanned
                    showAdd = True
'''
if old in source:
    source = source.replace(old, new, 1)
elif "classifyQrCodeType(scanned)" not in source:
    raise SystemExit("Step214: cannot locate pending unknown-QR assignment")
if "pendingUnknownQrCode" in source or "Chưa tìm thấy mã trong kho" in source:
    raise SystemExit("Step214: intermediate unknown-QR dialog remains")
if "classifyQrCodeType(scanned)" not in source:
    raise SystemExit("Step214: classifier is not wired into scan handler")
progress = ROOT / "PROGRESS.md"
if progress.exists():
    log = progress.read_text(encoding="utf-8")
    heading = "## Step214 — Direct QR routing by code type (05/11/2026)"
    if heading not in log:
        log += """

## Step214 — Direct QR routing by code type (05/11/2026)
- Removed the intermediate unknown-QR choice dialog.
- Preserved known-part behavior: a code already in the local warehouse opens that part's details.
- Added an explicit QrCodeType discriminator: numeric-only unknown codes are device QR codes and open device history; codes containing any non-digit are part QR codes and open the add-part form with the scanned code prefilled.
- No automatic part creation; the user still reviews and saves the add form. No database migration is required because type is determined by the established QR format.
- Runtime camera testing remains required after APK installation.
"""
        progress.write_text(log, encoding="utf-8")
MAIN.write_text(source, encoding="utf-8")
print("Step214 direct QR routing applied.")
