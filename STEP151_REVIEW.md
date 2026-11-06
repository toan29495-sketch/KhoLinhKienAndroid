# Step151 Review — Khôi phục ZIP sau khi ứng dụng bị đóng đột ngột (03/11/2026)

## Phạm vi
- Tiếp tục trực tiếp từ Step150.
- Audit đường khôi phục sau process death / hết pin trong giai đoạn đọc ZIP và ghi durable staging.
- Không thay đổi cloud restore protocol, commit RPC, Room schema, hoặc dữ liệu máy chủ.

## Phát hiện và sửa
- `ZipRestore` đã giữ journal `STAGING` khi coroutine bị hủy; durable batches và journal được giữ để lần nhập cùng archive có thể dựng lại an toàn. `restoreId` được xác định từ `ownerId + manifest SHA-256`, vì vậy archive khác sẽ không được dùng để ghi đè journal đang hoạt động.
- UI trước Step151 vẫn hiển thị nút “Tiếp tục khôi phục” cho `STAGING`/`FAILED`, nhưng coordinator chỉ resume các giai đoạn đã staging; ZIP stream ban đầu không thể được khôi phục sau khi process chết. Đây là điểm UX khiến người dùng bị kẹt dù dữ liệu kho chưa bị thay.
- Step151 phân biệt `STAGING`/`FAILED`: nút đổi thành “Chọn lại ZIP để dựng lại staging”, mở Document Picker và giải thích cần chọn đúng archive cũ. Khi xác nhận, UI chỉ cho phép thử lại journal local ở hai trạng thái này; `ZipRestore` vẫn kiểm tra `restoreId` từ owner + manifest digest và từ chối archive khác.
- Re-import xóa journal/batches abortable và thư mục staging đúng `ownerId + restoreId`, sau đó dựng lại. Không thay đổi live inventory; Room apply vẫn chỉ chạy sau `SNAPSHOT_DOWNLOADED`.
- Các trạng thái sau STAGED tiếp tục dùng resume hiện có; remote-feed journal không hiển thị hành động chọn ZIP.

## Kiểm tra
- `verify_step151_restore_crash_recovery_ui.py`: 12/12 PASS.
- Regression Step140: 13/13; Step141 restore remote contract: 15/15; Step141 remote feed SQLite: PASS; Step142: 12/12; Step143: 11/11; Step144: 14/14; Step145: 12/12; Step146: 12/12; Step147: 12/12; Step148: 31/31; Step149: 14/14; Step150: 21/21 — PASS.
- `python3 -m compileall -q tools`: PASS.

## Giới hạn xác minh
- Đây là source/static checks và regression behavior tests; chưa chạy Android process-kill, pin depletion, SAF URI permission, Room/Compose runtime hoặc WorkManager trên thiết bị.
- Chưa compile Android/Compose/Room, chưa build/cài APK, chưa test QR/2 thiết bị, chưa push GitHub. Không sử dụng Gradle.
- Không thay đổi Supabase SQL/migration/RPC/Edge/Auth/Storage hoặc dữ liệu máy chủ; không xóa tài khoản, kho, lịch sử, cloud objects hay restore staging.
