# Step157 — Audit và thiết kế sao lưu Cloud tự động hàng tuần (03/11/2026)

## Kết luận
Nên triển khai tự động sao lưu riêng cho từng owner/KTV. Admin là một owner có kho cá nhân như KTV, đồng thời chỉ được xem trạng thái sao lưu của các KTV khác qua quyền Admin đã xác thực. Không tạo một ZIP tổng hợp tất cả kho và không cho KTV đọc bản sao của người khác.

## Audit mã nguồn hiện tại
- `BackupManager.saveBackup()` đã tạo ZIP v2 có manifest, SHA-256 từng mục, ảnh, kiểm tra lại ZIP và Central Directory. Nhưng hiện tại nó chỉ xuất file vào Downloads/MediaStore hoặc thư mục app-specific trên Android 8/9; chưa có luồng upload ZIP backup lên Cloud.
- `createZip()` đang private và nhận danh sách `parts`/`movements` từ caller. UI truyền dữ liệu Compose đang hiển thị; cách này không phù hợp cho WorkManager chạy nền và không bảo đảm một snapshot DB nhất quán.
- DAO hiện có Flow `partsForOwner(ownerId)` và `movementsForOwner(ownerId)`, `countUnsentOutbox(ownerId)`, `getSyncCursor(ownerId)`. Chưa thấy API snapshot suspend đồng bộ trong một Room transaction để worker lấy một bộ dữ liệu cố định.
- WorkManager đã có scheduler theo owner, có unique work, network constraint, retry và hủy theo owner. Có thể mở rộng nhưng cần worker riêng cho backup, không gộp vào SyncOutboxWorker.
- ZIP hiện đã bao gồm ảnh: cloud photos được tải vào cache tạm, đưa vào ZIP rồi cache bị xóa trong `finally`. Có thể tái sử dụng serializer này, không cần giữ ảnh cloud thành bản cache thường trực.
- Supabase hiện có `part-images` private và `app-updates` public; chưa có bucket backup riêng hoặc metadata/retention API trong source/live audit hiện tại. Không dùng bucket public `app-updates` để chứa dữ liệu kho.

## Thiết kế đề xuất

### 1. Chu kỳ và điều kiện chạy
- Một unique periodic WorkManager job cho mỗi `ownerId`, chu kỳ 7 ngày.
- Constraints: mạng CONNECTED, đang sạc, pin không ở trạng thái LOW. Có thể retry khi máy offline hoặc điều kiện chưa đạt.
- Lưu `lastSuccessfulBackupAt`, `lastBackupCursor`, trạng thái và lỗi gần nhất theo owner. Không cập nhật thành công chỉ vì worker đã được enqueue hoặc ZIP đã tạo xong.
- Khi đăng xuất/đổi tài khoản/hạ quyền, hủy job của owner cũ; worker luôn xác minh session và active profile trước/sau các ranh giới I/O.

### 2. Nguồn dữ liệu nhất quán
- Worker không lấy danh sách từ Compose/UI.
- Thêm DAO query snapshot và lấy parts + movements + cursor + outbox count trong cùng Room transaction.
- Chỉ bắt đầu đóng gói khi owner không có outbox chưa gửi, không có restore đang chạy và feed owner đã được kéo đến trạng thái hiện hành. Nếu chưa đồng bộ xong, worker retry; không sao lưu bản thiếu giao dịch.
- Ghi cursor, số lượng dòng, ownerId, timestamp và checksum vào manifest/metadata để biết chính xác bản sao đại diện cho thời điểm nào.

### 3. Cloud object và quyền
- Tạo bucket riêng `inventory-backups`, private, không public URL.
- Object path theo owner, ví dụ `{ownerId}/weekly/{timestamp}-{backupId}.zip`; không nhận path do client tự ý chọn ngoài owner prefix.
- Tạo bảng metadata backup có ownerId, backupId, kind (WEEKLY/MONTHLY/PRE_UPDATE), objectPath, checksum, bytes, partCount, movementCount, snapshotCursor, createdAt, verifiedAt, status.
- Client chỉ được ghi/đọc metadata của chính owner; Admin xem metadata mọi owner qua RPC kiểm tra active profile + app_admins link. Admin không cần quyền tải ZIP của KTV khác.
- Không tái sử dụng `part-images` vì bucket đó dành cho ảnh và policy path hiện tại; không dùng `app-updates` vì là public.

### 4. Xác minh và retention
- Tạo ZIP trong cache riêng; không ghi vào Downloads tự động.
- Upload xong phải xác minh object tồn tại, kích thước và SHA-256; chỉ sau đó đánh dấu VERIFIED và mới cập nhật last-success.
- Retention đề xuất: giữ 4 bản tuần gần nhất + 1 bản tháng gần nhất + 1 bản trước cập nhật lớn. Nếu các mốc trùng cùng một object thì chỉ giữ một bản.
- Không xóa bản cũ trước khi bản mới được xác minh. Dọn bản quá hạn qua RPC server-side owner-bound; client không được cấp quyền xóa object tùy ý. Giữ tối thiểu một bản VERIFIED nếu đã từng có bản thành công.

### 5. Khôi phục
- Bản tự động phải xuất hiện riêng trong danh sách “Sao lưu Cloud”, phân biệt với ZIP thủ công.
- Khi chọn khôi phục, tải bản ZIP vào cache tạm, xác minh checksum/owner/manifest, rồi chuyển vào pipeline restore hiện tại. Không tự động ghi đè kho khi backup vừa hoàn tất.
- Không lấy dữ liệu tổng hợp Admin-global làm backup kho cá nhân; mỗi bản chỉ chứa owner tương ứng.

## Các bước triển khai tiếp theo
1. Tạo DAO transaction snapshot + worker skeleton và verifier source-level.
2. Refactor BackupManager để tái sử dụng ZIP serializer cho OutputStream/File, giữ nguyên luồng xuất ZIP thủ công.
3. Chuẩn bị migration candidate cho private bucket, metadata table, RLS, RPC upload registration/list/retention và Admin status. Chưa áp dụng lên Supabase thật nếu chưa có xác nhận riêng.
4. Nối WorkManager schedule/cancel theo owner và trạng thái profile.
5. Bổ sung UI trạng thái sao lưu và luồng chọn bản Cloud để khôi phục.
6. Test checksum, owner isolation, retry, process death, outbox pending, retention và ảnh; sau đó mới xin phép triển khai backend.

## Ranh giới an toàn
- Không dùng `app-updates` public.
- Không cấp service-role key cho APK.
- Không cho client xóa object backup cũ trực tiếp.
- Không tự động restore.
- Không xóa dữ liệu Supabase hiện có, user/profile, bucket hoặc ảnh linh kiện.
- Mọi migration/bucket/policy/RPC mới trên Supabase thật cần được báo rõ và xin xác nhận trước khi triển khai.
