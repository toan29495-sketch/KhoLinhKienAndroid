# Step 26 — Siết kiểm tra tính hợp lệ của lịch sử khi restore cloud

## Nguồn
- Tiếp tục trực tiếp từ `KhoLinhKienAndroid-step25-audited-2026-11-02.zip`.
- Không thay đổi Kotlin, Room schema, UI, worker, Storage path hoặc dữ liệu live.
- Chỉ chỉnh migration SQL chưa được chạy trên Supabase theo nhật ký hiện có.

## Phát hiện
Migration `202611020006_restore_commit_and_snapshot_read.sql` đã kiểm tra movement có UUID, mã linh kiện, tên, loại giao dịch không rỗng, số lượng không âm, thời gian và tham chiếu đến PART trong snapshot. Tuy nhiên, chưa giới hạn `type` vào các loại được ứng dụng sử dụng và chưa kiểm tra quan hệ giữa `before_qty`, `amount`, `after_qty`.

Một ZIP bị sửa có thể vì thế chứa lịch sử như XUẤT nhiều hơn tồn trước, hoặc NHẬP với số tồn sau không bằng tồn trước cộng số nhập. Dữ liệu có thể vượt qua preflight phía Android nếu file được chế tạo có chủ đích và được ghi vào snapshot cloud.

## Đã sửa
Trong bước validate MOVEMENT trước mọi lệnh DELETE/INSERT:
- Chỉ chấp nhận `NHẬP`, `XUẤT`, `KIỂM KÊ`, `ĐẦU KỲ`.
- `NHẬP`, `XUẤT`, `ĐẦU KỲ` phải có amount > 0; `KIỂM KÊ` cho phép amount = 0.
- `NHẬP`: `after_qty = before_qty + amount`.
- `XUẤT`: `before_qty >= amount` và `after_qty = before_qty - amount`.
- `KIỂM KÊ`: `after_qty = amount`.
- `ĐẦU KỲ`: `before_qty = 0` và `after_qty = amount`.
- Phép cộng/trừ so sánh bằng bigint để tránh tràn số nguyên trong biểu thức kiểm tra.

## Kiểm tra phạm vi
- Đối chiếu các loại giao dịch với `MainActivity.kt` và `Data.kt`: ba loại thao tác người dùng và giao dịch đầu kỳ khi tạo linh kiện.
- Điều kiện kiểm tra nằm trong phần pre-validation, trước khi SQL xóa dữ liệu `movements`/`parts`; lỗi sẽ làm transaction rollback, không thay kho cloud.
- Không sửa các migration đã được ghi nhận là đã triển khai; nhật ký hiện tại cho biết các migration đồng bộ/restore chưa chạy trên Supabase thật.

## Chưa thực hiện / chưa xác minh
- Chưa chạy PostgreSQL parser hoặc migration trên Supabase staging/live.
- Chưa chạy test SQL tự động cho các trường hợp hợp lệ/không hợp lệ.
- Chưa chạy Gradle, build APK hoặc kiểm thử điện thoại.
- Chưa deploy Edge Function, chưa push GitHub.

## Bộ test SQL cần chạy ở bước kiểm thử staging
1. NHẬP 4 + tồn 6 => tồn sau 10: chấp nhận.
2. XUẤT 3 + tồn 6 => tồn sau 3: chấp nhận.
3. XUẤT 7 + tồn 6: từ chối.
4. KIỂM KÊ 0 => tồn sau 0: chấp nhận.
5. ĐẦU KỲ 5, tồn trước 0, tồn sau 5: chấp nhận.
6. Loại giao dịch ngoài danh sách: từ chối.
7. NHẬP/XUẤT có after_qty sai: từ chối.
8. Mỗi trường hợp bị từ chối phải bảo đảm snapshot live trước đó không đổi.
