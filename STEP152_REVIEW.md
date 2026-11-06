# Step152 — Bảo vệ khôi phục ZIP khi điện thoại thiếu dung lượng (03/11/2026)

## Phạm vi
Tiếp tục trực tiếp từ Step151. Chỉ sửa source local ZIP restore và bổ sung verifier. Không thay đổi schema Room, Supabase, Storage, Auth, RPC, dữ liệu máy chủ hoặc luồng áp dụng kho đang hoạt động.

## Phát hiện và thay đổi
- Trước đây ZIP restore chỉ kiểm tra tối thiểu 32 MB trống lúc bắt đầu. Trong quá trình giải nén, chép ảnh từ cache sang durable staging hoặc ghi các batch Room, dung lượng có thể cạn giữa chừng.
- Bổ sung kiểm tra dung lượng động trước mỗi lần ghi buffer ZIP và mỗi batch Room, luôn giữ lại ngưỡng dự phòng 16 MB.
- Trước khi tạo durable staging, tính tổng kích thước ảnh và kiểm tra đủ chỗ cho bản sao thứ hai cộng 16 MB dự phòng, vì cache extraction và durable staging cùng tồn tại trong giai đoạn chuyển tiếp.
- Khi chép ảnh, kiểm tra dung lượng theo từng buffer và xác nhận kích thước đích bằng nguồn.
- Nếu hết dung lượng, thao tác dừng trước lần ghi tiếp theo; live inventory vẫn chưa bị áp dụng. `finally` vẫn dọn cache ZIP tạm. Durable staging dở dang được giữ cùng journal `STAGING`/`FAILED` để retry theo Step151 hoặc hủy theo lifecycle hiện có.
- Nếu thiết bị hết dung lượng khiến việc ghi journal `FAILED` cũng lỗi, giữ nguyên exception gốc; journal `STAGING` (nếu đã tồn tại) hoặc orphan staging sẽ được luồng recovery hiện tại xử lý.

## Kiểm chứng
- `verify_step152_restore_low_storage_guard.py`: 15/15 PASS; gồm source contract và boundary model cho reserve, photo-copy preflight.
- Regression Step140–152: PASS. Các verifier đã chạy: Step140 13/13; Step141 restore remote contract 15/15; Step141 remote feed 13/13 + SQLite migration smoke PASS; Step142 12/12; Step143 11/11; Step144 14/14; Step145 12/12; Step146 12/12; Step147 12/12; Step148 31/31; Step149 14/14; Step150 21/21; Step151 12/12; Step152 15/15.
- `python3 -m compileall -q tools`: PASS.

## Giới hạn xác minh
Đây là source/static checks và boundary model. Chưa mô phỏng ENOSPC thật trên Android, chưa kiểm tra `StatFs`/Room/SAF runtime, chưa test trên điện thoại. Chưa compile Android, chưa build/cài APK, chưa QR/2 thiết bị, chưa push GitHub. Không dùng Gradle.

## An toàn dữ liệu
Không xóa hoặc sửa live inventory, lịch sử, outbox, tài khoản, cloud objects, restore sessions/chunks/commits hoặc dữ liệu Supabase. Không thay đổi server.
