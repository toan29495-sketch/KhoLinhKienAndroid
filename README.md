# Kho Linh Kiện Android — mã nguồn phiên bản hoàn thiện chức năng chính

Ứng dụng Android native, ưu tiên offline, lưu cơ sở dữ liệu Room cục bộ trên điện thoại.

## Chức năng có trong mã nguồn
- Danh sách tồn kho, tìm kiếm theo tên/mã/ghi chú, cảnh báo tồn thấp.
- Quét QR bằng camera; QR có sẵn phải chứa chính xác mã linh kiện.
- Thêm linh kiện, chụp ảnh bằng camera hoặc chọn ảnh có sẵn.
- Tạo ảnh QR PNG cho từng mã; nhập mã hàng loạt và tự sinh ảnh QR.
- OCR ảnh danh sách mã: trích xuất chuỗi 10 ký tự chữ/số để người dùng rà soát trước khi tạo.
- Nhập kho, xuất dùng, kiểm kê số lượng thực tế (cho phép tồn bằng 0); giao dịch được ghi vào lịch sử.
- Sửa tên, đơn vị, ghi chú và ngưỡng tồn.
- Xuất tệp Excel `.xlsx` có hai trang `Tồn kho` và `Lịch sử`.
- Sao lưu ZIP gồm dữ liệu kho, lịch sử và ảnh linh kiện; manifest gắn tài khoản sở hữu và mã SHA-256 để phát hiện tệp hỏng. Khôi phục ZIP kiểm tra dữ liệu trước khi thay thế, giới hạn dung lượng giải nén, từ chối mã linh kiện trùng và chỉ thay dữ liệu của tài khoản đang đăng nhập. Vẫn hỗ trợ khôi phục JSON cũ.

## Quy trình build hiện tại
- Dự án có Gradle Wrapper 8.9 và workflow GitHub Actions tại `.github/workflows/android-validation.yml`.
- Quy trình dự kiến: đưa mã nguồn lên repository đã cấu hình GitHub Actions, chạy workflow để kiểm tra Kotlin và tạo APK debug, sau đó tải artifact về điện thoại để cài thử.
- Người dùng không cần cài Android Studio hoặc thao tác trên PC. Tuy nhiên, workflow chưa được chạy xác nhận cho gói Step208; chưa có bằng chứng APK mới đã được tạo, cài hoặc kiểm thử.
- Môi trường kiểm tra hiện tại không có Android SDK và không tải được Gradle distribution từ `services.gradle.org`; không được coi các kiểm tra Python/source-contract là thay thế cho build Android.

## Quy tắc và lưu ý
- Mã tạo hàng loạt chỉ nhận chuỗi đúng 10 ký tự chữ/số. QR quét sẵn có thể mang mã khác định dạng và vẫn được tra cứu theo đúng nội dung quét.
- OCR có thể nhầm `O/0`, `I/1`, `B/8`; phải rà soát trước khi xác nhận tạo.
- QR sinh ra chứa nguyên văn mã linh kiện; in tem QR nên kiểm tra quét thử trước khi dán.
- Tồn kho ban đầu được ghi thành giao dịch `ĐẦU KỲ`; nhập/xuất/kiểm kê ghi lịch sử và không cho xuất vượt tồn.
- Khôi phục ZIP/JSON sẽ thay thế dữ liệu của tài khoản đang đăng nhập; hãy sao lưu trước. ZIP cũ chưa có ownerId vẫn được hỗ trợ để tương thích, vì vậy chỉ chọn bản sao lưu mà bạn biết rõ nguồn gốc.
- ZIP hiện giới hạn tổng dữ liệu giải nén 300 MB, mỗi ảnh 20 MB và mỗi mục dữ liệu 100 MB để giảm rủi ro tệp nén bất thường.
- Đây là mã nguồn đã hoàn thiện chức năng chính nhưng **chưa được biên dịch hoặc kiểm thử trên thiết bị thật trong môi trường tạo tệp này**. Cần chạy Gradle build và thử camera/QR/OCR/Excel/khôi phục trước khi dùng làm sổ kho chính. Chưa cung cấp APK đã ký.


