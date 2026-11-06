# Thiết kế đồng bộ offline-first cho Kiểm soát linh kiện

## Trạng thái tại mã nguồn Step208

Tài liệu này bắt đầu là thiết kế; nhiều thành phần hiện đã được triển khai trong mã nguồn Android và các RPC nền tảng đã được đối chiếu với catalog Supabase. Không được dùng các đoạn mô tả lịch sử bên dưới như khẳng định rằng đồng bộ đã được kiểm thử runtime. Các luồng gửi Outbox, kéo change-feed, xử lý tombstone/xung đột, Admin feed và restore snapshot đều cần được xác minh bằng APK trên thiết bị thật trước khi phát hành.

Không tự chạy lại các migration nền tảng hoặc triển khai SQL chỉ vì tài liệu này có ví dụ thiết kế. Step139 có sai khác lịch sử migration đã được ghi riêng trong `supabase/REMOTE_MIGRATION_MANIFEST.md`; không replay các file pending.

## Mục tiêu và nguyên tắc

1. Room là nơi đọc/ghi chính khi người dùng thao tác; app phải dùng được khi mất mạng.
2. Mỗi thay đổi nghiệp vụ được ghi cùng một bản ghi hàng đợi đồng bộ trong cùng transaction Room.
3. Mỗi thao tác có `operation_id` UUID ổn định. Gửi lại sau timeout không được tạo giao dịch thứ hai.
4. Máy chủ là nơi tuần tự hóa thay đổi tồn kho. Không đồng bộ bằng cách `upsert` số lượng tồn cục bộ.
5. Mọi truy vấn và thao tác dữ liệu phải giới hạn theo `owner_id`; Admin chỉ có quyền quản trị và xem lịch sử theo chính sách đã thống nhất, không tự động sở hữu kho của KTV.
6. Ảnh phải tải lên Storage trước khi gửi bản ghi linh kiện tham chiếu ảnh; đường dẫn ảnh phải nằm trong namespace của chủ kho.

## Mô hình đề xuất

### Hàng đợi cục bộ (Room)

Một bảng `sync_outbox` dự kiến có:

- `operationId`: UUID, khóa duy nhất.
- `ownerId`: Supabase Auth user ID.
- `operationType`: `PART_CREATE`, `PART_UPDATE`, `PART_DELETE`, `STOCK_CHANGE`, `RESTORE_OWNER_DATA`.
- `entityKey`: mã linh kiện hoặc khóa đối tượng.
- `payloadJson`: dữ liệu tối thiểu cần thiết cho thao tác.
- `createdAt`: thời điểm tạo ở máy.
- `status`: `PENDING`, `IN_FLIGHT`, `SYNCED`, `FAILED`.
- `attemptCount`, `lastError`, `serverResult`.

Không xóa bản ghi đã đồng bộ ngay lập tức; giữ một khoảng thời gian để hỗ trợ chẩn đoán, sau đó mới dọn theo chính sách.

### Giao thức máy chủ

Ưu tiên RPC PostgreSQL thực hiện trong một transaction. RPC nhận `operation_id`, loại thao tác, mã linh kiện và payload; kiểm tra `auth.uid() = owner_id`, kiểm tra hồ sơ đang hoạt động, sau đó:

- Tạo bảng/unique constraint chống lặp trên `(owner_id, operation_id)`.
- Nếu `operation_id` đã xử lý, trả lại kết quả cũ, không áp dụng lần hai.
- Với `STOCK_CHANGE`, khóa dòng linh kiện (`SELECT ... FOR UPDATE`), kiểm tra tồn kho và quy tắc SINGLE/BULK, cập nhật tồn, thêm đúng một movement.
- Với `PART_UPDATE`/`PART_DELETE`, kiểm tra phiên bản hoặc `updated_at` để phát hiện xung đột chỉnh sửa.
- Trả về phiên bản mới và trạng thái chấp nhận để client đánh dấu `SYNCED`.

Không dùng client-side read-modify-write cho số lượng tồn trên cloud vì hai KTV có thể ghi đè lẫn nhau.

## Thứ tự đồng bộ

1. Khôi phục phiên; lấy profile và xác nhận `is_active=true`.
2. Tải ảnh còn thiếu lên Storage theo đường dẫn riêng của owner.
3. Gửi outbox theo thứ tự tạo, từng thao tác hoặc lô nhỏ; mỗi thao tác dùng `operation_id` bất biến.
4. Máy chủ trả kết quả; client đánh dấu `SYNCED` chỉ sau xác nhận thành công.
5. Kéo thay đổi cloud về Room theo `server_revision`/`updated_at` đã được máy chủ cấp.
6. Nếu mất mạng hoặc timeout, giữ `PENDING` và gửi lại cùng `operation_id`.

