# Step49–Step72 — Tái triển khai trên working source đã khôi phục

Ngày: 2026-11-03
Working copy: `KhoLinhKienAndroid_recovered_step47`
Baseline: archive Step26 còn đầy đủ + thay đổi ảnh Step47 được áp dụng lại.

## Kết luận phạm vi
Đã tái triển khai trên source hiện tại các lớp đồng bộ phiên bản, CAS/tombstone, Outbox FIFO/lease, chặn xung đột, đọc/đối chiếu snapshot và một đường giải quyết hẹp `KEEP_LOCAL` cho `PART_UPSERT` độc lập. Không khẳng định đây là bản khôi phục byte-for-byte của source Step72 bị thất lạc; đây là implementation mới được đối chiếu contract từ source Step26/47 và các review Step49–72 còn lại.

## Thay đổi thực tế trong source

### Step49–Step52: versioning, payload freeze và chaining
- Thêm `Part.serverVersion` và Room migration 8→9; giá trị mặc định 0 bảo toàn dữ liệu cũ nhưng được xem là chưa có version đáng tin cậy.
- Pull và cloud restore mapper đọc `server_version`.
- `PART_UPSERT`, `PART_DELETE`, `STOCK_CHANGE` đưa `expected_server_version` vào payload. Khi claim lần đầu, payload được đóng băng trước khi chuyển `IN_FLIGHT`.
- Sau RPC thành công, `server_version` mới, rebase các operation chưa gửi cùng linh kiện và trạng thái `SYNCED` được ghi trong một Room transaction.
- Ảnh vẫn dùng object path riêng theo `operationId`; file local chỉ xóa sau khi không còn Outbox khác tham chiếu.

### Step51: SQL optimistic concurrency và tombstone
- Migration `202611030001_enforce_inventory_version_cas.sql` thay RPC `apply_inventory_operation` bằng phiên bản yêu cầu CAS cho update/delete/stock.
- Stale upsert không thể bỏ `deleted_at`; tombstone trả `PART_DELETED`.
- PART_DELETE và STOCK_CHANGE cũng so sánh đúng `expected_server_version`.
- Nếu payload mang version dương nhưng row không còn tồn tại, server từ chối thay vì tạo lại row.
- Create mới vẫn được phép với version 0; update có version không hợp lệ bị chặn.

### Step53–Step58: block, inspect và server snapshot
- Lỗi vĩnh viễn như version conflict, tombstone, ownership hoặc operation-id conflict chuyển Outbox sang `BLOCKED`; lỗi tạm thời vẫn là `FAILED`.
- Có BLOCKED thì hàng đợi owner bị tạm dừng; pull không ghi đè linh kiện còn Outbox BLOCKED; restore cũng xem BLOCKED là giao dịch chưa xử lý.
- UI hiển thị chi tiết giao dịch, payload đã đóng băng, lỗi, số lần thử, số thao tác phía sau và phần so sánh các trường local/server.
- Migration `202611030002_read_owned_part_conflict_snapshot.sql` thêm RPC chỉ đọc, lookup bằng `parts.sync_id` và `auth.uid()`; client kiểm tra Supabase user đúng owner trước khi gọi.
- Có planner thuần để giải thích từng nhóm lỗi; planner không có quyền ghi dữ liệu.

### Step59–Step69: decision/preflight và giới hạn resolve
- Room migration 10→11 thêm `sync_conflict_decisions`, lưu quyết định `KEEP_LOCAL`, `KEEP_SERVER`, `MANUAL_REVIEW` có tính lặp an toàn.
- Preflight đọc blocker và các thao tác phụ thuộc trong Room transaction; xác minh owner, trạng thái, thứ tự, cùng entity và thao tác đã từng attempt.
- UI yêu cầu lưu lựa chọn, xác minh chuỗi phụ thuộc, đọc snapshot máy chủ lần cuối và xác nhận riêng trước khi xử lý.
- Chỉ triển khai thực thi cho trường hợp hẹp: `KEEP_LOCAL` + `PART_UPSERT` + server row còn tồn tại + server version dương + không có bất kỳ thao tác nào phía sau.
- Đường này đọc snapshot mới ngay trước khi ghi, từ chối tombstone và từ chối nếu quantity local/server khác nhau (PART_UPSERT không được phép ghi đè tồn kho); tạo operationId mới, giữ nguyên operation cũ ở `RESOLVED`, enqueue payload mới có CAS token, và liên kết replacementOperationId/version trong cùng Room transaction.
- Retry sau process death trả lại replacement đã liên kết, không sinh operation trùng.
- `KEEP_SERVER`, `MANUAL_REVIEW`, PART_DELETE, STOCK_CHANGE, tombstone/missing server row và mọi chuỗi phụ thuộc không được tự thực thi; queue vẫn bị khóa để xử lý thủ công. Đây là giới hạn có chủ đích, không phải thiếu sót được che giấu.

