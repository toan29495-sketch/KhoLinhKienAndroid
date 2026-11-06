# Step80 — Lưu tombstone/version fence cục bộ (2026-11-03)

## Phát hiện
Step79 so sánh `serverVersion` với Part còn tồn tại trong Room. Tuy nhiên pull tombstone xóa vật lý Part; sau khi process chết hoặc feed replay, local không còn version của tombstone để chặn một live event cũ đến trễ. Cursor tuần tự là hàng rào chính nhưng chưa đủ để bảo vệ độc lập ở cấp entity.

## Thay đổi
- Thêm entity Room `PartTombstone`, khóa ghép `(ownerId, syncId)`, lưu `serverVersion` và `deletedAt`.
- Room migration 11→12 tạo bảng `part_tombstones`; không xóa hoặc sửa dữ liệu kho hiện tại.
- `applyPulledPart()` đọc cả live Part và tombstone; dùng version lớn nhất làm hàng rào đơn điệu.
- Với tombstone mới hơn: lưu tombstone rồi xóa Part trong cùng DAO transaction.
- Với live event mới hơn tombstone: xóa tombstone cũ rồi upsert Part trong cùng transaction. Event bằng/cũ hơn bị bỏ qua.
- Restore snapshot giữ tombstone của các linh kiện vắng mặt; chỉ xóa tombstone khi snapshot chứa Part có `serverVersion` cao hơn. Snapshot Part không có version dương bị từ chối trước khi thay dữ liệu.
- Giữ nguyên owner isolation, restore lock và Outbox unresolved guard.

## Kiểm tra
- `tools/verify_step80_tombstone_ledger.py`: 16/16 PASS, gồm chạy SQL migration 11→12 thực tế trong SQLite, mô phỏng tombstone mới, replay live event cũ, replay tombstone trùng, live event mới hơn và owner isolation.
- `tools/verify_step79_stale_pull_guard.py`: 12/12 PASS.
- `tools/verify_step78_delete_ack.py`: 7/7 PASS.
- `tools/verify_step77_sequential_rebase.py`: 7/7 PASS.
- `tools/verify_step76_rpc_ack_guard.py`: 7/7 PASS.
- `tools/verify_step75_stock_version_null.py`: 7/7 PASS.
- `tools/verify_step74_null_validation.py`: 5/5 PASS.
- `tools/verify_step73_idempotency.py`: 5/5 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 PASS; SQLite schema/atomic replacement smoke test PASS.
- SQL correlated cleanup query đã chạy thử trên SQLite: xóa đúng tombstone khi live row cùng owner/entity có version cao hơn.
- Đây là source/static/SQLite model validation, không phải Room instrumentation hoặc Android runtime test.

## Giới hạn
- Chưa có Room runtime/instrumentation để xác nhận generated DAO/schema trên Android.
- Chưa chạy Supabase/PostgreSQL staging, test hai thiết bị, concurrency hoặc process-kill fault injection.
- Chưa build APK, deploy SQL, push GitHub hoặc thay đổi dữ liệu máy chủ.
- Tombstone được giữ cho tới khi có live event/snapshot mới hơn; chưa có garbage collection. Đây là lựa chọn an toàn để không làm mất version fence.

## Checkpoint lineage
Dựa trên reconstructed working source từ Step73 và các thay đổi Step74–79. Không phải phục hồi byte-for-byte source Step49–72 đã thất lạc. Step79 và các checkpoint trước giữ nguyên.
