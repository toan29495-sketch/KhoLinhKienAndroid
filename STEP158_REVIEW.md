# Step158 — Chụp snapshot nhất quán và chuẩn bị ZIP backup tuần (03/11/2026)

## Mục tiêu
Xây dựng phần an toàn cốt lõi cho sao lưu Cloud hàng tuần mà không thay đổi hành vi sao lưu thủ công, không bật worker/lịch chạy thật và chưa gọi Supabase Storage.

## Thay đổi mã nguồn
- `InventoryDao.captureAutomaticBackupSnapshot(ownerId)` là hàm `@Transaction`; chỉ trả snapshot nếu tài khoản không có restore đang hoạt động và outbox không còn `PENDING`, `FAILED`, `IN_FLIGHT` hoặc `BLOCKED`.
- Snapshot lấy `parts`, `movements` và sync cursor trong cùng Room transaction; mọi truy vấn dữ liệu đều lọc theo `ownerId`. KTV chỉ sao lưu kho và lịch sử của chính mình; không lấy Admin global movement feed hay dữ liệu kho của KTV khác.
- `BackupManager.prepareWeeklyCloudBackup(...)` tạo ZIP ở `context.cacheDir`, dùng snapshot DAO thay vì các list từ Compose UI, kiểm tra session/owner ở các ranh giới, xác minh nội dung ZIP và Central Directory, tính SHA-256, và xóa file tạm nếu thất bại.
- Manifest giữ version 2 và bổ sung `backupKind=WEEKLY_AUTO` cùng `snapshotCursor`. Manual ZIP mặc định `backupKind=MANUAL`; inspector vẫn đọc được archive cũ không có trường này và kiểm tra weekly archive phải có cursor không âm.
- Hàm chuẩn bị chỉ tạo archive cục bộ riêng tư; không upload, không đưa vào Downloads, không xóa bản backup cũ, không tự động khôi phục.

## Xác minh
- `tools/verify_step158_weekly_backup_snapshot.py`: 21/21 PASS.
- Regression Step140–156: tất cả verifier PASS; Step141 remote-feed SQLite migration smoke PASS.
- `python3 -m compileall -q tools`: PASS.
- SQLite WAL smoke model xác nhận transaction đọc nhất quán qua hai kết nối; các thay đổi ghi sau khi snapshot bắt đầu chỉ xuất hiện ở snapshot kế tiếp.

## Giới hạn chưa xác minh
- Chưa chạy Room-generated DAO hoặc Kotlin/Android compile; SQLite WAL smoke là mô hình hành vi, không thay thế runtime test Room trên Android.
- Chưa gọi hàm tạo ZIP trên thiết bị thật; chưa stress test tải ảnh lớn/ENOSPC; chưa tạo WorkManager worker hay lịch 7 ngày.
- Chưa có private bucket, bảng metadata, RPC owner-bound hoặc retention API cho backup; không upload Cloud trong Step158.
- Chưa build/cài APK, thử điện thoại, QR/2 thiết bị hoặc push GitHub. Không dùng Gradle.
- Không thay đổi Supabase, schema, bucket, user, dữ liệu kho, lịch sử hoặc Storage objects.

## Bước tiếp theo
Step159: kiểm tra và xây dựng worker upload riêng theo owner sau khi xác định chính xác Supabase Storage API đang dùng, nhưng trước đó cần chuẩn bị candidate cho bucket private + metadata + RPC/retention và xin xác nhận trước mọi thay đổi trên project thật. Worker chỉ được schedule sau khi upload/verify/retention contract có thể kiểm chứng.
