# Step79 — Chặn change-feed snapshot cũ ghi đè trạng thái mới (2026-11-03)

## Thay đổi
- `InventoryDao.applyPulledPart()` giờ xác thực owner/entity và yêu cầu `serverVersion > 0` trước khi chấp nhận PART event.
- Vẫn dừng pull nếu có restore đang hoạt động hoặc còn mutation Outbox chưa xử lý cho cùng linh kiện.
- Đọc Part hiện tại trước khi thay đổi; nếu local `serverVersion >=` version trong event thì coi event là replay/cũ và không ghi đè hoặc xóa trạng thái mới hơn.
- Event mới hơn tiếp tục đi theo nhánh tombstone delete hoặc live upsert hiện có; ảnh cloud vẫn giữ object path.

## Lý do
Change feed có thể được replay sau gián đoạn. Nếu event snapshot cũ được áp dụng sau khi local đã có phiên bản mới hơn, local state có thể bị lùi. So sánh version đơn điệu giúp fail-safe khi row hiện tại còn tồn tại.

## Kiểm tra
- `tools/verify_step79_stale_pull_guard.py`: 11/11 static + SQLite model assertions PASS.
- Step78: 7/7; Step77: 7/7; Step76: 7/7; Step75: 7/7; Step74: 5/5; Step73: 5/5; Step49–72: 24/24; SQLite schema/atomic replacement smoke test PASS.

## Giới hạn cần giữ rõ
- Local pull tombstone hiện xóa vật lý Part row. Vì vậy version guard không thể so sánh với tombstone cũ sau khi row đã biến mất. Trật tự change-feed cursor là hàng rào cho trường hợp đó; muốn chống cả event bị đảo thứ tự sau tombstone cần lưu tombstone/version ledger cục bộ hoặc thay mô hình xóa mềm. Step79 KHÔNG tuyên bố đã giải quyết trường hợp này.
- Chưa có Room runtime, PostgreSQL/Supabase staging, test nhiều worker đồng thời hoặc fault injection. Không build APK, không triển khai SQL, không push GitHub.
