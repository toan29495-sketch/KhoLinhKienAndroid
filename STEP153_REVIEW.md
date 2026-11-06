# Step153 — Tuần tự hóa Hủy/Import/Resume khôi phục (03/11/2026)

## Phỏng đoán rủi ro
- Người dùng có thể chạm nhanh nhiều lần, thao tác resume/import được gọi gần nhau, hoặc một luồng hủy staging chạy cùng lúc với import/resume.
- `RestoreCloudCoordinator` đã có Mutex process-wide cho import/resume, nhưng `RestoreStagingLifecycle.abort()` trước đây không dùng cùng khóa. Nếu abort được gọi từ một tác vụ khác đúng lúc coordinator đang đọc/ghi staging, có thể xóa journal/rows/files trong khi luồng import còn sử dụng.
- Việc rời tab Tools không tự hủy coroutine vì thao tác được khởi chạy từ scope cấp màn hình chính; nếu Activity/process bị hủy, `CancellationException` trong ZIP giữ journal `STAGING` và batch đã commit để retry.
- Không có nút Hủy đang hiển thị khi restore chạy; do đó không tạo thêm nút hủy giữa pha cloud commit, tránh cho người dùng nhầm rằng có thể rollback một commit server đã bắt đầu.

## Thay đổi
- Tách khóa thành `RestoreOperationGate.mutex`, dùng chung giữa `RestoreCloudCoordinator.importAndRestore`, `resume` và `RestoreStagingLifecycle.abort`.
- Abort giữ khóa xuyên suốt kiểm tra owner/trạng thái, transaction xóa journal + staged rows, và xóa đúng thư mục staging. Import/resume không thể chen vào giữa.
- Giữ conditional DAO DELETE chỉ cho `STAGING`, `STAGED`, `FAILED` như lớp bảo vệ thứ hai. Các trạng thái cloud/commit không thể abort.
- Không thay đổi schema Room, RPC, Supabase, Storage, dữ liệu kho thật hoặc idempotency.
- Không thêm nút Hủy UI; hủy thủ công chỉ được giữ ở API lifecycle nội bộ và luôn bị khóa an toàn. Nếu người dùng rời màn hình, tiến trình tiếp tục trong scope gốc; nếu process chết, journal vẫn hỗ trợ phục hồi.

## Kiểm chứng
- `verify_step153_restore_abort_serialization.py`: 14/14 PASS; xác minh cùng gate được dùng bởi import/resume/abort, owner fence, trạng thái được phép hủy, transaction atomic, file cleanup nằm trong critical section và mô hình tuần tự không overlap.
- Regression: Step140 13/13; Step141 remote contract 15/15; Step141 remote feed static 13/13 (+ SQLite migration smoke PASS); Step142 12/12; Step143 11/11; Step144 14/14; Step145 12/12; Step146 12/12; Step147 12/12; Step148 31/31; Step149 14/14; Step150 21/21; Step151 12/12; Step152 15/15 — PASS.
- `python3 -m compileall -q tools`: PASS.

## Giới hạn
- Đây là source/static verification và mô hình tuần tự hóa; chưa chạy coroutine/Room runtime trên Android, chưa mô phỏng tap storm hoặc Activity/process death thật.
- Chưa compile Android, chưa build/cài APK, chưa test điện thoại, chưa push GitHub. Không dùng Gradle.
- Không thay đổi Supabase hoặc dữ liệu máy chủ.
