from pathlib import Path
root = Path(__file__).resolve().parents[1]
main = (root / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text(encoding="utf-8")
classifier = (root / "app/src/main/java/vn/kholinhkien/QrCodeType.kt").read_text(encoding="utf-8")
checks = {
    "explicit QR type enum": "enum class QrCodeType { DEVICE, PART }" in classifier,
    "numeric-only maps to device": "value.all(Char::isDigit)" in classifier and "QrCodeType.DEVICE" in classifier,
    "other codes map to part": "else QrCodeType.PART" in classifier,
    "known part lookup remains first": "val part = dao.byCode(profile.id, scanned)" in main and main.index("val part = dao.byCode(profile.id, scanned)") < main.index("classifyQrCodeType(scanned)"),
    "unknown numeric opens device history": "machineHistoryCode = scanned" in main,
    "unknown part opens add form with prefilled code": "prefillCode = scanned\n                showAdd = true" in main,
    "choice dialog removed": "pendingUnknownQrCode" not in main and "Chưa tìm thấy mã trong kho" not in main,
}
for label, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " - " + label)
print(f"RESULT: {sum(checks.values())}/{len(checks)} PASS")
if not all(checks.values()):
    raise SystemExit(1)
