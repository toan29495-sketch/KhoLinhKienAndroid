# Step155 — Bảo vệ snapshot cloud khi điện thoại thiếu dung lượng (03/11/2026)

## Rủi ro dự đoán
Sau khi server đã commit restore, Android tải các chunk snapshot đã xác nhận vào các bucket staging riêng trong Room. Nếu bộ nhớ đầy giữa các chunk, lần tải có thể dừng giữa chừng. Live inventory chưa được thay thế, nhưng cần ngăn lần ghi chunk tiếp theo làm cạn vùng lưu trữ và phải giữ trạng thái đủ rõ để retry.

## Thay đổi
- `RestoreCloudSnapshotReader` nhận `Context` để kiểm tra vùng lưu trữ bền vững bằng `StatFs(context.filesDir.absolutePath)`.
- Trước mỗi lần ghi chunk snapshot vào Room, tính kích thước UTF-8 của payload và ước lượng 2x để dự phòng SQLite/WAL, đồng thời giữ tối thiểu 16 MiB trống.
- Kiểm tra lại active Supabase user trước và sau khi ghi mỗi chunk; nếu tài khoản đổi, dừng và không chuyển dữ liệu sang owner mới. Chunk đã ghi vẫn nằm trong staging có ownerId cũ.
- Các chunk chỉ nằm trong `SNAPSHOT_PART`/`SNAPSHOT_MOVEMENT`; retry tiếp tục dùng cơ chế hiện hữu xóa riêng hai bucket snapshot dở dang rồi tải lại từ snapshot đã commit. ZIP payload staging và live inventory không bị xóa.
- Coordinator và SyncPullWorker truyền application context cho reader.
- Không thay đổi schema Room, SQL/RPC, Supabase, dữ liệu server, hoặc luồng commit/apply.

## Kiểm chứng
- `verify_step155_snapshot_low_storage_guard.py`: 15/15 PASS.
- Regression Step140–154: toàn bộ verifier PASS. Hai verifier Step141 được cập nhật assertion constructor để phản ánh việc reader nay cần Context; không thay đổi logic nghiệp vụ của verifier.
- `python3 -m compileall -q tools`: PASS.
- ZIP integrity: PASS.

## Giới hạn
- Đây là source/static verification; chưa mô phỏng ENOSPC thật, SQLite WAL tăng dung lượng, hoặc StatFs trên Android runtime.
- Ước lượng 2x là guard bảo thủ theo kích thước payload, không phải cam kết dự báo chính xác mọi overhead SQLite.
- Chưa compile Android/Room/Compose, chưa build/cài APK, chưa thử trên điện thoại, chưa QR/2 thiết bị, chưa push GitHub. Không dùng Gradle.
- Không thay đổi Supabase SQL/migration/RPC/Edge/Auth/Storage hoặc dữ liệu server; không xóa tài khoản, kho, lịch sử, ảnh hay restore staging.
