# Bảng xử lý tồn đọng hợp nhất — mốc Step208

Ngày chốt: 04/11/2026  
Nguồn chuẩn: `KhoLinhKienAndroid-step208-backup-trust-model-20261104.zip`  
SHA-256: `fa8e6893bb946ab7cc544b1c25da48c6fcaddbe880b5c8cf530d002400f68b3c`

## Nguyên tắc điều hành

- Tạm dừng tính năng mới. Chỉ sửa lỗi, kiểm chứng và hoàn thiện các luồng đã có.
- Không tạo Step lẻ cho từng chỉnh sửa tài liệu. Dùng bảng này làm sổ tồn đọng duy nhất; chỉ ghi một mốc mới khi có thay đổi mã nguồn đáng kể hoặc nhóm việc được đóng.
- Phân biệt bốn trạng thái: **có trong source**, **đối chiếu catalog/backend**, **đã build**, **đã chạy trên thiết bị**. Không nâng trạng thái nếu thiếu bằng chứng.
- Không deploy Supabase, sửa dữ liệu, Storage/Auth, migration history hoặc push GitHub nếu chưa có yêu cầu/cho phép rõ ràng.
- Mọi thao tác restore chỉ được thay thế kho của owner đang đăng nhập; Admin xem lịch sử toàn hệ thống không có nghĩa được ghi đè kho KTV khác.

## Thay đổi đã triển khai trong source — Step209 (05/11/2026)
- App-side session age guard: 30 ngày từ lần đăng nhập thành công, theo owner, kiểm tra khi cold start và foreground; không xóa dữ liệu nghiệp vụ khi hết hạn. Timestamp thiếu ở lần nâng cấp đầu tiên yêu cầu đăng nhập lại.
- Automatic cloud backup: WorkManager periodic 30 ngày, được kích hoạt sau khi profile xác minh và hoàn tất forced-password-change gate; `UPDATE` cập nhật lịch cũ, giữ enqueue time; yêu cầu mạng CONNECTED.
- Đây mới là source-level implementation. Không có APK/device runtime verification. Client-side expiry không phải server-side enforcement; cần cấu hình Supabase Auth Time-box user sessions 30 ngày nếu gói dự án hỗ trợ (Supabase docs hiện nêu tính năng này chỉ có Pro+). Project settings chưa được đọc/đổi.
- Verifier mục tiêu: Step145 12/12, Step164 23/23, Step165 12/12, Step171 11/11, Step175 18/18, Step179 12/12, Step208 9/9, Step209 12/12; regression Steps190–209: 19 scripts, 314/314; Python compileall PASS.

## Ưu tiên và điều kiện đóng

### P0 — Build được APK từ đúng nguồn mới nhất

**Hiện trạng:** Gradle Wrapper 8.9, AGP 8.7.3, Kotlin 2.0.21 và workflow GitHub Actions đã có trong source. Môi trường làm việc hiện tại thiếu Android SDK/JDK17 và không phân giải được `services.gradle.org`; lần chạy Gradle trước dừng ở tải distribution, chưa tới bước biên dịch Kotlin.

**Cần làm:**
1. Chạy workflow Android validation trên đúng commit chứa Step209 hoặc bản kế tiếp được duyệt.
2. Xác nhận `compileDebugKotlin` và `assembleDebug` đều thành công.
3. Lưu artifact APK, SHA-256, commit SHA và log workflow.
4. Không coi parser Kotlin, Python verifier hoặc ZIP integrity là build APK.

**Đóng khi:** có log workflow thành công và APK artifact gắn với đúng commit. Cài/test điện thoại là gate riêng bên dưới.

### P0 — Kiểm thử ứng dụng thật trên điện thoại

**Hiện trạng:** chưa có APK Step208 được xác nhận cài đặt; chưa có kiểm thử Android runtime cho Compose, Room migration, WorkManager, camera/QR/OCR, file picker, Storage hay các luồng đồng bộ/restore.

**Cần làm theo thứ tự:**
1. Cài APK và xác minh khởi động, nâng Room DB hiện có, đăng nhập/đăng xuất, đổi mật khẩu bắt buộc.
2. Kiểm tra Admin đồng thời là owner kho cá nhân; KTV chỉ thấy kho của mình; Admin global history chỉ là quyền xem.
3. Kiểm tra tạo/sửa/xóa linh kiện, nhập/xuất/kiểm kê, QR thiết bị và lịch sử.
4. Chạy offline rồi online; xác minh Outbox, push/pull, retry, tombstone, xung đột và đổi tài khoản.
5. Kiểm thử Backup/Restore với file nhỏ và lớn, ảnh local/cloud, mất mạng, app bị kill, thiếu dung lượng; xác minh dữ liệu KTV khác không đổi.

**Đóng khi:** có biên bản test theo ca, kết quả thực tế và không còn lỗi chặn phát hành. Các ca chưa thể chạy phải được ghi rõ, không tự đánh dấu PASS.

### P0 — Backup/Restore và bảo toàn dữ liệu

