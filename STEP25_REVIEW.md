# Step 25 — Khóa thao tác kho trong thời gian khôi phục

## Vấn đề
Phiên khôi phục có journal bền vững và worker đồng bộ đã tạm dừng, nhưng giao diện kho vẫn có thể nhận thao tác thêm/sửa/xóa/nhập-xuất trong lúc phiên restore chưa hoàn tất. Các thao tác mới có thể tạo outbox sau khi coordinator đã kiểm tra outbox ở một phase trước đó; việc này có nguy cơ làm trạng thái kho cục bộ lệch với snapshot đang được áp dụng.

## Đã triển khai
- MainActivity suy ra `restoreLocked` từ `restoreBusy` và journal owner-scoped chưa ở `COMPLETE`/`ABORTED`; trạng thái này tồn tại sau khi ứng dụng khởi động lại.
- Khi đang khóa: chặn mở quét QR và thêm linh kiện từ thanh công cụ; vô hiệu hóa tạo mã hàng loạt và sao lưu ZIP; không cho thao tác lưu/xóa/điều chỉnh tồn kho từ các dialog đang mở.
- Trước các callback ghi dữ liệu, MainActivity đọc lại `activeRestore(ownerId)` để không dựa riêng vào state giao diện có thể cũ.
- Bổ sung kiểm tra `activeRestore(ownerId)` ngay bên trong các DAO transaction `insertPartWithOpeningMovement`, `update`, `deletePartByCode`, `changeStock`. Đây là lớp bảo vệ quan trọng: kiểm tra và thay đổi dữ liệu nằm trong cùng Room transaction, tránh việc thao tác giao dịch thường chen vào khi journal restore đã được tạo.
- Không khóa đọc kho/lịch sử, xuất Excel hoặc thao tác quản trị tài khoản KTV; các thao tác đó không ghi kho owner đang khôi phục.

## An toàn / phạm vi
- Chỉ khóa theo `ownerId` đang có journal hoạt động; tài khoản KTV khác không bị ảnh hưởng.
- `COMPLETE` và `ABORTED` không khóa thao tác.
- Không xóa outbox, không đổi cursor, không thay schema Room và không sửa dữ liệu cloud.

## Chưa xác minh
- Chưa chạy Gradle, Kotlin type-check, unit/instrumentation tests hoặc test đa coroutine trên thiết bị.
- Chưa build APK, chưa chạy Supabase SQL/RPC, chưa deploy và chưa push GitHub.
- Cần kiểm thử tình huống đang mở dialog thì restore bắt đầu, app bị đóng rồi mở lại khi journal còn hoạt động, hai thao tác ghi/khởi tạo restore diễn ra gần đồng thời, và pull worker đang xử lý page thì restore bắt đầu.
- Phạm vi kiểm tra tĩnh: các đường ghi nghiệp vụ hiện dùng (UI add/edit/delete/stock, batch, legacy claim/restore, PART/MOVEMENT pull và cursor pull) đều có guard transaction. Các primitive DAO cấp thấp vẫn tồn tại để phục vụ wrapper/restore snapshot; không được gọi trực tiếp từ UI hoặc worker mới mà bỏ qua wrapper.
