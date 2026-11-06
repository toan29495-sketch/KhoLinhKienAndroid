# Step73 — Siết chặt idempotency của RPC đồng bộ

Ngày: 2026-11-03
Baseline: checkpoint tái triển khai Step72 (`KhoLinhKienAndroid_recovered_step47`).

## Phát hiện
Trong `apply_inventory_operation`, nhánh trả kết quả đã xử lý (`processed_at IS NOT NULL`) đứng trước kiểm tra payload. Với cùng `operation_id`, `owner_id`, `entity_type` và `entity_id` nhưng payload khác, RPC có thể trả kết quả cache thay vì báo `OPERATION_PAYLOAD_CONFLICT`.

## Sửa đổi
- Chuyển kiểm tra `v_existing.payload IS DISTINCT FROM p_payload` lên trước nhánh trả kết quả cache.
- Giữ nguyên khóa hàng `FOR UPDATE`, kiểm tra owner/type/entity và cơ chế trả kết quả idempotent khi payload khớp chính xác.
- Không đổi chữ ký RPC, schema bảng hoặc dữ liệu hiện có.

## Kiểm tra
- `tools/verify_step73_idempotency.py`: 5/5 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 source-level PASS.
- SQLite schema/atomic replacement smoke test từ script hiện hữu: PASS.

## Giới hạn
- Chưa có PostgreSQL client/runtime trong môi trường hiện tại; chưa parse hoặc chạy migration trên PostgreSQL/Supabase staging.
- Chưa kiểm thử hai request đồng thời, mất response sau commit, RLS/grants thực tế hoặc upload Storage.
- Không triển khai migration lên production/staging; không build APK; không push GitHub.

## Kết luận
Sửa thứ tự kiểm tra idempotency ở mức SQL source. Đây là sửa lỗi quan trọng nhưng chưa đủ để tuyên bố RPC đã được xác thực runtime. Tiếp tục giữ migration chưa triển khai cho tới khi staging concurrency/fault-injection tests đạt.