## Phiên bản 1.2 – chế độ QR linh kiện
- Mặc định linh kiện lẻ: 1 mã QR cho 1 món; khi xuất 1 món, mã được ẩn khỏi danh sách tồn kho nhưng giao dịch vẫn được giữ trong lịch sử.
- Có thể chọn chế độ nhiều món dùng chung một mã QR; xuất kho trừ theo số lượng và mã vẫn hiển thị khi tồn về 0.
- Có nút xóa mã QR riêng kèm xác nhận; chỉ xóa khỏi danh mục, không xóa lịch sử giao dịch.
- Cơ sở dữ liệu nâng từ v1 lên v2 bằng migration, thêm cột chế độ QR và giữ dữ liệu đã có.

## Đồng bộ nhiều KTV — trạng thái mã nguồn Step208
- Room lưu dữ liệu offline và phân vùng theo `ownerId`; Outbox giữ các thao tác chờ gửi với `operationId` ổn định.
- `SyncOutboxSender` gửi thao tác lên RPC `apply_inventory_operation`; `SyncPullWorker` kéo change-feed qua `pull_inventory_changes`; Admin có feed lịch sử riêng `pull_admin_movement_changes`.
- Mã nguồn có xử lý tombstone, revision, idempotency, xung đột và phân vùng kho Admin/KTV; WorkManager lập lịch gửi/kéo dữ liệu khi có mạng.
- Các RPC nền tảng Step138 và các hardening liên quan đã được đối chiếu với catalog Supabase trong các lượt kiểm toán trước. Đây là xác nhận ở mức source/catalog, không phải bằng chứng đồng bộ thật đã chạy ổn định trên nhiều điện thoại.
- **Chưa được xác minh runtime:** đăng nhập nhiều tài khoản trên thiết bị thật, hai thiết bị cùng sửa một linh kiện, offline/retry, xung đột, đổi tài khoản, thu hồi quyền Admin/KTV và tính nhất quán lịch sử.
- Xem `docs/SYNC_DESIGN.md` để biết luồng triển khai hiện tại và các giới hạn còn lại.

- Restore cleanup: see `docs/RESTORE_CLEANUP.md`; local staging is deleted only after Room transaction commits and cursor matches. Cloud Storage objects are never deleted by local cleanup.

### Cloud backup and restore status
- Cloud backup is started only by the explicit **Sao lưu Cloud ngay** action. The request is owner-scoped, waits for network connectivity, and does not enable the periodic weekly schedule.
- The Tools screen can list the signed-in owner's Cloud backups, download only `VERIFIED` entries, verify exact size/SHA-256 and ZIP manifest, and persist the verified archive in a private owner-scoped inbox. A downloaded archive is not restored automatically.
- Restore requires a separate confirmation because it replaces the current owner's inventory and movement history. The resumable `RestoreCloudCoordinator` stages the ZIP, uploads and verifies referenced photos, stages inventory/history chunks in Cloud, commits the restore, reads and verifies the committed snapshot, and only then applies the snapshot in one Room transaction. Other KTV owners are isolated.
- Unfinished owner-scoped restore sessions can be resumed from the Tools screen. After app/process restart, Step198 also schedules WorkManager recovery for already-confirmed, resumable `LOCAL_ZIP` journals; interrupted ZIP extraction still requires selecting the same archive again. `REMOTE_FEED` snapshot recovery remains with `SyncPullWorker`. Local staging cleanup occurs only after the Room apply transaction commits. Legacy JSON direct replacement is disabled so it cannot bypass Cloud commit and snapshot verification.
- This describes the source-level workflow. It is not evidence of a new Android APK/device end-to-end test or a fresh live Cloud restore test for the current Step200 archive.
