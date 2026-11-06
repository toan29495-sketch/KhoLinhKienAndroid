# Step143 – Dọn cache lịch sử Admin khi hạ quyền (2026-11-03)

## Baseline và phạm vi
- Tiếp tục từ archive Step142 đã giải nén và kiểm tra ZIP; không quay lại hoặc ghi đè bằng checkpoint cũ.
- Phạm vi chỉ là thay đổi Android source + verifier. Không có SQL/migration, Supabase RPC/Edge Function, Auth, Storage hay dữ liệu máy chủ nào bị thay đổi.
- Không chạy Gradle, Android Studio, Android SDK, emulator hoặc build APK.

## Vấn đề và thay đổi
- Step135/136 đã xác nhận UI xóa danh sách `adminHistoryRows`, watermark và tab Admin khi profile chuyển sang KTV; WorkManager cũng hủy hai global pull worker.
- Tuy nhiên các movement do Admin-global feed tải về vẫn nằm trong Room. Dù truy vấn KTV đã owner-scope, việc giữ bản ghi của KTV khác trên thiết bị sau khi mất quyền Admin là không cần thiết.
- Bổ sung `InventoryDao.clearForeignMovementCache(ownerId)`, chỉ xóa các movement có `ownerId` khác tài khoản đang đăng nhập. Lịch sử của chính tài khoản Admin/KTV được giữ lại.
- Khi profile là KTV, MainActivity chạy cleanup trên IO dispatcher và xóa riêng cursor `admin-global:<ownerId>`. Việc reset cursor cho phép nếu tài khoản được cấp Admin lại sau này, ứng dụng tải lại toàn bộ global feed thay vì bỏ qua lịch sử đã xóa khỏi cache.
- Không xóa server history, không xóa profile, không xóa kho cá nhân, không xóa outbox, không xóa cursor cá nhân. Scheduler vẫn hủy Admin-global one-time và periodic workers.

## Kiểm thử
- `tools/verify_step143_admin_downgrade_local_cache.py`: 11/11 PASS.
- SQLite smoke: giữ movement của tài khoản hiện tại; xóa movement foreign-owner; xóa đúng Admin-global cursor; giữ cursor owner khác.
- Regression: Step142 12/12; Step141 remote contract 15/15; Step141 remote restore feed 13/13 + SQLite migration 12→13; Step140 cloud-photo 13/13; Step135 15/15; Step136 14/14; Step133 45/45; Step134 29/29; Step132 53/53; Step131 monotonic cursor; Step130 29/29; Step138 remote alignment 24/24.
- `python -m compileall -q tools`: PASS.
- Read-only Supabase count audit sau thay đổi source: 2 profiles, 1 Admin link; parts, movements, sync_operations, sync_events, restore sessions/chunks/commits đều 0.

## Giới hạn chưa kiểm thử
- Chưa chạy Room instrumentation/Android runtime; source verifier và SQLite smoke không thay thế kiểm thử Android thực tế.
- Chưa build/cài APK, chưa test QR/2 thiết bị, chưa push GitHub.
- Không thay đổi Supabase live; `admin-users` vẫn ACTIVE v3, candidate v4 chưa deploy.
