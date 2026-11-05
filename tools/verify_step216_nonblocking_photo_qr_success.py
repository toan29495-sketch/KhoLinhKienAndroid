from pathlib import Path
root = Path(__file__).resolve().parents[1]
main = (root / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text(encoding="utf-8")
checks = {
    "photo capture no longer raises success dialog": 'message = "Đã chụp ảnh, đang chờ đồng bộ cloud"' not in main,
    "successful part save no longer raises sync claim dialog": 'message = "Đã thêm ${part.code}; sẽ đồng bộ khi có mạng"' not in main,
    "QR image export no longer raises success dialog": 'message = "Đã lưu ảnh QR vào thư viện ảnh"' not in main,
    "photo capture still stages image locally": "pendingPhotoPath = file.absolutePath" in main,
    "part save still schedules background sync": "SyncWorkScheduler.schedule(context, profile.id, isAdmin)" in main,
    "photo save errors remain visible": 'message = "Không lưu được ảnh chờ đồng bộ: ${e.message}"' in main,
}
for label, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " - " + label)
print(f"RESULT: {sum(checks.values())}/{len(checks)} PASS")
if not all(checks.values()):
    raise SystemExit(1)
