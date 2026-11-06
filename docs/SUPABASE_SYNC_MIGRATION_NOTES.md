# Ghi chú migration đồng bộ Supabase

Migration `202611020001_offline_sync_foundation.sql` được viết dựa trên báo cáo schema người dùng xuất ngày 02/11/2026.

## Các thay đổi bổ sung
- Tạo bucket `part-images` ở chế độ private nếu chưa có; giới hạn 20 MiB và MIME JPEG/PNG/WebP.
- Bổ sung `payload`, `result`, `processed_at` vào `sync_operations`; giữ nguyên `operation_id` làm khóa chính chống gửi trùng.
- Thêm restrictive policy cho `storage.objects` để tài khoản bị khóa không tiếp tục truy cập ảnh; các policy owner-folder hiện có vẫn quyết định quyền theo thư mục.
- Thêm RPC `apply_inventory_operation` chạy trong transaction PostgreSQL cho `PART_UPSERT`, `PART_DELETE`, `STOCK_CHANGE`.
- Giao dịch kho dùng `SELECT ... FOR UPDATE`; movement có `id = operation_id`, nhờ đó một operation không tạo hai movement.
- RPC chỉ tác động dữ liệu có `owner_id = auth.uid()`; Admin không được dùng RPC này để thay kho KTV.

## Điều kiện và giới hạn cần biết
- Chạy sau migration `202611010002_enforce_active_profiles.sql`.
- Migration chỉ được chuẩn bị, CHƯA chạy trên Supabase thật.
- Client Android hiện chưa gọi RPC này. Chưa được bật đồng bộ trong app.
- Room hiện dùng `Long` cho ID cục bộ, còn cloud dùng UUID. Trước khi tích hợp client phải bổ sung UUID ổn định cho từng Part và tạo outbox Room trong cùng transaction với mọi thao tác ghi.
- `PART_UPSERT` hiện là upsert snapshot linh kiện; không được dùng nó để thay đổi tồn kho sau khi đã có giao dịch cloud. Thay đổi tồn chỉ dùng `STOCK_CHANGE`.
- Chưa có RPC snapshot/restore toàn kho. Khôi phục ZIP không được tự động đồng bộ bằng cách phát lại từng movement.
- Cần test trên Supabase staging trước: chạy migration, test tài khoản active/inactive, retry cùng operation_id, hai KTV? (mỗi owner riêng), hai request đồng thời cùng owner/part, stock âm, mã trùng owner, photo path khác owner.
- Cần kiểm tra lại định nghĩa thực tế của `is_active_profile()` và quyền tạo policy Storage trên project trước khi chạy.
- `PART_UPSERT` chỉ dùng để tạo linh kiện hoặc cập nhật metadata; khi cập nhật bản ghi đã có, gửi `expected_server_version`. RPC không ghi đè `quantity` của linh kiện đã tồn tại. Tồn kho chỉ thay đổi qua `STOCK_CHANGE`.

## Change feed for two-way sync (added 02/11/2026)
- The foundation migration now creates `inventory_sync_events`, an append-only owner-scoped change feed with a monotonic `bigserial` cursor.
- `apply_inventory_operation` appends a complete PART snapshot for create/update/delete; STOCK_CHANGE appends both the updated PART snapshot and immutable MOVEMENT snapshot in the same PostgreSQL transaction.
- Idempotent retries return the saved ledger result before appending events, so retrying the same operation UUID cannot duplicate the change feed.
- `pull_inventory_changes(after_event_id, limit)` returns only the authenticated active owner's events, in ascending event order, capped at 500 per page. The event table has RLS enabled and no direct client grants; reads go through the owner-checked SECURITY DEFINER RPC.
- This is still a migration draft. It has not been parsed/executed against the live Supabase project. Android pull client, Room cursor persistence, conflict-aware application and photo download are not implemented yet.
