# Step78 — Xác nhận giao dịch xóa linh kiện an toàn (2026-11-03)

## Phát hiện
`deletePartByCode()` tạo `PART_DELETE` trong Outbox rồi xóa dòng `Part` cục bộ trong cùng Room transaction. Tuy nhiên, sender trước đây xử lý mọi mutation qua `completeOutboxWithServerVersion()`, gọi `recordServerVersionAndRebase()`. Hàm này yêu cầu cập nhật đúng một `Part` owner-scoped. Với luồng xóa hợp lệ, dòng đó đã không còn, khiến transaction xác nhận local thất bại ngay cả khi RPC máy chủ đã commit tombstone.

## Thay đổi
- Thêm `completeDeleteOutbox()` là Room `@Transaction` riêng cho `PART_DELETE`.
- Chỉ chấp nhận đúng Outbox có owner, entity, loại `PART_DELETE` và trạng thái `IN_FLIGHT` khớp.
- Xác nhận dòng `Part` local vẫn vắng mặt; nếu nó xuất hiện lại, fail closed.
- Kiểm tra không còn mutation `PART_UPSERT`/`PART_DELETE`/`STOCK_CHANGE` chưa xử lý khác cho cùng owner/entity. Nếu có, không đánh dấu xóa đã hoàn tất và giữ trạng thái cần đối soát.
- Chỉ sau các kiểm tra trên mới đánh dấu chính Outbox xóa là `SYNCED`; không cố cập nhật serverVersion cho Part đã bị xóa cục bộ.
- `SyncOutboxSender` định tuyến `PART_DELETE` sang DAO chuyên biệt; các loại mutation khác vẫn dùng atomic server-version rebase như trước.

## Kiểm tra
- `tools/verify_step78_delete_ack.py`: 7/7 static assertions PASS; mô hình trạng thái fail-closed PASS.
- `tools/verify_step77_sequential_rebase.py`: 7/7 static assertions PASS; FIFO version-chain và overflow model PASS.
- `tools/verify_step76_rpc_ack_guard.py`: 7/7 PASS.
- `tools/verify_step75_stock_version_null.py`: 7/7 PASS.
- `tools/verify_step74_null_validation.py`: 5/5 PASS.
- `tools/verify_step73_idempotency.py`: 5/5 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 PASS, gồm SQLite schema/atomic replacement smoke test.

## Giới hạn
- Đây là kiểm tra mã nguồn và mô hình độc lập, không phải Room instrumentation/runtime test.
- Chưa chạy Supabase/PostgreSQL staging để kiểm tra lost-response sau khi tombstone commit, replay idempotent, hoặc chuỗi delete cạnh tranh với restore.
- Không build APK, không test thiết bị, không deploy SQL, không push GitHub.
