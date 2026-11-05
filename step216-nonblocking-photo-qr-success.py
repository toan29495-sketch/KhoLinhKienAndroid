#!/usr/bin/env python3
"""Step216: remove blocking success dialogs after photo capture and QR saves."""
from pathlib import Path
ROOT = Path(__file__).resolve().parent
MAIN = ROOT / "app/src/main/java/vn/kholinhkien/MainActivity.kt"
source = MAIN.read_text(encoding="utf-8")

replacements = [
    (
        '                message = "Đã chụp ảnh, đang chờ đồng bộ cloud"\n',
        '                // Photo is staged locally; the form already shows "Đã lưu ảnh linh kiện".\n'
    ),
    (
        '                message = "Đã thêm ${part.code}; sẽ đồng bộ khi có mạng"\n',
        '                // Save is reflected immediately in the inventory list; sync runs in background.\n'
    ),
    (
        '            message = "Đã lưu ảnh QR vào thư viện ảnh"\n',
        '            // QR image is saved to the gallery without a blocking success dialog.\n'
    ),
]
for old, new in replacements:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Step216: expected exactly one occurrence, found {count}: {old.strip()}")
    source = source.replace(old, new, 1)

progress = ROOT / "PROGRESS.md"
if progress.exists():
    log = progress.read_text(encoding="utf-8")
    heading = "## Step216 — Remove blocking success dialogs for photos and QR saves (05/11/2026)"
    if heading not in log:
        log += """

## Step216 — Remove blocking success dialogs for photos and QR saves (05/11/2026)
- Removed modal success message after taking a part photo. The add/edit form already indicates that a photo is attached.
- Removed modal success message after saving a part/QR; the item appears immediately in the local inventory and sync remains scheduled in the background.
- Removed modal success message after exporting a QR image to the gallery.
- Kept error dialogs intact; this change does not pretend that cloud sync succeeded. The existing outbox/sync worker remains responsible for cloud delivery.
- Runtime verification on the user's phone is still required.
"""
        progress.write_text(log, encoding="utf-8")
MAIN.write_text(source, encoding="utf-8")
print("Step216: blocking success dialogs removed.")
