# STEP186 — Owner-scoped Cloud backup catalog contract (03/11/2026)

## Mục tiêu
Chuẩn bị bước đầu để người dùng có thể tìm thấy backup sau khi gỡ/cài lại ứng dụng, mà không suy đoán trạng thái Cloud, không lẫn dữ liệu giữa Admin/KTV và không tự động ghi/khôi phục dữ liệu.

## Thay đổi
- Thêm `WeeklyBackupCloudCatalog.kt`: adapter đọc-only gọi RPC `list_my_inventory_backups()` bằng phiên Supabase hiện tại.
- Kiểm tra UUID owner canonical và Auth UID trước/sau RPC; nếu tài khoản đổi trong lúc tải thì từ chối toàn bộ kết quả.
- Parse toàn bộ response theo nguyên tắc all-or-nothing; lỗi/missing field/duplicate ID không trả về catalog một phần.
- Kiểm tra backupId UUID, `storage_path == ownerId/backupId.zip`, loại backup, trạng thái, kích thước <=256 MiB, SHA-256 64 ký tự hex, cursor, số lượng parts/movements và timestamp bắt buộc cho VERIFIED/DELETED.
- Chỉ đánh dấu `isRestoreCandidate=true` khi status VERIFIED, có verified_at và chưa deleted. PENDING/FAILED/DELETED không thể thành ứng viên khôi phục.
- Không tải object, không mở ZIP, không gọi restore, không upload/delete, không thay đổi Room hay dữ liệu Cloud. Chưa gắn catalog vào UI/Worker; tránh gọi RPC candidate chưa được xác nhận triển khai.

## Backend / triển khai
- `list_my_inventory_backups()` hiện chỉ tồn tại trong `supabase/candidates/step160_weekly_backup_backend_candidate.sql`. Chưa có bằng chứng RPC đã triển khai trên Supabase thật.
- Khi RPC chưa có, lời gọi tương lai phải báo lỗi rõ ràng; tuyệt đối không diễn giải lỗi RPC thành danh sách rỗng hoặc “không có backup”.
- Không chạy SQL, không deploy, không truy vấn hoặc thay đổi dữ liệu Supabase trong Step186.

## Kiểm tra
- `tools/verify_step186_cloud_catalog_contract.py`: 22/22 PASS.
- Regression Steps158–186: 29 verifier scripts, 481/481 checks PASS.
- Python compileall và ZIP integrity được chạy trước khi đóng gói.

## Giới hạn
- Chỉ source-contract/static checks. Chưa Kotlin compile/type-check, APK build, cài đặt/kiểm thử Android, RPC thật, RLS, Storage download, checksum trên object thật hoặc restore.
- Catalog chưa được gọi từ giao diện; chưa có tính năng phục hồi sau reinstall hoàn chỉnh.
- Không thay đổi Room schema, SQL/backend/server/data/Storage; không kích hoạt lịch backup; không push GitHub. Không chạy Gradle theo yêu cầu dự án.

## Bước tiếp theo
Step187: giao diện xem catalog sau đăng nhập, hiển thị trạng thái minh bạch và chỉ cho phép chọn bản VERIFIED; sau đó mới triển khai luồng tải về vào cache tạm, xác minh size/SHA-256 và chuyển qua cơ chế restore đã có. Cần xác nhận backend RPC đã được triển khai trước khi bật giao diện gọi thật.