## Xung đột và khôi phục

- Giao dịch nhập/xuất là sự kiện cộng/trừ tuần tự ở máy chủ; không giải quyết bằng “lấy số tồn mới nhất ghi đè”.
- Chỉnh sửa mô tả linh kiện dùng revision/optimistic concurrency; xung đột cần báo người dùng và không âm thầm ghi đè.
- Khôi phục ZIP là thao tác thay thế toàn bộ kho của một owner, phải có xác nhận rõ ràng và RPC transaction riêng. Không được phát lại từng movement cũ như giao dịch mới vì sẽ làm sai tồn.
- Bản ZIP được khôi phục nên được đánh dấu là snapshot/version mới; các outbox cũ của owner cần được xử lý/đóng có chủ đích trước khi đồng bộ tiếp.
- Xóa linh kiện phải giữ movement lịch sử; cần quy tắc soft-delete hoặc snapshot thông tin linh kiện trong movement.

## Quyền truy cập cần có

- KTV chỉ đọc/ghi dữ liệu có `owner_id = auth.uid()`.
- KTV không được sửa/xóa movement đã ghi; giao dịch sửa sai phải là một movement điều chỉnh mới theo quy tắc nghiệp vụ.
- Admin đọc lịch sử toàn hệ thống nếu policy hiện hành cho phép; không mặc định được sửa kho của KTV.
- Tài khoản bị khóa phải bị chặn cả RPC và Storage, không chỉ PostgREST tables.
- Edge Function dùng service-role chỉ cho quản trị tài khoản; không dùng service-role trong APK.

## Điều kiện trước khi triển khai

Cần xác minh schema thực tế của `parts`, `movements`, `profiles`, `sync_operations`, `app_admins`, bucket `part-images` và toàn bộ policies hiện có. Cụ thể cần biết:

- Tên/cột/kiểu dữ liệu thực tế của `parts` và `movements` (đặc biệt khóa chính, `owner_id`, `photo_path`, timestamp, các trường bắt buộc).
- Cấu trúc hiện tại của `sync_operations`; không được giả định rằng bảng này đã có `operation_id` hoặc payload phù hợp.
- Policy Storage của `part-images` và cách đặt đường dẫn ảnh theo owner.
- Chính sách Admin đọc movement và quyền KTV insert/select.

Sau khi schema được xác minh mới viết migration tương thích; không drop/recreate bảng hoặc policy đang chạy.

## Client sender implementation (02/11/2026)

- `SyncOutboxSender` sends one row at a time in Room enqueue order to `apply_inventory_operation`.
- It checks that the current Supabase Auth user ID equals the outbox `ownerId` before sending; it never sends another KTV's queue under the active session.
- Each RPC carries the persistent `operationId`, operation type, UUID `entityId`, and JSON payload. A timeout leaves the operation retryable with the same UUID; the server ledger makes duplicate delivery idempotent.
- The sender claims rows with `PENDING`/`FAILED` -> `IN_FLIGHT`, marks `SYNCED` only after the RPC returns successfully, and records an error as `FAILED` otherwise. It stops at the first failure to preserve dependencies (for example, part creation before opening stock).
- Part photos are uploaded before `PART_UPSERT` to the private `part-images` bucket at `<owner UUID>/<part sync UUID>.<extension>`; the RPC receives the remote path, never the device's private absolute path. Upload uses upsert so a retry after a network timeout is safe.
- `SyncOutboxWorker` uses WorkManager with a connected-network constraint, exponential retry, an immediate one-time request at login and a 15-minute periodic request. A process mutex prevents concurrent workers in the app process from recovering/claiming the same in-flight row at once.
- The source now also includes `SyncPullWorker` for owner-scoped `pull_inventory_changes` and a separate Admin-global movement feed, with cursor fencing, tombstone application, restore commit handling, and account/role checks. Conflict decision UI and keep-local/keep-server resolver components are present in the source.
- This is a source-level implementation inventory, not proof of runtime correctness. Multi-device push/pull, simultaneous stock changes, conflict resolution, deletion propagation, account switching, photo Storage access, and restore-to-sync handoff still require real Android tests.
- No Supabase migration or live data was changed by the client implementation described here.
