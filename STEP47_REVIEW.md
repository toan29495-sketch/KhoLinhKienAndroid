# Step 47 — Cô lập object ảnh theo từng thao tác Outbox

## Nguồn và phạm vi
- Nguồn mã đầy đủ được khôi phục: `KhoLinhKienAndroid-step26-restore-movement-validation-2026-11-02.zip`.
- Đây là bản source đầy đủ mới nhất còn truy cập được trong phiên; các báo cáo Step27–Step46 trong thư mục audit khác chưa có source tương ứng để chứng minh đã tích hợp.
- Chỉ sửa `SyncOutboxSender.kt`; không thay Room schema, UI, SQL migration hoặc dữ liệu cloud.

## Phát hiện xác minh trực tiếp
- Trước sửa, `uploadPartPhotoIfPresent()` tạo object path `$ownerId/$partSyncId.$extension` và upload `upsert = true`.
- Mọi lần thay ảnh của cùng một linh kiện và cùng extension đều có thể ghi đè object trước đó.
- Mỗi row Outbox đã có `operationId` ổn định, được gửi cùng RPC; vì vậy có thể dùng nó làm thành phần của object path.

## Thay đổi
- Truyền `operation.operationId` vào hàm upload ảnh.
- Đổi path thành `$ownerId/$partSyncId/$operationId.$extension`.
- Retry cùng row Outbox tiếp tục upload vào cùng path; thao tác thay ảnh mới có `operationId` khác nên không ghi đè object của thao tác trước.
- Path vẫn nằm dưới thư mục UID cấp đầu tiên, tương thích Storage policy hiện có.

## Kiểm tra thực hiện
- Kiểm tra source bằng script: call site truyền `operationId`; signature nhận `operationId`; path có đủ ownerId/partSyncId/operationId; không còn path cũ trong sender.
- Kiểm tra policy Storage: chỉ ràng buộc thư mục đầu tiên bằng `auth.uid()`, nên path có thêm thư mục con vẫn nằm trong phạm vi owner.
- Không chạy PostgreSQL/Supabase thật, không upload object, không build APK, không test thiết bị, không push GitHub.

## Giới hạn còn lại
- Ảnh cũ hiện chưa được dọn tự động. Đây là rò rỉ dung lượng có thể xảy ra, nhưng an toàn hơn xóa nhầm ảnh còn được tham chiếu.
- PART_DELETE chưa có quy trình dọn object có đối soát tham chiếu.
- Cần thiết kế garbage collection có kiểm kê tham chiếu từ `parts`, restore snapshot đang hoạt động và các Outbox chưa hoàn tất; chỉ xóa sau khoảng an toàn và xác minh owner.
- Conflict giữa hai thiết bị khi sửa metadata/ảnh vẫn chưa có server-version optimistic concurrency được nối hoàn chỉnh trong source Step26.