**Đã có trong source:** Cloud backup có journal/worker, owner-scoped catalog/inbox, download và kiểm tra size/SHA-256; Cloud restore có xác nhận riêng, journal, staging theo chunk, commit, đọc/xác minh snapshot rồi thay Room trong transaction; có recovery và gate ngăn sync chạy đồng thời.

**Đã xác minh:** các verifier source-contract gần nhất cho Step190–208 đạt 302/302 checks (18 scripts). Đây không phải kiểm thử runtime.

**Còn phải xác minh:**
- Tạo backup thật, upload/download Storage, readback SHA-256 và trạng thái `VERIFIED`.
- Restore thật với dữ liệu lớn/ảnh; mất mạng ở từng phase; process death; retry commit response không rõ; dung lượng thấp.
- So sánh chính xác số parts/movements/ảnh và cursor sau restore.
- Xác nhận restore chỉ thay owner hiện tại, giữ kho owner khác nguyên vẹn.

**Ranh giới tin cậy:** `VERIFIED` hiện dùng `CLIENT_DOWNLOAD_SHA256`: app chính thức tải đọc lại và hash; RPC backend kiểm tra metadata/size/digest đã đăng ký nhưng không tự hash bytes. Restore client vẫn bắt buộc tự kiểm tra SHA-256 và payload. Chưa cần triển khai server-side verifier nếu chưa có quyết định riêng; đây không phải blocker cho kiểm thử app chính thức, nhưng phải ghi rõ ý nghĩa trạng thái.

### P1 — Supabase và đồng bộ nhiều KTV

**Đã có trong source:** `SyncOutboxSender`, `SyncPullWorker`, WorkManager scheduling, idempotency operation UUID, cursor, tombstone, xử lý xung đột, Admin global feed, owner/session fencing.

**Đã đối chiếu backend:** RPC Step138, Step187, Step204/205 cùng grants/RLS liên quan đã được kiểm tra catalog trong các lượt trước. Không thay đổi backend trong bước hợp nhất này.

**Còn phải kiểm thử:**
- Hai thiết bị cùng một owner và nhiều owner khác nhau.
- Hai thiết bị đồng thời xuất/nhập cùng linh kiện; server tuần tự hóa và tồn không âm.
- Mất mạng sau khi server commit nhưng trước khi client nhận response; retry không nhân đôi movement.
- Xung đột sửa/xóa, tombstone, Admin role downgrade và token/session cũ.
- Storage photo upload/download, retry, đổi ảnh, xóa ảnh và owner isolation.

### P1 — Kiểm toán bảo mật runtime

**Đã hoàn thành:** đọc tĩnh từng định nghĩa của 20 RPC `SECURITY DEFINER` được `authenticated` gọi; phân loại 6 trigger functions và 1 event trigger; không thấy anon/PUBLIC EXECUTE trong catalog snapshot đã kiểm tra; fixed search_path.

**Còn thiếu:** authenticated adversarial tests, malicious payload/fault injection, concurrency tests, role downgrade/session tests, và xác minh Storage/RLS qua client thực. Chưa đánh dấu kiểm toán bảo mật đóng.

### P2 — Hòa giải lịch sử migration Step139

**Hiện trạng:** live catalog có các restore objects Step139, nhưng 9 version strings Step139 không có trong migration history snapshot mới nhất. 25 file SQL pending là tập lịch sử pha trộn, không thể ghép 1:1 có căn cứ vào 9 version và transaction boundaries.

**Cách xử lý:** giữ nguyên file pending; lập mapping chỉ khi có bằng chứng từ backup/source control/deployment logs. Không replay, không `migration repair`, không tạo migration giả để làm đẹp lịch sử.

**Đóng khi:** có chứng cứ provenance đáng tin cậy hoặc ghi nhận chính thức là không thể khôi phục chính xác; không nhất thiết phải thay đổi production.

### P2 — Dọn cảnh báo và tối ưu sau khi P0/P1 ổn định

- Đánh giá FK indexes thiếu trên audit actor/target IDs bằng workload thực trước khi thêm.
- Theo dõi RPC Admin backup status không phân trang khi dữ liệu tăng.
- Đánh giá leaked-password protection và Advisor warnings; không cấp quyền rộng hoặc xóa index chỉ để làm sạch cảnh báo.

## Những việc không làm trong giai đoạn này

- Không thêm tính năng mới hoặc thay đổi UI ngoài sửa lỗi phát hiện trong kiểm thử.
- Không triển khai Edge Function xác minh SHA-256 server-side khi chưa có thiết kế/đo tải/duyệt.
- Không chạy `supabase db push`, không replay 25 migration pending, không sửa migration history theo phỏng đoán.
- Không xóa dữ liệu test hoặc Storage objects từ xa.
- Không push GitHub nếu chưa được yêu cầu.

## Bằng chứng kiểm tra tại mốc hợp nhất

- ZIP Step208 SHA-256 khớp: `fa8e6893bb946ab7cc544b1c25da48c6fcaddbe880b5c8cf530d002400f68b3c`.
- `python -m compileall -q tools`: PASS.
- Verifier Step190–208: 18 scripts, 302/302 assertions PASS.
- Các kiểm tra trên là source-contract checks; không thay thế Kotlin/Gradle build hoặc Android/Supabase runtime tests.
