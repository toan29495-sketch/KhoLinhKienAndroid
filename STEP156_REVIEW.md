# Step156 — Tuần tự hóa toàn bộ chu trình tải và áp dụng snapshot cloud (03/11/2026)

## Mục tiêu
Ngăn Coordinator khôi phục thủ công và SyncPullWorker chạy nền cùng lúc tải/xóa các bucket snapshot hoặc cùng áp dụng một snapshot vào kho Room.

## Phát hiện
- `RestoreCloudSnapshotReader` và `RestoreCloudSnapshotApplier` trước đây có hai mutex riêng. Hai mutex này không loại trừ lẫn nhau.
- `RestoreOperationGate` bảo vệ import/resume/abort, nhưng SyncPullWorker không sử dụng gate đó; vì vậy Worker có thể chạm snapshot trong lúc Coordinator chạy.
- Một số nhánh Worker dựa trên `activeRestore` đọc trước đó; journal có thể đã đổi trạng thái trong lúc chờ xử lý.

## Thay đổi
- Thêm `RestoreSnapshotGate.mutex`, khóa dùng chung trong process cho toàn bộ chu trình snapshot.
- Bỏ mutex riêng trong Reader và Applier; khóa do caller sở hữu, tránh hai lớp mutex không tương tác.
- Coordinator giữ shared snapshot gate xuyên suốt download và apply; đọc lại journal sau khi lấy khóa và kiểm tra lại trạng thái ngay trước apply.
- SyncPullWorker dùng cùng gate cho cả nhánh tiếp tục active remote restore và nhánh xử lý `RESTORE_COMMIT`; đọc lại journal, kiểm tra owner/sourceType/manifest/cursor/counts và chỉ apply khi journal là `SNAPSHOT_DOWNLOADED`.
- Nếu reservation `beginRemoteRestoreIfSafe` thất bại, Worker không tiếp tục tải/apply và để page retry.
- Giữ nguyên thứ tự: xác minh snapshot hoàn chỉnh -> Room transaction apply -> cập nhật cursor/journal; không thay đổi server RPC, SQL, schema hoặc dữ liệu live trước bước apply.

## Kiểm chứng
- `verify_step156_snapshot_pipeline_serialization.py`: 15/15 PASS.
- Regression Step140–156: tất cả verifier PASS; Step141 remote-feed SQLite migration smoke PASS.
- `python3 -m compileall -q tools`: PASS.
- Đã cập nhật Step141 verifier để kiểm tra luồng Worker mới có shared gate thay vì phụ thuộc vào lời gọi cũ ngoài gate.

## Giới hạn
- Đây là kiểm tra source/static và SQLite smoke test hiện có; chưa chạy Android coroutine/Room/WorkManager runtime hoặc stress test hai worker thực sự.
- Chưa compile Android, build/cài APK, test trên điện thoại, test hai thiết bị hoặc push GitHub. Không dùng Gradle.
- Không thay đổi Supabase, migration/RPC/Edge/Auth/Storage, tài khoản, dữ liệu server hoặc cloud objects.
