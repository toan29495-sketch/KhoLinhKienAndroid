# Step 48 — Đối soát vòng đời ảnh cloud và xung đột ảnh giữa thiết bị

## Phạm vi
- Tiếp tục trên working copy đã khôi phục từ source Step26 và sửa ảnh Step47.
- Chỉ rà soát mã nguồn Android, các migration SQL đang có và thiết kế vòng đời ảnh.
- Không deploy, không sửa dữ liệu Supabase thật, không build APK.

## Kết quả kiểm tra trực tiếp

### 1. Không thể bật xóa ảnh cloud tự động an toàn ở thời điểm này
Ảnh cloud có thể còn được tham chiếu ở nhiều nơi:
- `public.parts.photo_path` (ảnh hiện tại của linh kiện).
- `public.inventory_sync_events.payload` (feed append-only được tạo từ thao tác PART).
- `public.inventory_restore_chunks` (các chunk snapshot restore đã stage/commit).
- `public.inventory_restore_sessions` và commit ledger liên quan tới snapshot đã commit.
- `sync_outbox.payloadJson` trên từng thiết bị khi thao tác chưa hoàn tất; đây là dữ liệu local, server không thể tự nhìn thấy mọi thiết bị.

Các snapshot restore được giữ vô thời hạn theo quyết định retention trước đó; event feed cũng là append-only trong migration hiện có. Do đó một object không còn là ảnh hiện tại trong `parts` vẫn có thể cần cho replay/restore hoặc một thiết bị offline. Không có registry từng installation và ACK theo thiết bị để chứng minh tất cả thiết bị đã qua mốc an toàn.

**Quyết định Step48:** không thêm RPC xóa ảnh, cron cleanup, hoặc thao tác Storage delete. Giữ ảnh cũ; chấp nhận chi phí lưu trữ cho đến khi có protocol retention theo từng installation và kiểm chứng staging. Đây là lựa chọn tránh mất dữ liệu, không phải tuyên bố GC đã hoàn tất.

### 2. Step47 path ổn định theo operation
- `SyncOutboxSender.uploadPartPhotoIfPresent()` dùng `$ownerId/$partSyncId/$operationId.$extension`.
- Retry cùng Outbox row sẽ dùng cùng object path; thao tác mới có UUID mới nên không ghi đè ảnh của thao tác trước.
- `upsert=true` chỉ ghi đè object cùng operation path; điều này cần thiết cho retry sau upload bị ngắt.
- Policy `part_images_owner_*` kiểm tra thư mục đầu tiên bằng `auth.uid()`, nên path có thêm cấp thư mục vẫn thuộc owner.

### 3. Xung đột ảnh/metadata chưa được bảo vệ đầy đủ
- SQL `apply_inventory_operation` hiện chỉ áp dụng điều kiện `expected_server_version` nếu payload có trường này; nếu thiếu, update vẫn được chấp nhận.
- `Part` trong Room chưa có `serverVersion`.
- `enqueuePart()` không đưa `expected_server_version` vào Outbox payload.
- Mapper từ change feed/snapshot không ánh xạ `server_version` về Room.
- Vì vậy client hiện tại không thể gửi optimistic concurrency token đáng tin cậy. Hai thiết bị sửa metadata/ảnh từ cùng một phiên bản có thể ghi đè theo thứ tự máy chủ nhận.
- Khi `PART_UPSERT` chạy trên row đã soft-delete, SQL hiện đặt `deleted_at = NULL`; một thiết bị offline có thể vô tình làm sống lại linh kiện đã bị xóa. Đây là vấn đề tombstone riêng, không tự ý sửa trong Step48 vì cần quy tắc xung đột được chốt xuyên suốt pull/restore/delete.

## Việc cần làm trước khi triển khai GC hoặc optimistic concurrency
1. Thêm `serverVersion` vào Room `Part` và migration Room có kiểm tra dữ liệu cũ.
2. Trả `server_version` trong mọi PART feed/snapshot và ánh xạ đầy đủ vào Room.
3. Gắn `expected_server_version` vào Outbox payload tại thời điểm enqueue; xử lý rõ conflict bằng cách giữ Outbox, tải bản mới và yêu cầu hợp nhất/ghi lại thay vì tự động ghi đè.
4. Thiết kế tombstone/delete semantics: stale upsert không được tự xóa `deleted_at`; restore có chủ đích phải là một hành động riêng.
5. Thiết kế device registry, per-installation ACK/cursor, bootstrap thiết bị mới, expiry/recovery thiết bị lâu offline và retention snapshot/feed.
6. Chỉ sau đó mới lập candidate set ảnh mồ côi, tính mọi tham chiếu từ live rows, event feed, restore snapshots và pending Outbox; dùng grace period, owner-scoped delete RPC và audit log; kiểm thử race upload/restore/delete trên staging.

## Kiểm tra source-level
- Xác nhận `Part` không có `serverVersion`.
- Xác nhận `enqueuePart()` không gửi `expected_server_version`.
- Xác nhận mapper PART không đọc `server_version`.
- Xác nhận SQL cho phép thiếu `expected_server_version` và set `deleted_at = NULL` ở nhánh PART_UPSERT.
- Xác nhận migration feed tạo append-only `inventory_sync_events`; các migration hiện có tạo restore staging/chunk tables.

## Giới hạn
- Không chạy PostgreSQL/Supabase thực tế, không kiểm thử Storage, không kiểm thử hai thiết bị, không build APK, không test thiết bị và không push GitHub.
- Step48 là audit + quyết định an toàn, chưa phải implementation GC hoặc conflict resolution.