### Step70–Step72: atomic acknowledgement, FIFO và claim lease
- Acknowledge ghi version/rebase/status trong cùng transaction Room; RPC idempotency giữ operationId/payload ổn định khi response bị mất.
- FIFO claim chỉ nhận operation unresolved đầu tiên; không bỏ qua IN_FLIGHT/BLOCKED để gửi operation phía sau.
- Room migration 9→10 thêm `claimStartedAt`; lease 30 phút chỉ phục hồi IN_FLIGHT có timestamp dương đã hết hạn sang FAILED, giữ nguyên operationId/payload.
- IN_FLIGHT cũ có timestamp 0 được giữ lại và hiển thị cần đối soát; không tự lấy quyền xử lý khi không biết tuổi claim.
- Worker không retry ngay lỗi vĩnh viễn; lỗi tạm thời vẫn được retry.

## Kiểm tra đã chạy
- Script `tools/verify_sync_step49_72.py`: **24/24 kiểm tra source-level PASS**.
- SQLite smoke test: migration additive giữ nguyên row cũ và default version/lease bằng 0; transaction mô phỏng replacement insert + resolve blocker + link decision cùng commit: PASS.
- Một SQLite smoke test riêng cho FIFO/lease ở bước kiểm tra trước cũng PASS.

## Chưa kiểm chứng
- Không chạy Kotlin/Android compiler hoặc Android runtime/device test.
- Không chạy Room migration bằng Room thật; SQLite smoke test chỉ xác minh schema/transaction SQL tương đương.
- Không chạy PostgreSQL parser/runtime, Supabase staging RPC, RLS, advisory/idempotency concurrency, upload ảnh hoặc kiểm thử hai thiết bị.
- Không build APK, không triển khai migration SQL, không push GitHub.
- Lease hiện là timeout phục hồi, không phải fencing token; chưa có heartbeat renewal khi upload/RPC cực lâu. Trước khi bật multi-process/multi-device production cần kiểm thử race/idempotency trên staging.

## Việc còn lại trước khi phát hành
1. Review SQL migration trên staging, nhất là response shape `rpc`, CAS cho delete/stock và idempotency sau mất response.
2. Chạy Room migration test thật từ schema 8 qua 11 trên bản cài có dữ liệu cũ.
3. Test 2 thiết bị: create→opening stock→edit→stock changes→delete, conflict version, tombstone, mất mạng giữa upload/RPC/ack.
4. Thiết kế riêng quy trình `KEEP_SERVER`, `MANUAL_REVIEW`, và chuỗi phụ thuộc; không mở queue cho tới khi hoàn thành.
5. Xử lý các IN_FLIGHT legacy `claimStartedAt=0` bằng đối soát có hướng dẫn, không tự reset.
6. Chỉ sau khi người dùng duyệt source ZIP mới chuyển sang APK.

### Additional validation after UI/decision integration
- Re-ran `tools/verify_sync_step49_72.py`: 24/24 PASS.
- Re-ran SQLite smoke test for additive schema and atomic replacement: PASS.
- SQLite `EXPLAIN QUERY PLAN` accepted all Outbox SELECT DAO query forms: PASS.
- Standalone Kotlin compilation of the pure `SyncConflictResolutionPlanner.kt`: PASS. This does not compile the Android app or Room DAO.
