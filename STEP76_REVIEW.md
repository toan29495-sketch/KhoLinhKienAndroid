# Step76 — Xác minh phản hồi RPC trước khi xác nhận Outbox (2026-11-03)

## Mục tiêu
Bảo vệ trạng thái local khi RPC trả về thành công ở tầng truyền tải nhưng body không chứng minh rằng đúng giao dịch/đúng linh kiện đã được áp dụng.

## Thay đổi
- `SyncOutboxSender` chỉ cho phép hoàn tất Outbox nếu phản hồi có `status = applied`.
- `entity_id` trong phản hồi phải khớp `claimed.entityId` (không phân biệt chữ hoa/thường của UUID).
- `server_version` phải là số nguyên dương trước khi gọi DAO đánh dấu SYNCED/rebase.
- Thiếu trường, sai entity hoặc version không hợp lệ sẽ đi vào nhánh lỗi hiện có; giao dịch không được xác nhận SYNCED và hàng đợi dừng tại đó.
- SQL hiện tại trả về result đã lưu cho retry cùng operationId và payload; result gốc bao gồm status/entity_id/server_version, nên replay hợp lệ tiếp tục thỏa điều kiện xác nhận. Payload khác vẫn bị từ chối trước nhánh cached replay.

## Kiểm tra
- `tools/verify_step76_rpc_ack_guard.py`: 7/7 kiểm tra tĩnh PASS.
- `tools/verify_step75_stock_version_null.py`: 7/7 PASS.
- `tools/verify_step74_null_validation.py`: 5/5 PASS.
- `tools/verify_step73_idempotency.py`: 5/5 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 PASS, gồm SQLite schema/atomic replacement smoke test.

## Giới hạn
- Đây là source-level/static checks, không phải Kotlin/Android runtime test.
- Chưa có PostgreSQL/Supabase staging để kiểm tra mất phản hồi sau COMMIT, retry đồng thời hoặc xác nhận khóa hàng idempotency trên DB thật.
- Schema gốc tạo `sync_operations` không nằm trong bộ migration được phục hồi ở source này; do đó không kết luận độc lập về primary key/unique constraint từ source hiện có.
- Không build APK, không test thiết bị, không deploy SQL, không push GitHub.
