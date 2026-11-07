# Tiến độ tiếp tục dự án Kiểm soát linh kiện

## Nguồn làm việc
- Nguồn nền đã đối chiếu: ZIP `KhoLinhKienAndroid-multi-ktv-working-2026-11-01-r2.zip` (mới nhất trong các ZIP đã nhận). Working copy hiện tại chứa các thay đổi tiếp theo cho an toàn sao lưu/khôi phục.
- Chưa có commit/push GitHub mới nào được tạo trong lượt làm việc này.
- Không có `local.properties` chứa cấu hình Supabase trong ZIP; không ghi đè cấu hình thật bằng giá trị giả.

## Đã chỉnh sửa trong working copy
- `Data.kt`: nâng Room schema từ v2 lên v3; thêm `ownerId` cho Part và Movement.
- Tạo unique index theo cặp `(ownerId, code)` để các KTV có thể dùng cùng mã linh kiện trong kho riêng.
- DAO truy vấn kho/lịch sử theo chủ sở hữu; Admin có luồng đọc lịch sử toàn hệ thống.
- Có bước gán dữ liệu cũ chưa có `ownerId` cho tài khoản đầu tiên đăng nhập sau migration.
- `MainActivity.kt`: lọc kho theo tài khoản; KTV chỉ xem lịch sử của mình; Admin xem lịch sử toàn hệ thống; thao tác thêm/xóa/xuất/nhập và khôi phục gắn với `profile.id`.
- `ZipRestore.kt`: khôi phục chỉ thay thế dữ liệu của tài khoản đang đăng nhập, không xóa kho KTV khác.
- `BackupManager.kt`: sửa lỗi writer đóng ZIP output stream quá sớm; flush và đóng từng ZIP entry đúng cách.

## Chưa xác minh / còn việc
- Chưa biên dịch được: Gradle Wrapper cần tải Gradle 8.9 nhưng môi trường hiện tại không có kết nối tới `services.gradle.org`.
- Chưa chạy trên điện thoại; chưa được coi là APK hoạt động.
- Ghi chú ở thời điểm cập nhật cũ: các màn hình và Edge Function quản trị chưa có; các phần này được bổ sung ở những mục tiến độ phía dưới.
- Chưa thấy luồng đồng bộ Supabase cho `parts`/`movements`; hiện các thay đổi ownerId mới bảo vệ phân tách dữ liệu cục bộ Room, chưa chứng minh phân tách dữ liệu trên cloud.
- Các việc còn lại được cập nhật theo từng mục bên dưới; cần kiểm thử migration từ DB v1/v2 và chạy build GitHub Actions khi môi trường có Gradle.

## Phân biệt trạng thái
- Đã sửa mã nguồn trong working copy: Có.
- Đã biên dịch thành công: Chưa.
- Đã tạo APK mới: Chưa.
- Đã cài/kiểm thử trên điện thoại: Chưa.
- Đã commit/push GitHub: Chưa.

## Cập nhật tiếp theo — tích hợp luồng đăng nhập và Admin (01/11/2026)
- `MainActivity.kt`: khôi phục phiên Supabase khi mở app; kiểm tra hồ sơ đang hoạt động; buộc đổi mật khẩu trước khi vào kho; thêm đăng xuất; thêm tab Quản trị chỉ dành cho Admin.
- Admin vẫn vào cùng màn hình kho cá nhân; tab Lịch sử của Admin lấy toàn bộ giao dịch; tab Công cụ chỉ xuất/sao lưu kho và lịch sử của chính Admin. KTV thường chỉ có 3 tab kho/lịch sử/công cụ của mình.
- Chỉ tài khoản Admin mới được nhận dữ liệu cũ có `ownerId=''` sau nâng cấp, tránh KTV đăng nhập trước vô tình nhận kho cũ.
- `AuthRepository.kt`: chuyển việc gỡ cờ `must_change_password` sang RPC `complete_password_change` sau khi Auth cập nhật mật khẩu thành công.
- Thêm `supabase/migrations/202611010001_complete_password_change.sql`; cần chạy migration này trong Supabase SQL Editor trước khi thử chức năng đổi mật khẩu.
- `app/build.gradle.kts`: không còn làm Gradle configuration thất bại chỉ vì thiếu `local.properties`; GitHub Actions hiện đã tạo file này từ Secrets.
- Sửa cảnh báo khôi phục để nói rõ chỉ thay dữ liệu của tài khoản đang đăng nhập.

## Kiểm chứng sau cập nhật
- Đã rà soát cấu trúc điều hướng và các điểm gọi `TopAppBarCompat`; chỉ có một nơi gọi và đã truyền callback đăng xuất.
- `git diff --check` không áp dụng được vì thư mục ZIP không có metadata Git.
- Chạy `./gradlew assembleDebug --offline`: chưa chạy được do Gradle Wrapper phải tải Gradle 8.9 từ `services.gradle.org`, môi trường hiện tại không phân giải được máy chủ (UnknownHostException). Đây là chưa kiểm chứng build, không được ghi nhận là build thành công hay lỗi Kotlin.
- Chưa chạy migration trên Supabase; chưa xác minh Edge Function `admin-users`; chưa build APK, chưa cài/kiểm thử điện thoại, chưa commit/push GitHub.
- Kiểm tra cú pháp bằng `kotlinc` độc lập: không phát hiện lỗi parser Kotlin; do thiếu Android/Compose/Supabase dependencies nên đây không phải kiểm tra type-check hoặc build.
- Lịch sử Admin hiện hiển thị toàn hệ thống ở chế độ đọc; thao tác quản trị chỉnh sửa/xóa lịch sử dành riêng Admin vẫn chưa triển khai và không được tính là hoàn thành.

## Bổ sung mã nguồn Edge Function quản trị (01/11/2026)
- Thêm `supabase/functions/admin-users/index.ts` tương thích tên hàm mà `AuthRepository` đang gọi.
- Hàm xác thực Bearer token, kiểm tra hồ sơ người gọi phải là Admin đang hoạt động; chỉ thao tác với hồ sơ đích có role `ktv`.
- Hỗ trợ tạo KTV (mật khẩu ban đầu `000000`, buộc đổi mật khẩu), đặt lại mật khẩu, khóa và mở khóa.
- Khi tạo hồ sơ thất bại sau khi tạo Auth user, hàm cố gắng xóa Auth user vừa tạo để tránh tài khoản mồ côi.
- Chỉ thêm mã nguồn vào ZIP; chưa deploy Edge Function, chưa đặt secrets, chưa chạy trên Supabase và chưa kiểm thử thực tế.
- Lưu ý: khóa tài khoản hiện cập nhật `profiles.is_active`; cần bổ sung/kiểm chứng chính sách RLS và cách chặn phiên đang hoạt động để bảo đảm khóa có hiệu lực tức thời trên mọi API.

## Bổ sung bảo vệ tài khoản bị khóa (01/11/2026)
- Thêm migration `202611010002_enforce_active_profiles.sql`.
- Migration tạo hàm `is_active_profile()` dạng `SECURITY DEFINER`, sau đó thêm policy `RESTRICTIVE` cho `profiles`, `parts`, `movements`, `sync_operations` nếu bảng tồn tại.
- Mục đích: mọi policy cho phép dữ liệu hiện có vẫn phải đồng thời thỏa điều kiện hồ sơ đang hoạt động; tài khoản bị khóa không thể tiếp tục đọc/ghi các bảng này qua PostgREST dù access token cũ chưa hết hạn.
- Migration dùng `DROP POLICY IF EXISTS` và tạo lại policy cùng tên để có thể chạy lại.
- Chưa chạy migration trên Supabase thật. Cần chạy sau migration `202611010001_complete_password_change.sql`, rồi kiểm thử bằng access token của KTV bị khóa.
- Chưa xác minh các bảng cloud khác ngoài bốn bảng nêu trên; nếu dự án có thêm bảng chứa dữ liệu người dùng, cần áp dụng điều kiện active tương tự.
- Đây là thay đổi mã SQL, không phải xác nhận đã khóa phiên Auth hoặc thu hồi refresh token. Khóa qua RLS chặn truy cập dữ liệu; phiên Auth có thể vẫn còn cho tới khi hết hạn/đăng xuất.


## Cập nhật an toàn sao lưu/khôi phục ZIP (02/11/2026)
- Đã xác định `KhoLinhKienAndroid-multi-ktv-working-2026-11-01-r2.zip` là nguồn mới nhất: so với r1 chỉ bổ sung migration bảo vệ tài khoản bị khóa và cập nhật nhật ký; các tệp Kotlin chức năng chính giống nhau.
- `BackupManager.kt`: bổ sung `ownerId` vào manifest; thêm SHA-256 cho `parts.jsonl`, `movements.jsonl` và từng ảnh; dừng sao lưu nếu ảnh được tham chiếu không còn tồn tại.
- `ZipRestore.kt`: giới hạn 10.002 mục, tổng giải nén 300 MB, mỗi ảnh 20 MB, manifest 1 MB và mục dữ liệu 100 MB; chặn đường dẫn không an toàn, tên mục trùng, mã linh kiện trùng, ảnh tham chiếu bị thiếu, dữ liệu số lượng/kiểu theo dõi không hợp lệ; kiểm tra SHA-256 nếu manifest có checksum.
- Nếu manifest có `ownerId`, chỉ khôi phục khi trùng tài khoản đang đăng nhập. ZIP v2 cũ chưa có `ownerId` vẫn được hỗ trợ để tương thích, nên người dùng cần chọn đúng file backup của mình.
- Toàn bộ cấu trúc và dữ liệu được kiểm tra trước khi xóa dữ liệu cũ. Ảnh mới được chép trước; nếu Room transaction thất bại, các ảnh vừa chép được xóa. Transaction chỉ xóa và ghi dữ liệu của `ownerId` hiện tại.
- `MainActivity.kt`: truyền `profile.id` vào luồng sao lưu và đặt tên mặc định file `.zip`.
- README đã cập nhật mô tả ZIP.
- Trạng thái: đã chỉnh mã trong working copy; chưa build Gradle, chưa chạy kiểm thử Android/Room, chưa chạy trên điện thoại, chưa deploy SQL/Edge Function, chưa commit/push GitHub.

## Rà soát quyền Admin/KTV tiếp theo (02/11/2026)
- `AuthRepository.getCurrentProfile()` kiểm tra `is_active`; hồ sơ bị khóa sẽ đăng xuất khỏi app khi tải hồ sơ. Migration `202611010002_enforce_active_profiles.sql` bổ sung RLS RESTRICTIVE để access token cũ cũng không đọc/ghi được bốn bảng chính qua PostgREST sau khi migration được chạy.
- Edge Function `admin-users` kiểm tra token bằng `auth.getUser()`, kiểm tra hồ sơ gọi hàm phải có `role=admin` và `is_active=true`; mọi thao tác mục tiêu chỉ cho `role=ktv`, không cho Admin tự khóa/đặt lại mật khẩu qua endpoint này.
- Phát hiện và sửa thứ tự thao tác `reset_password`: bật `must_change_password=true` trước, rồi mới cập nhật mật khẩu Auth thành `000000`. Nếu cập nhật Auth lỗi, cờ bắt buộc đổi mật khẩu vẫn được giữ; an toàn hơn thứ tự cũ.
- Chưa có migration RLS nền tảng trong ZIP để tự xác minh toàn bộ policy `profiles`, `parts`, `movements`, `sync_operations`, `app_admins`; chính sách đang có theo các ảnh Supabase người dùng đã cung cấp ở các lượt trước, nhưng chưa được kiểm thử lại trên dự án thật trong phiên này. Không tự tạo lại policy nền vì có thể xung đột với cấu hình hiện hữu.
- Tìm toàn bộ mã Kotlin/SQL/Edge Function: chưa thấy luồng đồng bộ `parts` và `movements` giữa Room và Supabase; tích hợp Supabase hiện tập trung vào Auth, profiles, Edge Function quản trị. Do đó phân kho nhiều KTV hiện chỉ có tác dụng cục bộ trên thiết bị; chưa được mô tả là đồng bộ nhiều thiết bị.
- Cần thiết kế và thống nhất riêng mô hình đồng bộ trước khi triển khai: quyền RLS, xung đột cập nhật tồn kho, chống ghi giao dịch trùng, xử lý ảnh Storage, retry/offline queue và đồng bộ lịch sử. Không tự bật đồng bộ một chiều vì có thể ghi đè hoặc nhân đôi tồn kho.
- Trạng thái kiểm chứng: đã rà soát mã nguồn và sửa Edge Function trong working copy; chưa chạy Deno test/deploy; chưa chạy migration trên Supabase thật; chưa xác minh RLS live; chưa build APK, chưa test thiết bị, chưa commit/push.

## Thiết kế đồng bộ offline-first (02/11/2026)
- Rà soát toàn bộ điểm ghi dữ liệu trong `MainActivity.kt` và `InventoryDao`: thêm/sửa/xóa linh kiện, nhập/xuất/kiểm kê, khôi phục đều ghi trực tiếp Room; chưa có outbox hoặc luồng PostgREST đồng bộ `parts`/`movements`.
- Không triển khai kiểu tải số tồn cục bộ lên bằng upsert vì có thể ghi đè khi hai KTV cùng cập nhật. Đã tạo `docs/SYNC_DESIGN.md` làm đặc tả triển khai: Room outbox với UUID idempotency, RPC PostgreSQL tuần tự hóa giao dịch tồn kho, revision cho sửa linh kiện, Storage theo owner, retry offline và xử lý riêng khôi phục snapshot.
- Chưa thêm bảng/column hoặc migration đồng bộ vì ZIP hiện tại không chứa migration tạo schema cloud nền tảng và chưa có định nghĩa cột thực tế của `sync_operations`/Storage policies. Việc tự đoán schema có thể làm hỏng dữ liệu Supabase đang dùng.
- Phát hiện `SupabaseProvider.kt` là client thứ hai không được dùng; mã thực tế đang dùng `SupabaseClientProvider`. Chưa xóa file để tránh thay đổi không cần thiết.
- Trạng thái: audit mã nguồn hoàn tất cho các điểm ghi dữ liệu; đặc tả đồng bộ đã thêm. Chưa có đồng bộ cloud được triển khai; chưa build, deploy, hoặc test thiết bị.

## Đọc báo cáo schema Supabase và chuẩn bị migration nền đồng bộ (02/11/2026)
- Đã đọc file CSV xuất từ SQL Editor: một dòng JSON đầy đủ, 23.851 ký tự.
- Schema xác nhận: `parts.id` và `movements.id` là UUID trên cloud; Room hiện dùng `Long` cục bộ. `parts` có unique `(owner_id, code)`, `server_version`, `deleted_at`; `movements` có `part_id`, `occurred_at`, `created_at`; `sync_operations.operation_id` là UUID PK nhưng chưa có payload/result/processed_at.
- RLS đang bật trên cả 5 bảng; `parts` chỉ owner ALL; `movements` owner INSERT/SELECT và Admin ALL; `sync_operations` owner INSERT/SELECT và Admin ALL. `part-images` bucket chưa tồn tại, trong khi đã có 4 owner-folder policies cho Storage.
- Tạo migration bổ sung `202611020001_offline_sync_foundation.sql`: tạo bucket private, thêm cột ledger, restrictive policy Storage theo trạng thái active, RPC `apply_inventory_operation` cho PART_UPSERT/PART_DELETE/STOCK_CHANGE, chống gửi lặp theo operation_id và khóa dòng part khi thay đổi tồn.
- Đây là migration dự thảo dựa trên báo cáo ngày 02/11/2026; chưa chạy trên Supabase. Chưa kiểm tra PostgreSQL bằng môi trường Supabase thật và chưa triển khai client Android.
- Rủi ro/việc bắt buộc trước khi bật client: thêm UUID ổn định cho Part trên Room; thêm Room outbox; đảm bảo mọi thao tác nhập/xuất/kiểm kê ghi outbox cùng transaction; sửa các đường tạo/sửa/xóa part; pull server changes; xử lý xung đột; test concurrency và retry.
- Không được coi đồng bộ cloud đã hoàn thành. Chưa build APK, chưa cài/test điện thoại, chưa push GitHub.
- Rà soát lần hai migration: metadata `PART_UPSERT` không cập nhật `quantity` khi part đã tồn tại; có hỗ trợ `expected_server_version` để từ chối cập nhật metadata cũ. Tồn kho chỉ được thay đổi qua `STOCK_CHANGE` khóa dòng.
- Migration chưa được PostgreSQL parse/execute trong môi trường Supabase; chỉ rà soát tĩnh. Cần staging test trước khi người dùng chạy trên production.

## Chuẩn bị tính nguyên tử cho luồng đồng bộ (02/11/2026)
- Phát hiện thao tác thêm linh kiện có tồn ban đầu đang ghi `parts` và movement `ĐẦU KỲ` bằng hai lệnh Room tách rời, không cùng transaction. Nếu lệnh thêm movement lỗi, linh kiện vẫn được tạo nhưng thiếu lịch sử tồn đầu kỳ.
- Đã thêm `InventoryDao.insertPartWithOpeningMovement(...)` có `@Transaction`; thao tác tạo linh kiện và movement tồn đầu kỳ nay nằm trong một transaction Room.
- `MainActivity.kt` đã chuyển sang gọi hàm DAO mới; không thay đổi giao diện hoặc quy tắc nghiệp vụ.
- Thêm `docs/SYNC_TEST_MATRIX.md` định nghĩa ca kiểm thử giao dịch, phân quyền, retry/xung đột và Storage. Tất cả vẫn là CHƯA KIỂM THỬ cho tới khi chạy thực tế.
- Các điểm chưa giải quyết trước khi tích hợp outbox: Room vẫn dùng Long cho ID; chưa có UUID ổn định, outbox hoặc tombstone; xóa linh kiện vẫn là hard delete; sửa metadata và các luồng nhập/xuất chưa ghi outbox cùng transaction. Không được bật đồng bộ cloud ở trạng thái hiện tại.
- Chưa build Gradle, chưa test Android, chưa chạy migration trên Supabase, chưa deploy, chưa push GitHub.

## Nền tảng UUID cục bộ và outbox Room (02/11/2026)
- `Part` và `Movement` được bổ sung `syncId` UUID ổn định riêng, không dùng khóa `Long` tự tăng của Room làm ID cloud.
- Room nâng từ schema v4 lên v5; migration `4_5` thêm `syncId`, gán UUID cho dữ liệu đã có, tạo unique index và tạo bảng `sync_outbox` có trạng thái, số lần thử và lỗi gần nhất.
- DAO có API đọc outbox PENDING và cập nhật trạng thái; các thao tác tạo linh kiện, sửa metadata, xóa linh kiện, tồn đầu kỳ và nhập/xuất/kiểm kê ghi outbox cùng transaction Room.
- Luồng tạo mã QR hàng loạt được chuyển qua DAO tạo linh kiện có outbox, thay vì insert trực tiếp.
- Đây mới là nền tảng lưu hàng đợi cục bộ; chưa có worker gửi RPC, chưa kéo dữ liệu cloud, chưa xử lý phản hồi máy chủ và chưa đồng bộ ảnh. Không bật đồng bộ thực tế hoặc chạy migration cloud ở giai đoạn này.
- Khôi phục ZIP/JSON hiện vẫn là thao tác thay thế dữ liệu cục bộ; cần thiết kế snapshot operation và xử lý outbox tồn trước khi cho phép đồng bộ sau khôi phục.
- Chưa biên dịch Gradle, chưa chạy migration Room trên thiết bị hoặc database thử nghiệm. Cần kiểm tra Room compile/schema validation và migration v1-v4 trước khi tiếp tục.


## Sửa nền retry outbox và giao dịch tồn đầu kỳ (02/11/2026)
- `InventoryDao.pendingOutbox`: lấy cả `PENDING` và `FAILED`, sắp xếp theo `createdAt` và `rowid` để giữ thứ tự enqueue khi hai thao tác có cùng timestamp.
- Thay API cập nhật trạng thái chung bằng các bước tách biệt: `markOutboxInFlight` (tăng attempt khi bắt đầu gửi), `markOutboxSynced` (chỉ đánh dấu sau xác nhận), `markOutboxFailed` (lưu lỗi để retry) và `recoverInterruptedOutbox` (đưa IN_FLIGHT về PENDING khi worker khởi động lại). Chưa có worker gọi các API này; đây là nền DAO đã chuẩn bị.
- Migration RPC cho phép loại giao dịch `ĐẦU KỲ`, chỉ nhận số lượng dương và chỉ áp dụng khi tồn cloud hiện tại bằng 0.
- Sửa `PART_UPSERT`: khi tạo part mới trên cloud, tồn ban đầu luôn bằng 0; tồn thực tế được áp dụng sau bởi `STOCK_CHANGE`. Như vậy giao dịch `ĐẦU KỲ` không bị cộng/trùng hoặc từ chối vì part đã mang sẵn số lượng ban đầu.
- RPC dùng `movement_sync_id` làm UUID của dòng `movements`; `operation_id` vẫn là UUID idempotency riêng trong `sync_operations`.
- Kiểm tra tĩnh sau sửa: xác nhận tên trạng thái/DAO và nhánh SQL tương ứng bằng grep/diff; chưa chạy PostgreSQL parser, chưa chạy migration trên Supabase, chưa biên dịch Room/Gradle, chưa có sender/worker hoặc pull cloud. Không coi đồng bộ là hoạt động.

## Bộ gửi Room Outbox và WorkManager (02/11/2026)
- Thêm `SyncOutboxSender.kt`: kiểm tra Auth user ID trùng ownerId, gửi lần lượt từng outbox đến RPC `apply_inventory_operation` với operation UUID ổn định, entity UUID và payload JSON.
- Chỉ chuyển `SYNCED` khi RPC trả về thành công; lỗi được lưu `FAILED` kèm thông báo và dừng hàng đợi tại thao tác lỗi để giữ thứ tự phụ thuộc. Lần retry dùng lại đúng operationId, phù hợp ledger idempotency ở migration.
- Với `PART_UPSERT`, ảnh local được tải trước lên bucket riêng `part-images` theo đường dẫn `<ownerId>/<partSyncId>.<extension>`; RPC nhận đường dẫn cloud thay vì đường dẫn file tuyệt đối trên Android. Upload dùng upsert để có thể thử lại sau timeout.
- Thêm `SyncOutboxWorker.kt` dùng WorkManager: chỉ chạy khi có mạng, retry exponential; lập lịch một lần khi vào kho và định kỳ mỗi 15 phút. Có mutex trong process để tránh hai worker cùng tiến hành phục hồi/claim hàng đợi đồng thời.
- Gắn lịch đồng bộ ngay sau các thao tác tạo/sửa/xóa linh kiện, nhập/xuất/kiểm kê và tạo QR hàng loạt.
- Chưa có pull cloud về Room, xử lý conflict/server_version, tải ảnh từ cloud về, tombstone pull hoặc reconcile sau restore ZIP/JSON. Đây chưa phải đồng bộ hai chiều hoàn chỉnh.
- Kiểm tra cú pháp Kotlin độc lập: không thấy lỗi parser; nhưng `kotlinc` không có Android/WorkManager/Supabase dependencies nên không xác minh type-check.
- Thử `./gradlew :app:compileDebugKotlin --offline`: thất bại trước khi Gradle khởi chạy vì wrapper không tải được Gradle 8.9 (`UnknownHostException: services.gradle.org`). Không ghi nhận là lỗi mã Kotlin hay build thành công.
- Chưa chạy PostgreSQL/Supabase migration, chưa upload ảnh thật, chưa kiểm thử RPC, chưa build APK, chưa cài trên điện thoại, chưa push GitHub.

## Change feed Supabase cho đồng bộ hai chiều (02/11/2026)
- Bổ sung `inventory_sync_events` vào migration nền: nhật ký thay đổi append-only, `event_id BIGSERIAL` làm cursor tăng dần, phân vùng theo `owner_id`, bật RLS và thu hồi quyền truy cập trực tiếp của client.
- RPC `apply_inventory_operation` ghi snapshot PART sau tạo/sửa/xóa; với STOCK_CHANGE ghi đồng thời snapshot PART mới và MOVEMENT bất biến. Các event được ghi trong cùng transaction với thay đổi nghiệp vụ.
- Retry cùng `operation_id` trả kết quả ledger đã lưu trước khi tạo event mới, tránh nhân đôi nhật ký.
- Thêm RPC `pull_inventory_changes(after_event_id, limit)`: xác thực phiên và trạng thái active, chỉ trả event của `auth.uid()`, tăng dần theo event_id, giới hạn tối đa 500 event/lượt.
- Cập nhật ghi chú migration và ma trận kiểm thử cho change feed.
- Kiểm tra tĩnh: xác nhận nhánh PART_UPSERT/PART_DELETE và STOCK_CHANGE đều phát event; RPC pull lọc owner và cursor. Chưa có PostgreSQL/Supabase parser hoặc môi trường staging trong phiên này; migration CHƯA chạy thật.
- Chưa viết Android pull client, Room cursor, conflict-aware apply hoặc tải ảnh từ Storage về máy. Chưa build, chưa APK, chưa push GitHub, chưa kiểm thử điện thoại.

## Android Pull Worker và cursor Room (02/11/2026)
- Nâng Room schema v5 lên v6, thêm bảng `sync_cursors` lưu `lastEventId` riêng cho từng owner; có migration 5→6 không xóa dữ liệu cũ.
- Thêm DAO đọc/lưu cursor, upsert PART theo UUID cloud, chèn MOVEMENT idempotent theo `syncId`, xóa PART khi nhận tombstone.
- `applyPulledPart` chạy trong Room transaction và kiểm tra outbox của đúng owner/entity. Nếu có thao tác PENDING/FAILED/IN_FLIGHT, không áp dụng snapshot cloud và không tiến cursor qua event đó; worker retry để outbox hoàn tất trước.
- Thêm `SyncPullWorker`: gọi RPC `pull_inventory_changes` theo cursor, đọc từng event tăng dần, áp dụng PART/MOVEMENT, lưu cursor sau mỗi event. Giới hạn 200 event/trang và 5 trang/lần chạy để tránh worker kéo quá lâu.
- Gắn one-time và periodic pull worker 15 phút vào `SyncWorkScheduler`; cùng điều kiện có mạng và backoff.
- Khi nhận PART snapshot, giữ ảnh local hiện có nếu file vẫn tồn tại; ảnh cloud từ thiết bị khác chưa được tải xuống trong bước này. Đây là phần còn thiếu, không coi đồng bộ ảnh hai chiều đã hoàn thành.
- Kiểm tra migration Room và luồng idempotency bằng đọc mã/grep. Thử `./gradlew :app:compileDebugKotlin --no-daemon` nhưng wrapper không tải được Gradle 8.9 do `UnknownHostException: services.gradle.org`; không có kết quả compile.
- Chưa chạy migration Supabase, chưa kiểm thử RPC/PostgREST/Storage, chưa chạy Room migration trên thiết bị, chưa kiểm thử hai KTV/đa thiết bị, chưa build APK, chưa push GitHub.


## Khắc phục quy trình Gradle/GitHub Actions (02/11/2026)
- Xác định nguyên nhân cụ thể từ lần chạy local: `curl` tới `https://services.gradle.org/distributions/gradle-8.9-bin.zip` trả `Could not resolve host: services.gradle.org`; đây là lỗi DNS/kết nối trước khi Gradle khởi chạy, không phải lỗi biên dịch Kotlin.
- Sửa workflow release để dùng Gradle Wrapper của repository thay vì cài một Gradle 8.9 độc lập; thêm wrapper-validation và Gradle cache qua `gradle/actions/setup-gradle@v4`.
- Tạo workflow `.github/workflows/android-validation.yml`: chạy trên `work/auth-foundation`, PR vào `main`, hoặc workflow_dispatch; dùng JDK 17, Android SDK 35, validate wrapper, compile Kotlin và assembleDebug; không phụ thuộc keystore hay Supabase secrets.
- Tăng wrapper network timeout lên 60 giây và retry 3 lần.
- Thêm `docs/GRADLE_BUILD_STRATEGY.md` mô tả nguyên nhân, cách chạy và cách đọc log.
- Trạng thái: đã chỉnh cấu hình và rà soát tĩnh; chưa push lên GitHub, chưa chạy GitHub Actions, chưa build thành công. Môi trường hiện tại vẫn không phân giải được `services.gradle.org`, nên không thể xác nhận compile tại chỗ.
- Không sửa `main`, không chạy migration Supabase, không tạo APK phát hành.

## 2026-11-02 — Device QR usage history (working-copy change; not yet pushed or built)
- Confirmed device QR content is the same identifier entered in the `Mã máy / cửa hàng` field on stock export.
- QR scan routing in `MainActivity.kt`: existing part code opens the part dialog; a code not found in the current user's parts opens device usage history instead of opening the add-part dialog.
- Device history includes only `XUẤT` movements whose `machineOrStore` matches the scanned device code (trimmed, case-insensitive), sorted newest first.
- Each row shows part code, part name, exported quantity, and full local date/time (`dd/MM/yyyy HH:mm:ss`). Admin additionally sees KTV name; regular KTV sees only their own movements through the existing owner-scoped flow.
- Empty state is shown when no export history exists. No database schema change required.
- Validation status: source inspected and edited in local working copy; Gradle compile could not start because this environment cannot resolve `services.gradle.org`. Not yet committed to GitHub or tested on-device.

## 2026-11-02 — Bổ sung quyền truy cập ảnh Supabase Storage
- Rà soát luồng ảnh phát hiện migration tạo bucket `part-images` nhưng chưa khai báo policy permissive cho SELECT/INSERT/UPDATE/DELETE. Policy restrictive kiểm tra hồ sơ hoạt động không tự cấp quyền truy cập; do đó upload ảnh có thể bị RLS từ chối.
- Thêm migration `202611020002_part_image_owner_policies.sql`: bucket private; tài khoản chỉ được đọc/ghi/xóa object trong thư mục đầu tiên đúng bằng `auth.uid()`; áp dụng cho cả Admin và KTV theo cùng nguyên tắc sở hữu tài khoản.
- Migration có thể chạy lặp lại an toàn bằng `drop policy if exists` trước khi tạo policy. Chưa chạy trên Supabase thật; cần triển khai migration trước khi kiểm tra upload/download.
- Đây mới là sửa quyền Storage, KHÔNG đồng nghĩa đã hoàn thiện quy trình ảnh on-demand hoặc dọn ảnh tạm trên thiết bị. Ảnh local hiện vẫn được giữ trong `filesDir`; camera capture hiện vẫn tạo URI MediaStore. Các phần đó cần xử lý tiếp cùng metadata đường dẫn cloud và luồng tải ảnh theo yêu cầu.

## 2026-11-02 — Triển khai chính sách ảnh cloud-first (đang làm, chưa hoàn tất)
- User xác nhận: ảnh chụp/cập nhật khi offline được lưu tạm trong bộ nhớ riêng để chờ upload; sau khi upload Storage và RPC nghiệp vụ xác nhận thành công thì tự xóa tệp tạm; xem ảnh trực tuyến không ghi cache/tệp xuống thiết bị; chỉ thao tác tải xuống rõ ràng mới lưu ảnh trên máy.
- Camera chuyển sang `TakePicturePreview()` trả bitmap, không còn tạo ảnh chụp trong thư viện `MediaStore`. Ảnh chụp được ghi vào `filesDir/pending_part_photos` làm hàng đợi tạm. Ảnh chọn từ thư viện cũng được sao chép vào thư mục chờ tạm này.
- `SyncOutboxSender`: phân biệt đường dẫn cloud tương đối với đường dẫn file Android tuyệt đối; upload ảnh chờ lên bucket riêng; chỉ sau khi RPC `apply_inventory_operation` thành công và Room đổi được `photoPath` sang object path cloud + đánh dấu outbox SYNCED mới xóa file chờ. Nếu bất kỳ bước nào lỗi, file tạm được giữ để retry.
- `InventoryDao`: thêm transaction cập nhật `photoPath` local thành object path cloud cùng trạng thái outbox.
- Pull cloud: giữ `photo_path` object path nhận từ cloud thay vì bỏ trống vì không phải file local.
- Chưa hoàn tất: hiển thị ảnh trực tiếp từ Supabase Storage không disk cache; nút tải ảnh về thiết bị theo yêu cầu; xóa/đổi ảnh cũ trên Storage; chuyển đổi ảnh local cũ/backup restore; kiểm tra file tạm mồ côi sau crash; xác minh API SDK; build và kiểm thử. Không coi tính năng ảnh cloud-first hoàn thành ở thời điểm này.
- Rà soát tiếp luồng pull phát hiện `SyncPullWorker.toLocalPart()` vẫn gán `photoPath = ""`, trái với ghi chú trước đó và làm mất object path ảnh cloud khi tải snapshot về thiết bị khác. Đã sửa để giữ `photo_path` trả về từ Supabase; không tải ảnh về máy.
- Thử `./gradlew --offline --no-daemon :app:compileDebugKotlin`: Gradle Wrapper chưa có distribution Gradle 8.9 trong cache và cố tải từ services.gradle.org; môi trường không phân giải DNS (`UnknownHostException`). Build chưa bắt đầu, không phải lỗi biên dịch Kotlin.

## Tiếp tục hoàn thiện ảnh cloud-first (02/11/2026 — working copy, chưa build)
- Thêm `PartPhotoPreview`: ảnh cloud được tải bằng `downloadAuthenticated` và giải mã trực tiếp trong RAM; không ghi ảnh xem trước vào disk cache. Ảnh chờ cục bộ vẫn đọc từ file tạm.
- Thêm nút **Tải ảnh về thiết bị** trong chi tiết linh kiện; chỉ khi người dùng chọn vị trí lưu qua Android CreateDocument mới ghi ảnh xuống thiết bị.
- Bổ sung xóa ảnh cloud cũ sau khi RPC cập nhật metadata hoặc tombstone xóa linh kiện thành công. Nếu xóa cloud object thất bại, outbox được retry; đường dẫn ảnh cũ phải thuộc owner hiện tại.
- Bổ sung kiểm tra tham chiếu outbox để không xóa file ảnh tạm khi một thao tác đồng bộ sau vẫn còn dùng chung file; cập nhật transaction cho phép lần gửi sau xác nhận đường dẫn cloud đã được ghi từ lần gửi trước.
- Sửa `BackupManager`: khi sao lưu, ảnh cloud private được tải tạm vào cache qua `downloadAuthenticatedTo`, sau đó đưa vào `photos/` trong ZIP theo luồng file; thư mục tạm luôn được dọn trong `finally`. Ảnh cục bộ đang chờ upload vẫn được đưa vào ZIP. ZIP vẫn chứa `manifest.json`, `parts.jsonl`, `movements.jsonl`, `photos/` và SHA-256.
- Cần kiểm tra tiếp: restore ZIP phải đưa ảnh vào hàng đợi upload và đồng bộ snapshot kho an toàn; xử lý ảnh mồ côi sau crash; Admin cần RPC/pull lịch sử toàn hệ thống; migration Storage phải được chạy thật. Chưa xác minh API bằng Gradle, chưa build, chưa test trên máy thật, chưa push GitHub.
- Bổ sung bước xác minh ảnh sau upload bằng cách đọc lại private object qua phiên hiện tại trước khi gửi RPC; chỉ sau RPC, xóa ảnh cũ (nếu có), cập nhật Room/outbox thành công mới dọn file tạm. Nếu đọc lại hoặc xóa ảnh cũ lỗi, outbox được giữ để retry.
- Bổ sung migration `202611020003_admin_global_history.sql`: RPC chỉ Admin đang hoạt động mới gọi được, chỉ trả movement events toàn hệ thống và gắn `owner_name` từ `profiles`; không mở quyền đọc bảng/event feed trực tiếp cho KTV.
- `SyncPullWorker` nhận cờ Admin, dùng cursor riêng `admin-global:<admin UUID>` và kéo lịch sử toàn hệ thống vào Room với `owner_id` gốc của từng KTV. Scheduler đặt tên worker riêng cho Admin để không bị giữ cấu hình worker KTV cũ.
- Trạng thái: thay đổi mới chỉ nằm trong working copy. Migration `202611020003` chưa chạy trên Supabase; chưa build/compile type-check, chưa test RPC/Storage thật, chưa push GitHub. `kotlinc` không báo lỗi cú pháp parser, nhưng do thiếu Android/Compose/Supabase dependencies nên không phải kiểm tra biên dịch ứng dụng.

## Rà soát an toàn ZIP và ảnh cloud (02/11/2026 — working copy)
- Siết điều kiện sao lưu ảnh cloud: object path phải nằm trong thư mục ownerId của tài khoản đang sao lưu, ngoài việc chặn đường dẫn tuyệt đối và traversal. Tránh vô tình đưa ảnh thuộc tài khoản khác vào file ZIP.
- Siết khôi phục ZIP version 2: manifest bắt buộc có danh sách SHA-256; tập checksum phải khớp chính xác mọi thành phần ngoài manifest; thiếu checksum, thừa checksum hoặc checksum sai đều bị từ chối trước khi ghi Room.
- Đây là thay đổi working copy, chưa compile và chưa test trên Android.
- Phát hiện blocker lớn chưa được giải quyết: ZIP restore hiện ghi ảnh vào `filesDir/part_photos` (lưu lâu dài trái yêu cầu cloud-first), không giữ syncId gốc trong JSONL, không tạo outbox cho snapshot restore và không xóa/đối soát outbox/cursor cũ. Không được bật pull/push sau restore theo trạng thái hiện tại vì thao tác cũ có thể phát lại hoặc cloud có thể ghi ngược dữ liệu cũ. Cần thiết kế RPC snapshot restore có tính nguyên tử/idempotent và khôi phục ảnh vào pending upload queue trước khi coi restore đa thiết bị hoàn tất.
- Bổ sung `syncId` UUID của từng linh kiện và từng dòng lịch sử vào JSONL backup; ZIP restore kiểm tra UUID hợp lệ/không trùng và giữ lại ID khi nạp Room. Với ZIP cũ chưa có trường này, restore sinh UUID mới để vẫn đọc được định dạng hiện tại.
- Chạy Kotlin compiler độc lập trên hai file backup/restore để dò parser; compiler không có Android/Room/Supabase classpath nên không thể type-check toàn app. Kết quả parser được ghi nhận riêng, không thay cho Gradle build.

## Thiết kế cổng an toàn khôi phục snapshot ZIP (02/11/2026 — chưa triển khai)
- Đọc lại trực tiếp `ZipRestore.kt`, `Data.kt`, `SyncOutboxSender.kt` và migration Supabase. Xác nhận restore hiện vẫn ghi ảnh vào `filesDir/part_photos`, chỉ thay Room parts/movements, không thay outbox/cursor; RPC hiện tại chỉ hỗ trợ từng PART/STOCK_CHANGE và stock change là phép cộng/trừ nên không thể dùng replay để khôi phục snapshot an toàn.
- Viết `docs/ZIP_RESTORE_SNAPSHOT_DESIGN.md` mô tả protocol bắt buộc: preflight không mutation; journal và pause push/pull; ảnh vào pending queue và xác minh Storage; RPC `restore_inventory_snapshot` idempotent, transaction nguyên tử và change-feed; Room reconcile sau cloud commit; cursor lấy từ snapshot commit; dọn ảnh tạm sau cùng.
- Đây là tài liệu thiết kế và xác nhận blocker, KHÔNG phải implementation. Không sửa luồng restore hiện tại hoặc bật sync sau restore vì sẽ có nguy cơ mất dữ liệu/nhân đôi tồn. Cần triển khai RPC + journal + scheduler coordination theo thiết kế trước khi build phát hành.
- Chưa chạy migration trên Supabase, chưa build/compile toàn ứng dụng, chưa kiểm thử thiết bị, chưa push GitHub.


## 2026-11-02 — Chốt chính sách khôi phục thay thế dữ liệu thử nghiệm
- User xác nhận dữ liệu hiện tại chỉ là dữ liệu test, không cần giữ hoặc hợp nhất khi khôi phục. ZIP là nguồn dữ liệu chuẩn; có thể thay thế toàn bộ kho và lịch sử của đúng tài khoản đang khôi phục.
- Phạm vi xóa/thay thế tuyệt đối chỉ là `auth.uid()` hiện tại. Không được xóa dữ liệu KTV khác; quyền Admin xem lịch sử toàn hệ thống không đồng nghĩa quyền ghi đè kho của KTV.
- Giữ yêu cầu transaction nguyên tử, idempotency, journal, pause push/pull, ảnh staging tạm và checksum. Đơn giản hóa bằng cách bỏ merge/conflict resolution của dữ liệu test cũ, không bỏ an toàn khi lỗi.
- Cập nhật `docs/ZIP_RESTORE_SNAPSHOT_DESIGN.md` theo chính sách đã được user chốt. Đây là thay đổi tài liệu/đặc tả; chưa triển khai RPC snapshot, journal, scheduler gate hoặc sửa `ZipRestore.restore()`. Chưa compile/build/test/push.


## 2026-11-02 — Restore journal and sync pause groundwork
- Added `RestoreJournal` Room entity/table, schema version 7 and migration 6→7. Journal stores restore ID, owner, manifest digest, phase/status, staging path, counts, returned cursor and error.
- Added DAO methods for journal lookup/upsert, owner-scoped outbox/cursor clearing and finished-journal cleanup.
- Added guards to push and pull WorkManager workers: an active restore journal for the same owner blocks sync and returns retry. Other owners are unaffected.
- Added draft migration `202611020004_restore_inventory_snapshot.sql`: `restore_inventory_snapshot` is SECURITY DEFINER, authenticates with `auth.uid()`, checks active profile, serializes per-owner restores, is idempotent by restore UUID + manifest SHA-256, replaces only that owner's parts/movements, validates owner-scoped photo paths and movement references, publishes fresh change-feed events, and returns a snapshot cursor.
- Critical limitation: this draft sends complete JSONB arrays in one RPC request. It is not yet safe for large ZIPs / >50MB and is not connected to Android. Must replace/extend with chunk staging (begin/stage/commit) before enabling.
- `ZipRestore.restore()` remains old local-only implementation; photo files still go to permanent `filesDir/part_photos`; no restore journal is yet written by UI. New worker gate is groundwork only.
- No PostgreSQL parse/run, no Supabase migration deployment, no Kotlin type-check/Gradle build, no device test, no GitHub push. Do not install/build this as a completed restore implementation.

## 2026-11-02 — SQL review follow-up (user approved)
- Updated `BackupManager.kt` to include stable `syncId` in each PART and MOVEMENT JSONL row.
- Updated `ZipRestore.kt` to restore the original `syncId`; legacy ZIP rows without the field receive a UUID once during import (the future restore journal must persist this mapping before retries).
- Added canonical camelCase-to-snake_case staging contract and explicit photoEntry-to-Storage-path rule to `docs/ZIP_RESTORE_CHUNK_STAGING.md`.
- Rewrote `docs/SQL_REVIEW_STEP6_FINDINGS.md` to distinguish resolved items from remaining blockers.
- Not completed: actual staging mapper/caller, photo Storage upload/verification, Admin pull RPC, authoritative base-schema verification, PostgreSQL execution.
- Verification: source-level grep/review only. No Gradle, PostgreSQL, Docker, Supabase deployment, GitHub push, or APK build.

## 2026-11-02 — Restore payload mapper hardening (tiếp bước 6)
- PART fallback UUID không còn dựa riêng vào code; yêu cầu seed ổn định gồm ownerId + restoreId + entity type + JSONL line index.
- Thêm xác thực UUID owner, path Storage owner-scoped và kiểm tra duplicate trong từng chunk.
- Mapper chưa tích hợp vào luồng restore; chưa upload ảnh, chưa gọi RPC, chưa chạy Gradle/PostgreSQL/Supabase.

## 2026-11-02 — Restore photo upload coordinator foundation
- Added `RestorePhotoUploadCoordinator.kt`: validates ZIP photo entry/path, owner+restore-scoped deterministic Storage object path, supported MIME/extensions, 20 MiB limit, SHA-256, and requires an independent verification callback before returning photoEntry→cloudPath mappings.
- Coordinator never deletes extracted source files; upload/verification failure leaves files available for retry. Returned map feeds `RestorePayloadMapper.part`.
- Added `docs/RESTORE_PHOTO_UPLOAD.md` documenting safety and integration boundary.
- Important: Supabase Storage adapter/independent object verification callback is not implemented because the pinned SDK API could not be verified from local dependency artifacts; coordinator is not yet wired into ZipRestore or restore journal. This is groundwork only, not completed cloud photo restore.
- No Gradle, PostgreSQL, Supabase deployment, APK build, GitHub push, or device test.

## 2026-11-02 — Supabase Storage SDK adapter for ZIP restore (working copy)
- Đã đối chiếu trực tiếp source tag `3.0.1` của `supabase-kt` (BucketApi.kt, BucketApiImpl.kt, UploadOptionBuilder.kt) qua GitHub connector; xác nhận `BucketApi.upload(path, ByteArray, options)` và `BucketApi.downloadAuthenticated(path): ByteArray`, cùng kiểu `UploadOptionBuilder.contentType: io.ktor.http.ContentType`.
- Thêm `SupabaseRestorePhotoStorage.kt`: upload vào bucket `part-images` bằng authenticated client, `upsert=true`, content type parse rõ ràng; sau upload đọc lại object private bằng `downloadAuthenticated`, xác minh kích thước và SHA-256.
- Thêm `RestorePhotoUploadFactory.kt` nối adapter với `RestorePhotoUploadCoordinator`. Xử lý từng ảnh tuần tự, coordinator giới hạn 20 MiB/ảnh; file staging không bị xóa bởi adapter.
- Giới hạn đã biết: verify tải một ảnh tối đa 20 MiB vào RAM; không tải toàn bộ ZIP vào RAM. Nếu sau này tăng giới hạn ảnh, cần chuyển verify sang streaming channel trước.
- Chưa tích hợp factory vào `ZipRestore.restore()` vì restore snapshot/journal/chunk staging chưa hoàn chỉnh; chưa được phép bật upload ảnh như một bước restore độc lập có thể để lại object mồ côi.
- Xác minh: đối chiếu chữ ký SDK từ source tag 3.0.1; rà soát source-level. Không chạy Gradle, không type-check toàn app, không chạy PostgreSQL/Supabase, không deploy, không build APK, không push GitHub, chưa test thiết bị.

## 2026-11-02 — ZIP restore preflight hardening after Storage adapter review
- Đọc trực tiếp `BackupManager.kt`, `ZipRestore.kt`, `RestorePayloadMapper.kt`, `Data.kt`, `RestorePhotoUploadCoordinator.kt` và adapter Storage.
- Sửa `ZipRestore`: ZIP v2 bắt buộc có `manifest.checksums`; tập tên checksum phải khớp chính xác tất cả file trong ZIP ngoại trừ manifest; từ chối thiếu/thừa checksum và SHA-256 sai. Trước đó code cho phép bỏ qua toàn bộ checksum nếu manifest không có object checksums.
- Sửa ID fallback của PART/MOVEMENT trong local restore: ID sinh deterministic theo owner + digest manifest + loại record + code/line index; validate UUID và duplicate ID trước khi bắt đầu transaction. Retry cùng ZIP không sinh ID mới.
- Xác nhận adapter Storage 3.0.1 hiện upload/read-back đúng API nhưng dùng ByteArray bounded theo ảnh 20MB; chưa có streaming file-to-channel. Không dùng adapter này trong restore hiện tại vì luồng `ZipRestore.restore()` vẫn ghi ảnh lâu dài vào `filesDir/part_photos` và thay Room trước khi có cloud snapshot commit.
- Phát hiện thêm: `BackupManager.kt` trong working copy đang yêu cầu `File(path).isFile` cho mọi `photoPath`; chưa tải ảnh cloud path từ Storage vào cache tạm như một số ghi chú trước đó mô tả. Ghi chú cũ về cloud photo backup chưa phản ánh đúng file đang kiểm tra; cần sửa trước khi coi backup cloud-first hoàn chỉnh.
- Chưa tích hợp upload coordinator vào ZipRestore, chưa thay semantics restore cũ, chưa tạo staging Room; không được coi local restore hiện tại là an toàn cho đồng bộ đa thiết bị.
- Kiểm tra tĩnh bằng đọc source và đối chiếu contract; không chạy Gradle, không build APK, không thực thi PostgreSQL, không deploy Supabase, không push GitHub.

## 2026-11-02 — Sửa thực tế luồng sao lưu ảnh cloud (working copy)
- Đối chiếu trực tiếp `BackupManager.kt` cho thấy phần ghi ở mục trước về `downloadAuthenticatedTo` chưa phản ánh mã nguồn: file thực tế vẫn chỉ chấp nhận file local. Đã sửa lại implementation thay vì coi ghi chú cũ là bằng chứng.
- `BackupManager.createZip` nay là hàm `suspend`; với `photoPath` tương đối, kiểm tra đường dẫn thuộc đúng `ownerId`, không có path traversal, rồi tải object private bằng Supabase Storage `downloadAuthenticatedTo` vào thư mục cache tạm riêng cho lần sao lưu.
- Với `photoPath` tuyệt đối, giữ nguyên file pending upload và đọc trực tiếp; không di chuyển hoặc xóa file đang chờ đồng bộ.
- Cả hai loại ảnh đều giới hạn 20 MB/ảnh, chỉ nhận jpg/jpeg/png/webp, được đưa vào ZIP theo luồng 64 KiB và tính SHA-256. Thư mục tải tạm được xóa trong `finally` kể cả khi sao lưu lỗi.
- API `downloadAuthenticatedTo(path, file)` được tra cứu trong tài liệu API Storage supabase-kt; tài liệu đánh dấu hỗ trợ Android/JVM. Dependency dự án đang khóa BOM 3.0.1. Tuy nhiên chưa có Gradle dependency cache và chưa compile nên import/type compatibility với đúng artifact 3.0.1 vẫn chưa được xác nhận bằng build.
- Chưa chạy Gradle, chưa build APK, chưa kiểm thử ZIP trên Android, chưa gọi Supabase thật, chưa push GitHub. Restore ZIP vẫn là blocker riêng và chưa được sửa trong bước này.

## 2026-11-02 — Restore ZIP preflight ownership/photo consistency (bước 8)
- Tăng cường preflight trong `ZipRestore.kt`: manifest bắt buộc phải có `ownerId` không rỗng và khớp chính xác tài khoản đang đăng nhập; không còn chấp nhận ZIP thiếu chủ sở hữu.
- Tập ảnh trong ZIP phải khớp chính xác tập ảnh được các bản ghi linh kiện tham chiếu; từ chối ảnh thừa/không tham chiếu và định dạng ngoài JPG/JPEG/PNG/WebP.
- Đây chỉ là hardening phần kiểm tra trước khi restore; chưa thay đổi thao tác ghi Room/ảnh của luồng restore cũ. Luồng restore hiện tại vẫn local-only và chưa đạt tiêu chí cloud-first/snapshot đa thiết bị, không coi là sẵn sàng phát hành.
- Không chạy Gradle, không build APK, không gọi Supabase/PostgreSQL, không push GitHub.

## 2026-11-02 — Room durable restore staging foundation (bước 9)
- Tăng Room DB từ version 7 lên 8; thêm entity `RestoreStagedRow` với khóa chính `(restoreId, entityType, chunkIndex, rowIndex)` và index theo owner/restore/type/chunk.
- Thêm migration 7→8 tạo bảng `restore_staged_rows`; migration được đăng ký trong builder, không destructive fallback.
- Thêm DAO để ghi batch staged rows, đọc theo thứ tự chunk/row, đếm số dòng theo owner/restore/type và xóa staging theo đúng owner + restoreId.
- Bảng staging chỉ giữ JSON payload, không tác động bảng `parts`, `movements`, `sync_outbox` hoặc cursor. Đây là nền tảng lưu bền vững khi process bị đóng; chưa có caller điều phối ZIP vào bảng này, chưa tích hợp RPC chunk staging và chưa chuyển đổi live Room.
- Chưa chạy Room migration trên thiết bị/instrumentation, chưa compile/type-check do thỏa thuận chưa build trong giai đoạn code. Chưa chạy Gradle, PostgreSQL, Supabase; chưa deploy, build APK hoặc push GitHub.

## 2026-11-02 — ZIP được đưa vào Room staging bền vững (bước 10)
- Thay phần cuối của `ZipRestore.restore()` vốn chép ảnh vào `filesDir/part_photos` và xóa/nhập trực tiếp bảng `parts`/`movements` bằng bước preflight + staging. Hàm hiện không sửa kho live, outbox hoặc cursor.
- Sau khi kiểm tra manifest/checksum/owner/ảnh, tạo restoreId ổn định từ owner + manifest digest; ghi journal `STAGING`, lưu payload JSONL vào `restore_staged_rows` theo lô 200 dòng, xác nhận đủ số dòng rồi chuyển journal sang `STAGED`.
- Ảnh ZIP được giữ trong thư mục riêng `filesDir/restore_staging/<restoreId>/photos` để có thể upload cloud ở bước tiếp theo; không đưa ảnh restore vào `part_photos` hoặc coi ảnh này là ảnh live.
- UI không còn báo “khôi phục thành công”; thông báo đúng rằng dữ liệu chỉ được kiểm tra và lưu tạm, kho hiện tại chưa thay đổi, cloud commit chưa bật.
- Lưu ý: đây mới là giai đoạn staging, không phải restore hoàn chỉnh. Chưa có nút/worker resume hoặc abort journal FAILED; journal active sẽ chặn push/pull theo guard hiện hữu cho tới khi luồng điều phối hoàn chỉnh được nối. Cần hoàn thiện resume/abort và cleanup trước khi bật phát hành.
- Chưa chạy Gradle, chưa build APK, chưa chạy Room migration/instrumentation, chưa gọi Supabase/PostgreSQL, chưa deploy hoặc push GitHub.

## 2026-11-02 — Durable staging lifecycle và retry/cancel (bước 11)
- Thêm `RestoreStagingLifecycle`: đọc trạng thái journal theo owner, đếm số dòng PART/MOVEMENT, kiểm tra ảnh staging; xác nhận phiên `STAGED` có thể tiếp tục khi số dòng đủ.
- Thêm thao tác hủy có điều kiện: chỉ cho phép `STAGING`, `STAGED`, `FAILED`; kiểm tra owner và canonical path; xóa journal trong transaction trước, chỉ xóa staged rows nếu journal thực sự bị xóa; sau transaction mới dọn thư mục staging. Không cho hủy ở trạng thái có thể đã bắt đầu cloud commit.
- `ZipRestore.restore()` nay retry cùng ZIP theo restoreId ổn định: phiên `STAGED` đủ dữ liệu trả lại kết quả mà không tạo phiên trùng; phiên `STAGING`/`FAILED` được dọn có kiểm soát rồi staging lại; phiên khác của cùng owner vẫn chặn; thư mục mồ côi trong crash window trước khi tạo journal được nhận diện và dọn theo đường dẫn an toàn.
- Không tác động live parts/movements, outbox hoặc cursor. Không xóa dữ liệu của owner khác.
- Giới hạn còn lại: lifecycle API chưa nối vào UI/worker; `STAGED` hiện chỉ xác nhận có thể tiếp tục, chưa có coordinator cloud để thực thi phần tiếp theo. Việc abort chỉ hủy local staging, chưa có remote session nào được tạo ở giai đoạn này. Không coi restore hoàn chỉnh.
- Kiểm tra source-level và rà soát thứ tự transaction; chưa chạy Gradle, chưa type-check, chưa chạy Room instrumentation/migration, chưa chạy PostgreSQL/Supabase, chưa build APK hoặc push GitHub.

## 2026-11-02 — Resumable cloud photo upload component (bước 12)
- Hoàn thiện `RestorePhotoUploadCoordinator`: object path deterministic theo owner + restoreId + SHA-256 + extension; retry không tạo object path mới.
- Trước khi upload, coordinator đọc object private bằng callback xác minh. Nếu object đã tồn tại đúng kích thước/SHA-256 thì bỏ qua upload; cơ chế này cũng xử lý crash sau upload thành công nhưng trước khi lưu checkpoint.
- Sau mỗi ảnh xác minh thành công, ghi `photos_uploaded.json` trong thư mục staging; file checkpoint được thay thế bằng `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` (minSdk 26). Checkpoint luôn được xem là advisory và remote object vẫn phải xác minh trước khi skip.
- Kiểm tra ảnh thay đổi giữa lúc hash và upload bằng cách so lại size + SHA-256 của ByteArray; chỉ giữ tối đa một ảnh 20 MiB trong RAM. Ảnh nguồn không bị xóa.
- Cập nhật `docs/RESTORE_PHOTO_UPLOAD.md` mô tả retry/crash recovery và ranh giới tích hợp.
- Giới hạn: coordinator/factory vẫn chưa được gọi từ `ZipRestore`/worker; chưa có orchestration để gọi uploader, map `photoEntry` sang Storage path, gửi chunk RPC, commit snapshot và reconcile Room. Vì vậy chưa coi upload ảnh cloud là luồng restore hoàn chỉnh, không được dọn ảnh staging sau upload riêng lẻ.
- Xác minh ở bước này: rà soát source-level và kiểm tra cấu trúc ZIP sau đóng gói; không chạy Gradle, không type-check, không gọi Storage/Supabase, không build APK, không push GitHub, chưa test thiết bị.


## 2026-11-02 — Nối upload ảnh với ánh xạ payload cloud (bước 13)
- Thêm `RestoreCloudPayloadPreparer`: đọc PART/MOVEMENT từ Room staging theo owner + restoreId; xác minh số lượng khớp journal trước khi xử lý.
- Lấy tập `photoEntry` được tham chiếu từ PART staging, gọi uploader resumable đã có; chỉ tiếp tục ánh xạ nếu tất cả ảnh được upload và read-back SHA-256 thành công.
- Dùng `RestorePayloadMapper` để chuyển PART/MOVEMENT sang schema snake_case; đường dẫn ảnh trong PART bắt buộc phải có trong map Storage đã xác minh và thuộc đúng owner.
- Lưu payload đã ánh xạ thành các dòng staging dẫn xuất `PART_PAYLOAD`/`MOVEMENT_PAYLOAD`; chia lô tối đa 200 dòng và mục tiêu 900 KiB để chừa headroom dưới giới hạn RPC 1 MiB. Bản JSONL nguồn không bị sửa, checkpoint ảnh được giữ để retry.
- Thêm DAO xóa riêng payload dẫn xuất theo entity type; retry chỉ xóa phần dẫn xuất, không xóa dữ liệu gốc hoặc ảnh. Journal chuyển `PAYLOAD_READY` chỉ sau khi đếm đủ payload PART/MOVEMENT.
- Ranh giới an toàn: preparer chưa được gọi từ UI/worker và chưa tạo remote restore session/RPC. Không tự upload ảnh từ thao tác ZIP staging vì nếu chưa có session cloud có thể để lại object mồ côi. Chưa thay live Room/outbox/cursor.
- Xác minh source-level: đối chiếu giới hạn 200 dòng/1 MiB với migration 005; kiểm tra tên DAO/entity và luồng owner. Không chạy Gradle/type-check, không gọi Storage/Supabase, không chạy PostgreSQL, không build APK, không push GitHub, chưa test thiết bị.

## 2026-11-02 – Step 14: cloud restore session + chunk sender
- Added `RestoreCloudSessionSender.kt`: validates authenticated owner, calls `begin_inventory_restore`, sends persisted PART/MOVEMENT chunks sequentially, and records `CLOUD_STAGING` / `CLOUD_STAGED` journal states.
- Retry uses the same restore ID and chunk indexes; server SQL compares replay payloads and rejects conflicting replays.
- Corrected a pre-existing contract mismatch: Android payload preparation may split before 200 rows to stay below 900 KiB, while migrations 005/006 required fixed 200-row chunks. Updated the not-yet-deployed SQL contract to allow variable 1..200 row chunks, exact contiguous indexes, and exact aggregate totals. Per-chunk 1 MiB limit remains.
- Sender intentionally does not call `commit_inventory_restore`; live inventory, outbox, and cursor remain untouched.
- Not done: UI wiring, server migration execution, Kotlin compilation, integration tests, commit/snapshot pull, safe Room replacement.
- No Gradle, APK build, Supabase deployment, or GitHub push performed.

## 2026-11-02 — Step 15: Android client for atomic cloud commit
- Added `RestoreCloudCommitter.kt` to call `commit_inventory_restore` only for owner-authenticated sessions whose journal is `CLOUD_STAGED` (or a prior ambiguous/confirmed commit state).
- Persists `COMMIT_UNKNOWN` before the RPC. If the process dies or the response is lost after the server commits, retrying the same restore ID relies on the server ledger's idempotent `already_committed` response rather than creating a second restore.
- Validates server status, restore ID, manifest SHA-256, positive snapshot cursor, and PART/MOVEMENT counts against the local journal before persisting `COMMIT_CONFIRMED` and cursor.
- This client does not alter local Room inventory, outbox, or sync cursor. It is not yet wired into UI/worker and does not fetch snapshot chunks or reconcile local Room. Therefore this is only the cloud commit-call component, not end-to-end safe restore.
- Static review compared RPC signature and response keys against migration 006. No Gradle/Kotlin compilation, Room tests, PostgreSQL execution, Supabase deployment, APK build, device test, or GitHub push.

## Step 16 — đọc snapshot cloud sau commit (mã nguồn, chưa build)
- Added `RestoreCloudSnapshotReader.kt` to call `read_inventory_restore_snapshot_chunk` only after `COMMIT_CONFIRMED` (or resumable snapshot-read states).
- Validates owner/session, manifest digest, stable snapshot cursor, entity type, chunk index/count, total row count, row count, JSON object payload shape, and server MD5 syntax.
- Persists downloaded PART/MOVEMENT snapshot rows into separate `SNAPSHOT_PART` / `SNAPSHOT_MOVEMENT` Room staging buckets. Does not touch live inventory, outbox, or sync cursor.
- Retry clears only the two snapshot-derived staging buckets before re-reading; original ZIP staging and cloud payload staging remain intact.
- This is a reader/staging component only. It is not yet connected to UI/orchestrator or Room swap. No local snapshot swap, no worker pause/resume, no schema migration, no Gradle, no APK, no Supabase execution.
- Integrity limitation: SQL returns `payload_md5` computed over PostgreSQL JSONB text. This client validates its format and validates the returned envelope/row counts; it does not recompute PostgreSQL's canonical JSONB text MD5 because Kotlin serialization is not guaranteed byte-identical. The manifest digest and commit ledger are separately validated.


## Bước 17 — Chuyển snapshot đã xác nhận vào Room nguyên tử (02/11/2026)
- Thêm `RestoreCloudSnapshotApplier.kt`: chỉ cho phép tài khoản Auth trùng `ownerId`, journal đúng chủ kho và trạng thái `SNAPSHOT_DOWNLOADED`; sau đó gọi một Room transaction chuyên biệt.
- `InventoryDao.applyCommittedRestoreSnapshot(...)`: kiểm tra journal/cursor/count, kiểm tra outbox còn PENDING/FAILED/IN_FLIGHT, xác minh toàn bộ chunk trước khi xóa dữ liệu; kiểm tra chunk liên tục, rowIndex, UUID, mã linh kiện trùng, số lượng, trackingMode, đường dẫn ảnh đúng owner và lịch sử phải tham chiếu linh kiện có trong snapshot.
- Chuyển đổi chỉ xóa/ghi `parts` và `movements` của owner hiện tại; chèn theo chunk tối đa 200 dòng, không giữ toàn bộ JSON snapshot trong RAM. Trong cùng transaction cập nhật cursor owner và journal `COMPLETE`. Nếu bất kỳ bước nào lỗi, Room rollback cả dữ liệu và journal; kho cũ vẫn nguyên.
- `SyncOutboxSender` nay tạm dừng gửi khi có restore journal đang hoạt động và kiểm tra lại trước từng operation. `SyncPullWorker` đã có guard active restore từ bước trước.
- Chưa nối UI/điều phối ZIP→cloud→commit→snapshot→applier; giao diện hiện vẫn gọi `ZipRestore.restore` cũ. Không được coi luồng khôi phục cloud-first đã sẵn sàng sử dụng cho tới khi thay entrypoint cũ bằng orchestrator hoàn chỉnh.
- Giới hạn cần xử lý trước release: `RestoreCloudSnapshotReader` hiện chỉ kiểm tra định dạng trường `payload_md5`, chưa tự xác minh MD5 theo cách serialize JSONB của PostgreSQL; migration 006 có RESTORE_COMMIT nhưng Android pull worker chưa xử lý marker; migration định nghĩa `pull_admin_movement_changes` hiện không có trong ZIP. Cần xử lý các mục này trước khi bật luồng khôi phục, đặc biệt với Admin/global history.
- Kiểm chứng bước này: rà soát tĩnh mã và cấu trúc transaction; chưa chạy Gradle/Room compiler, chưa chạy kiểm thử DB, chưa chạy Supabase SQL/RPC, chưa build APK hoặc test thiết bị.

## Bước 18 — Xử lý RESTORE_COMMIT trong change-feed worker (02/11/2026)
- `SyncPullWorker` nhận diện `RESTORE_COMMIT` như control marker, xác thực `restore_id` là UUID, `manifest_sha256` đúng 64 ký tự hex và count không âm.
- Sau khi marker hợp lệ, worker chỉ tiến cursor qua nhánh xử lý chung; không tạo PART/MOVEMENT giả và không sửa kho Room.
- Với Admin global feed, marker của KTV chỉ làm tiến global cursor; không áp snapshot KTV vào kho riêng Admin. Luồng PART của Admin vẫn chỉ tiến cursor; MOVEMENT vẫn được lưu theo owner_id trong payload như trước.
- Không tự thêm migration `pull_admin_movement_changes`: schema/authorization Admin RPC chưa có trong ZIP hiện tại, và không đủ căn cứ để tự suy đoán cơ chế xác định quyền Admin hoặc chính sách đọc toàn bộ movement. Đây vẫn là blocker bắt buộc trước khi bật global history/restore cho Admin.
- Kiểm tra tĩnh: đối chiếu payload RESTORE_COMMIT với migration 006; xác nhận nhánh marker không gọi DAO ghi parts/movements và cursor chỉ lưu sau validation. Chưa chạy Gradle/Kotlin compiler, chưa chạy unit/instrumentation tests, chưa gọi Supabase/PostgreSQL, chưa build APK hoặc push GitHub.


## Bước 19 — Xác minh payload snapshot bằng PostgreSQL JSONB (02/11/2026)
- Thêm migration `202611020007_verify_restore_snapshot_chunk.sql`: RPC chỉ cho profile đang hoạt động và đúng `auth.uid()` đọc/đối chiếu chunk thuộc phiên COMMITTED của chính mình. Hàm nhận JSON payload Android vừa parse, tính `md5(p_payload::text)` bằng chính PostgreSQL, so sánh với digest đã lưu, row_count và JSONB equality của chunk bất biến.
- `RestoreCloudSnapshotReader` gọi RPC xác minh trước khi chèn bất kỳ dòng nào vào `SNAPSHOT_PART`/`SNAPSHOT_MOVEMENT`; kiểm tra cả cờ verified, digest và row_count trả về. Không còn chỉ kiểm tra định dạng 32 ký tự MD5.
- MD5 ở đây là checksum đối chiếu tính nhất quán/retry, không phải chữ ký mật mã hay bằng chứng chống máy chủ độc hại; tính xác thực vẫn dựa trên phiên Supabase/TLS, RLS/RPC owner scope và manifest SHA-256 đã kiểm tra từ ZIP.
- Kiểm tra tĩnh: đối chiếu tham số/response giữa Kotlin và SQL, xác nhận verifier không sửa dữ liệu, chỉ đọc chunk đã COMMITTED; snapshot staging chỉ được ghi sau xác minh. Chưa chạy PostgreSQL/Supabase, chưa compile Gradle/Kotlin, chưa build APK hoặc test thiết bị.


## Bước 20 — RPC lịch sử chuyển động toàn hệ thống cho Admin (02/11/2026)
- Bổ sung migration `202611020008_admin_global_movement_feed.sql` định nghĩa RPC `pull_admin_movement_changes` mà `SyncPullWorker` đã gọi nhưng migration tương ứng trước đó bị thiếu.
- RPC kiểm tra `auth.uid()`, profile đang hoạt động và `profiles.role = admin` (không phân biệt hoa thường); KTV không thể gọi để đọc feed toàn hệ thống.
- Feed trả về MOVEMENT và RESTORE_COMMIT của mọi owner, bổ sung `owner_id`/`owner_name` từ profile để Android lưu đúng chủ lịch sử và hiển thị tên KTV.
- PART events được thay bằng marker `SKIP` không có `entity_id`/payload, giữ thứ tự event_id liên tục cho global cursor nhưng không làm lộ dữ liệu kho linh kiện.
- Cập nhật `SyncPullWorker` nhận `SKIP` như control event; không tạo hoặc sửa PART/MOVEMENT từ marker này. MOVEMENT tiếp tục được ghi theo owner_id trong payload; RESTORE_COMMIT chỉ tiến cursor.
- Kiểm tra tĩnh: đối chiếu tên RPC và tham số với worker; đối chiếu role `admin` với MainActivity và `profiles.role` với UserProfile/AuthRepository; rà soát rằng PART payload không được trả ra trong global feed. Chưa chạy PostgreSQL/Supabase, chưa compile Kotlin/Gradle, chưa build APK hoặc kiểm thử thiết bị.

## Bước 21 — Dọn staging sau khi Room đã áp dụng snapshot (02/11/2026)
- Thêm DAO `clearCompletedRestoreStagedRows`: chỉ xóa các dòng staging nếu journal đúng owner/restoreId và đã ở trạng thái `COMPLETE`.
- Thêm `RestoreStagingLifecycle.cleanupCompleted`: yêu cầu journal COMPLETE, owner khớp và sync cursor hiện tại khớp snapshot cursor; xác minh đường dẫn canonical dưới `filesDir/restore_staging/<restoreId>`; sau đó xóa dữ liệu staging cục bộ và thư mục ảnh tạm. Giữ journal COMPLETE làm dấu vết và cho phép retry nếu process bị đóng giữa lúc dọn.
- Nối cleanup sau `applyCommittedRestoreSnapshot` trong `RestoreCloudSnapshotApplier`. Cleanup là best-effort; nếu xóa file thất bại, kết quả Room apply vẫn được giữ thành công và `localCleanupComplete=false` báo còn việc dọn local để retry.
- Không xóa ảnh/object trên Supabase Storage; không sửa live inventory, movement history, outbox hoặc sync cursor. Không xóa journal COMPLETE.
- Kiểm tra tĩnh: xác nhận chỉ journal COMPLETE mới được dọn, cursor khớp trước khi xóa, owner scope và canonical path được xác thực. Chưa chạy Gradle/Kotlin compiler, Room instrumentation/migration, PostgreSQL/Supabase, build APK hoặc test thiết bị.
- Blocker tiếp tục: luồng điều phối cloud restore và UI vẫn chưa thay entrypoint `ZipRestore.restore` cũ; không coi toàn bộ tính năng khôi phục đã sẵn sàng.

## Bước 22 — Bộ điều phối khôi phục cloud có thể tiếp tục (02/11/2026)
- Thêm `RestoreCloudCoordinator`: cung cấp `importAndRestore(uri, ownerId)` và `resume(ownerId, restoreId)`; điều phối chuỗi ZIP staging → upload/xác minh ảnh → tạo payload → gửi cloud chunks → commit idempotent → đọc/xác minh snapshot → áp dụng Room transaction → dọn staging.
- Khi tiếp tục, coordinator đọc `RestoreJournal.status` và chỉ chạy giai đoạn cần thiết; các trạng thái `CLOUD_STAGING`, `COMMIT_UNKNOWN`, `SNAPSHOT_DOWNLOADING` có thể retry bằng cùng restoreId. Với `COMPLETE`, chỉ thử dọn staging local còn sót.
- Kiểm tra outbox trước khi bắt đầu staging, trước commit và trước khi áp snapshot. Nếu còn PENDING/FAILED/IN_FLIGHT thì dừng, không xóa outbox hoặc dữ liệu local. Lý do: active restore guard tạm dừng đồng bộ thường, nên cần outbox sạch trước khi tạo phiên.
- Bộ điều phối không trực tiếp sửa parts/movements; thao tác thay kho duy nhất vẫn nằm trong `RestoreCloudSnapshotApplier` và DAO transaction owner-scoped.
- Chưa nối MainActivity/UI: giao diện vẫn gọi `ZipRestore.restore` cũ và nhánh JSON cũ. Chưa có UI hiển thị tiến trình/tiếp tục phiên lỗi. Vì vậy chưa bật luồng cloud restore cho người dùng.
- Kiểm tra tĩnh: rà soát trạng thái đầu vào/đầu ra của từng phase và đường retry. Không chạy Gradle/Kotlin compiler, không chạy test, không thực thi SQL/Supabase, không build APK, không test thiết bị, không push GitHub.

## Step 23 — Safe restore UI integration (2026-11-02)
- MainActivity ZIP restore now routes through RestoreCloudCoordinator rather than calling ZipRestore directly.
- JSON legacy direct replacement has been removed from the UI path and is rejected with a clear explanation; ZIP is the supported restore format for the verified cloud flow.
- Tools screen lists unfinished owner-scoped restore sessions and offers resume by exact restoreId; new restore is disabled until that session is finished.
- Added busy state, progress indicator, and journal refresh after restore/resume.
- Review: `STEP23_REVIEW.md`.
- Not run: Gradle/Kotlin compile, tests, Supabase SQL/RPC, APK build, device validation, GitHub push.

## Step 24 — Hiển thị giai đoạn và lỗi khôi phục (2026-11-02)
- Thay token trạng thái kỹ thuật bằng tiêu đề tiếng Việt theo từng phase: staging ZIP, chuẩn bị payload, gửi cloud, commit, tải snapshot và áp dụng snapshot.
- Bổ sung mô tả hành động an toàn theo phase, số linh kiện/giao dịch dự kiến, thời điểm cập nhật journal và `lastError` đã lưu nếu có.
- Callback tiếp tục khôi phục truyền đúng `restoreId`; MainActivity xác nhận journal thuộc owner đang đăng nhập trước khi gọi coordinator.
- Không tạo phần trăm giả; hiện chỉ hiển thị tiến trình theo phase. Tiến độ chi tiết theo ảnh/chunk chưa được lưu bền vững.
- Hạn chế còn lại: `STAGING` và `FAILED` được mô tả nhưng chưa có thao tác hủy/nhập lại riêng trên UI; không xem đây là phase có thể resume bằng coordinator.
- Kiểm tra tĩnh chữ ký callback và dữ liệu journal; không chạy Gradle/Kotlin compiler, test, Room migration, SQL/Supabase, APK, thiết bị hoặc GitHub push.

## Step 24 — Hiển thị trạng thái dễ hiểu và thử dọn staging sau khôi phục (02/11/2026)
- Giữ lại phần ánh xạ trạng thái tiếng Việt đã có trong MainActivity; rà soát lại các nhãn/diễn giải theo các trạng thái journal mà coordinator sử dụng, không hiển thị phần trăm giả khi chưa có checkpoint tiến độ chi tiết.
- Bổ sung khu vực riêng cho journal `COMPLETE`, hiển thị số linh kiện, số giao dịch và snapshot cursor; cho phép gọi lại `RestoreCloudCoordinator.resume(ownerId, restoreId)` để thử cleanup staging cục bộ lần nữa.
- Nút cleanup chỉ gọi nhánh `COMPLETE` của coordinator. Nhánh này không gửi lại payload, không commit lại cloud, không áp dụng lại snapshot; chỉ xác minh owner/cursor và gọi `cleanupCompleted` an toàn, lặp lại được.
- Điều chỉnh thông báo sau thao tác để phân biệt phiên vừa khôi phục với phiên COMPLETE đang thử dọn dữ liệu tạm.
- Kiểm tra tĩnh: xác nhận callback truyền restoreId cụ thể, tìm journal theo đúng ownerId, trạng thái COMPLETE được coordinator định tuyến thẳng vào cleanup; không thêm Room column/migration và không đổi schema.
- Chưa chạy Gradle/Kotlin compiler, unit/instrumentation tests, PostgreSQL/Supabase, APK build, device test hoặc GitHub push.

## Step 25 — Khóa thao tác kho trong thời gian khôi phục (02/11/2026)
- Phát hiện rủi ro: UI vẫn có thể thêm/sửa/xóa/điều chỉnh tồn kho khi journal restore chưa hoàn tất; outbox mới có thể phát sinh sau một lần kiểm tra outbox ở phase trước.
- MainActivity suy ra trạng thái khóa từ `restoreBusy` và journal chưa COMPLETE/ABORTED, do đó khóa vẫn tồn tại sau khi app khởi động lại. Tạm chặn quét QR, mở thêm linh kiện, tạo mã hàng loạt và sao lưu ZIP; các callback lưu/sửa/xóa/điều chỉnh tồn kho cũng đọc lại journal trước khi ghi.
- Bổ sung kiểm tra `activeRestore(ownerId)` bên trong Room transaction của `insertPartWithOpeningMovement`, `update`, `deletePartByCode`, `changeStock`; thao tác ghi kho thông thường không thể commit nếu owner đang có restore journal hoạt động. Không khóa KTV khác, không khóa đọc kho/lịch sử hoặc xuất Excel.
- Không đổi schema, không xóa outbox/cursor và không sửa cloud.
- Review: `STEP25_REVIEW.md`.
- Chưa chạy Gradle/Kotlin type-check, tests, Supabase SQL/RPC, APK build, thiết bị hoặc GitHub push.


## Step 25 — Rà soát bổ sung khóa ghi và race với pull worker (02/11/2026)
- Audit lại tất cả lời gọi ghi Room liên quan kho/lịch sử sau khi hoàn tất UI lock. Phát hiện hai race còn sót: pull worker có thể bắt đầu trước restore rồi ghi PART/MOVEMENT sau khi restore journal được tạo; cursor có thể được tiến sau khi page đã tải trong lúc restore bắt đầu.
- Bổ sung guard trong cùng Room transaction cho `applyPulledPart`, `applyPulledMovement` và `savePulledCursorIfUnlocked`; worker dừng page và retry, không tiến cursor qua event chưa áp dụng.
- Bọc `claimLegacyParts`, `claimLegacyMovements` và đường `restoreAll` cũ bằng guard cùng transaction; xác nhận các primitive insert/delete/upsert liên quan chỉ còn được gọi bên trong wrapper/transaction đã kiểm tra.
- Cập nhật `STEP25_REVIEW.md` và mục này để phản ánh đúng audit bổ sung. Đây vẫn là kiểm tra source-level, chưa phải bằng chứng compile hoặc race-test runtime.
- Chưa chạy Gradle/Kotlin compiler, unit/instrumentation hoặc coroutine concurrency tests; chưa chạy Supabase SQL/RPC, chưa build APK, chưa test thiết bị và chưa push GitHub.

## 2026-11-02 — Step 26: kiểm tra ngữ nghĩa lịch sử trong cloud restore
- Tiếp tục từ ZIP Step 25 đã kiểm tra cấu trúc.
- Rà soát migration `202611020006_restore_commit_and_snapshot_read.sql` và đối chiếu loại giao dịch với `MainActivity.kt`/`Data.kt`.
- Phát hiện phần validate MOVEMENT trước restore mới kiểm tra trường bắt buộc và tham chiếu PART, chưa kiểm tra enum giao dịch hoặc tính đúng của before/amount/after.
- Bổ sung kiểm tra loại `NHẬP`, `XUẤT`, `KIỂM KÊ`, `ĐẦU KỲ`; điều kiện amount và phép tính tồn trước/sau. Phép cộng/trừ được cast bigint trong biểu thức so sánh.
- Tạo `STEP26_REVIEW.md` cùng bộ test SQL staging cần chạy.
- Đây là thay đổi migration chưa chạy. Chưa có bằng chứng SQL parser/PostgreSQL execution, chưa chạy Supabase, Gradle, build APK, test thiết bị hoặc push GitHub.

## Step73 — Khôi phục working source và xác định ranh giới thực tế (2026-11-03)
- Khôi phục source từ archive Step26 còn nguyên vẹn vào working copy mới; giữ archive gốc không thay đổi.
- Áp dụng lại thay đổi Step47 đã được ghi nhận: object ảnh dùng `$ownerId/$partSyncId/$operationId.$extension`.
- Kiểm tra source-level 8/8 PASS: sender/call-site/signature/path và sự hiện diện của các file lõi Kotlin/SQL.
- Phát hiện source Step49–Step72 không có trong runtime; chỉ còn review/progress docs. Không tuyên bố các tính năng Step49–72 đã được tích hợp vào working source này. Không tái tạo hàng loạt từ mô tả vì cần kiểm tra contract thực tế.
- Xem `RECOVERY_STATUS.md` và `STEP73_REVIEW.md`.
- Chưa compile/build, chưa test thiết bị/Room runtime/Supabase staging, chưa tạo APK, chưa deploy SQL, chưa push GitHub.

## 2026-11-03 — Tái triển khai Step49–Step72 trên working source đã khôi phục
- Baseline là source Step26 được khôi phục + thay đổi ảnh Step47; không giả định source Step49–72 cũ còn tồn tại.
- Tái triển khai Part.serverVersion/Room migration 8→9, pull/restore mapping, expected_server_version cho PART_UPSERT/PART_DELETE/STOCK_CHANGE, freeze payload khi claim và rebase các operation cùng linh kiện sau mỗi RPC thành công.
- Thêm migration SQL CAS/tombstone cho upsert/delete/stock; positive expected version không thể tự tạo lại row đã mất. Thêm RPC snapshot chỉ đọc theo sync_id và auth.uid().
- Thêm phân loại lỗi vĩnh viễn sang BLOCKED, pause hàng đợi owner, bảo vệ pull/restore khỏi overwrite khi còn BLOCKED; UI có chi tiết, payload local, snapshot server, so sánh trường và đếm thao tác phía sau.
- Thêm decision record bền vững, preflight phụ thuộc, final server recheck và đường KEEP_LOCAL giới hạn ở PART_UPSERT độc lập; replacement operationId/version được liên kết trong cùng Room transaction. KEEP_SERVER/MANUAL_REVIEW/delete/stock/dependent chain vẫn fail-closed.
- Thêm atomic acknowledgement (version + rebase + SYNCED), FIFO claim, claimStartedAt và lease 30 phút; chỉ phục hồi lease hết hạn có timestamp dương. Legacy IN_FLIGHT timestamp 0 hiển thị cần đối soát, không tự reset.
- Kiểm tra `tools/verify_sync_step49_72.py`: 24/24 source-level PASS; SQLite smoke test migration additive + atomic replacement PASS.
- Đây không phải compile hoặc Room runtime test. Chưa chạy Kotlin/Android compiler, Room instrumentation, PostgreSQL/Supabase staging, Storage, test 2 thiết bị, APK, production deploy hoặc GitHub push.
- Giới hạn còn lại: chưa có KEEP_SERVER/MANUAL_REVIEW resolver, chưa xử lý tự động dependent chain/delete/stock conflict; lease chưa có heartbeat renewal/fencing token; legacy claimStartedAt=0 cần đối soát thủ công.
- Review chi tiết: `STEP49_72_RECONSTRUCTION_REVIEW.md`; test matrix: `docs/STEP49_72_TEST_MATRIX.md`.
- Kiểm tra bổ sung: SQLite EXPLAIN chấp nhận các SELECT DAO Outbox; standalone Kotlin compile của planner thuần PASS (không phải compile Android app/Room).

## Step73 — Sửa thứ tự kiểm tra idempotency RPC (03/11/2026)
- Rà soát lại `apply_inventory_operation` phát hiện nhánh trả cached result đang chạy trước kiểm tra payload; cùng operationId/type/entity nhưng payload đổi có thể không bị từ chối đúng.
- Chuyển kiểm tra `v_existing.payload IS DISTINCT FROM p_payload` lên trước nhánh `processed_at IS NOT NULL`; giữ row lock và các kiểm tra owner/type/entity hiện có.
- Thêm `tools/verify_step73_idempotency.py`; kết quả 5/5 kiểm tra tĩnh PASS.
- Chạy lại kiểm tra Step49–72: 24/24 PASS; SQLite schema/atomic replacement smoke test PASS.
- Chưa chạy PostgreSQL/Supabase runtime, concurrency/fault-injection, Room runtime, APK hoặc thiết bị. Migration chưa triển khai, chưa push GitHub.
- Review: `STEP73_REVIEW.md`.

## Step74 — Chặn NULL discriminator trong RPC (2026-11-03)
- Audit `apply_inventory_operation` phát hiện hai điều kiện `NOT IN` có thể trả NULL theo logic ba giá trị của SQL; PL/pgSQL `IF NULL` không đi vào nhánh lỗi.
- Bổ sung kiểm tra tường minh `p_entity_type IS NULL` và `v_type IS NULL`; giữ nguyên mã lỗi ổn định cho operation type và stock movement type không hợp lệ.
- Không đổi schema, không thay đổi luồng hợp lệ, không triển khai migration.
- Review: `STEP74_REVIEW.md`; test tĩnh: `tools/verify_step74_null_validation.py`.
- Chưa có PostgreSQL/Supabase runtime để xác nhận thực thi; không build APK, không test thiết bị, không deploy SQL, không push GitHub.

## Step75 — Chặn expected_server_version NULL trong STOCK_CHANGE (2026-11-03)
- Audit phát hiện `expected_server_version` có thể tồn tại trong JSON nhưng mang giá trị NULL/rỗng; phép `server_version <> NULL` trả NULL và `IF NULL` có thể bỏ qua kiểm tra CAS.
- SQL giờ parse token thành bigint một lần; từ chối thiếu/NULL/rỗng/sai định dạng/quá giới hạn/0/âm bằng `PART_VERSION_REQUIRED`; so sánh phiên bản bằng `IS DISTINCT FROM` sau khi khóa đúng PART.
- Cập nhật assertion tổng hợp để kiểm tra biểu thức null-safe mới, không còn phụ thuộc vào đoạn SQL cũ.
- Kiểm tra Step75: 7/7 PASS; Step74: 5/5 PASS; Step73: 5/5 PASS; Step49–72: 24/24 PASS và SQLite schema/atomic replacement smoke test PASS.
- Chưa có PostgreSQL/Supabase staging để chạy migration/RPC thực tế; chưa deploy SQL, build APK, test thiết bị hoặc push GitHub.
- Review: `STEP75_REVIEW.md`.

## Step76 — Xác minh phản hồi RPC trước khi xác nhận Outbox (2026-11-03)
- Rà lại đường retry sau khi máy chủ đã commit nhưng phản hồi có thể bị mất; RPC SQL trả cached result gốc cho cùng operationId/payload, còn sender trước đây chỉ cần parse `server_version` để hoàn tất.
- Gia cố sender: chỉ xác nhận Outbox khi status là `applied`, entity_id khớp đúng entity đang claim và server_version là số nguyên dương. Mọi phản hồi thiếu/sai trường dừng queue và không đánh dấu SYNCED.
- Giữ nguyên retry idempotent: phản hồi cached result gốc có status/entity_id/server_version nên vẫn được chấp nhận; payload thay đổi bị SQL từ chối trước cached replay.
- Thêm `tools/verify_step76_rpc_ack_guard.py`; kết quả 7/7 PASS. Chạy lại Step75 7/7, Step74 5/5, Step73 5/5 và Step49–72 24/24 (bao gồm SQLite smoke test), tất cả PASS.
- Giới hạn: chưa có PostgreSQL/Supabase staging để kiểm tra lost-response sau COMMIT hoặc retry đồng thời; schema gốc tạo sync_operations không có trong bộ migration phục hồi nên chưa xác minh được PK/unique constraint độc lập. Không build APK, test thiết bị, deploy SQL hoặc push GitHub.
- Review: `STEP76_REVIEW.md`.

## Step77 — Rebase phiên bản tuần tự cho các giao dịch cùng linh kiện (2026-11-03)
- Audit tiếp luồng sau RPC thành công phát hiện lỗi logic: `recordServerVersionAndRebase()` gán cùng một token phiên bản cho mọi operation PENDING/FAILED cùng entity. Khi có nhiều mutation, operation thứ hai sẽ xung đột vì operation thứ nhất đã tăng server_version.
- Sửa rebase theo FIFO: operation đầu nhận version vừa xác nhận; mỗi operation tiếp theo nhận version tăng thêm 1. `Part.serverVersion` chỉ lưu phiên bản thực tế đã xác nhận, không lưu phiên bản dự kiến.
- Dùng `Math.addExact` để overflow thất bại an toàn; yêu cầu update đúng một Part thuộc owner/entity trong transaction.
- Thêm `tools/verify_step77_sequential_rebase.py`: 7/7 static assertions PASS, FIFO version-chain model PASS, overflow fail-closed PASS.
- Chạy lại Step76 7/7, Step75 7/7, Step74 5/5, Step73 5/5, Step49–72 24/24 và SQLite smoke test; tất cả PASS.
- Chưa có Room runtime hoặc Supabase staging để chạy chuỗi mutation thật; không build APK, không test thiết bị, không deploy SQL, không push GitHub.
- Review: `STEP77_REVIEW.md`.

## Step78 — Xác nhận Outbox xóa sau khi local Part đã bị xóa (2026-11-03)
- Audit phát hiện `PART_DELETE` local xóa dòng Part ngay khi enqueue; sender trước đây vẫn gọi `recordServerVersionAndRebase()`, yêu cầu cập nhật một Part tồn tại. Vì vậy RPC có thể commit tombstone nhưng Room acknowledgement rollback do Part local đã biến mất.
- Thêm DAO transaction `completeDeleteOutbox()` chuyên biệt: xác minh đúng owner/entity/type/status, xác nhận Part local vẫn vắng mặt, không còn mutation unresolved khác cùng entity, rồi mới mark chính delete Outbox SYNCED.
- Sender định tuyến PART_DELETE sang nhánh riêng; PART_UPSERT/STOCK_CHANGE và photo completion không đổi.
- Thêm `tools/verify_step78_delete_ack.py`: 7/7 static assertions và fail-closed state model PASS.
- Chạy lại Step77 7/7 + FIFO/overflow model; Step76 7/7; Step75 7/7; Step74 5/5; Step73 5/5; Step49–72 24/24 và SQLite smoke test; tất cả PASS.
- Chưa chạy Room runtime hoặc Supabase staging/lost-response test; không build APK, không test thiết bị, không deploy SQL, không push GitHub.
- Review: `STEP78_REVIEW.md`.

## Step79 — Bỏ qua PART change-feed snapshot cũ (2026-11-03)
- Rà soát tương tác giữa pull, restore và tombstone; phát hiện `applyPulledPart()` luôn upsert/xóa theo event mà không so sánh `serverVersion` với row local hiện tại.
- Bổ sung xác thực owner/entity, yêu cầu server_version dương, giữ restore/outbox guard; bỏ qua event có version nhỏ hơn hoặc bằng local version hiện tại để tránh ghi lùi/xóa nhầm một row mới hơn.
- Giữ nguyên xử lý tombstone vật lý và upsert ảnh cloud. Không thay đổi cursor protocol hoặc schema Room.
- Thêm `tools/verify_step79_stale_pull_guard.py`: 11/11 static + SQLite model checks PASS.
- Chạy lại Step78 7/7, Step77 7/7, Step76 7/7, Step75 7/7, Step74 5/5, Step73 5/5, Step49–72 24/24 và SQLite schema/atomic replacement smoke test; tất cả PASS.
- Giới hạn được ghi rõ trong `STEP79_REVIEW.md`: sau khi tombstone bị xóa vật lý, version guard không thể phát hiện một event cũ bị đảo thứ tự tới sau; ordered change-feed cursor là hàng rào hiện tại. Muốn bảo vệ cả trường hợp đảo thứ tự cần local tombstone/version ledger hoặc soft delete.
- Chưa có Room runtime, PostgreSQL/Supabase staging, concurrent-worker/lost-response fault injection. Không build APK, không deploy SQL, không push GitHub.

## Step80 — Lưu tombstone/version fence cục bộ (2026-11-03)
- Step79 đã nêu rõ giới hạn: sau khi pull tombstone xóa vật lý Part, local không còn version để chống replay live event cũ nếu sự kiện bị đảo thứ tự sau khi process chết.
- Thêm Room entity `PartTombstone` khóa ghép ownerId+syncId, lưu serverVersion/deletedAt; migration additive 11→12.
- `applyPulledPart()` so sánh event với version cao nhất giữa Part và tombstone; tombstone mới lưu bền vững trước khi xóa Part; live event chỉ xóa tombstone nếu version mới hơn và upsert trong cùng transaction.
- Restore snapshot giữ tombstone của entity không còn trong snapshot; chỉ dọn tombstone khi Part snapshot cùng owner/entity có version cao hơn. Từ chối snapshot Part thiếu server_version dương.
- Kiểm tra Step80: 16/16 PASS, bao gồm thực thi SQL migration 11→12 trong SQLite và state model cho replay cũ/tombstone trùng/live version mới hơn/owner isolation.
- Step79: 12/12; Step78: 7/7; Step77: 7/7; Step76: 7/7; Step75: 7/7; Step74: 5/5; Step73: 5/5; Step49–72: 24/24 + SQLite schema/atomic replacement smoke test PASS.
- Kiểm tra trực tiếp câu SQL dọn tombstone có version thấp hơn live row trên SQLite: PASS.
- Chưa chạy Room instrumentation/runtime, PostgreSQL/Supabase staging, test hai thiết bị, concurrency hoặc process-kill fault injection. Không build APK, deploy SQL, push GitHub hay thay đổi dữ liệu máy chủ.
- Review: `STEP80_REVIEW.md`; regression: `tools/verify_step80_tombstone_ledger.py`.
- Giới hạn: tombstone chưa có garbage collection; giữ lại là chủ ý fail-safe cho đến khi có quy trình dọn an toàn.

## Step81 – Movement history consistency validation (2026-11-03)
- Fixed restore validation mismatch: Android ZIP mapper and Room snapshot applier now enforce the same supported movement types and before/after quantity semantics already required by the cloud restore commit path.
- Added `tools/verify_step81_movement_consistency.py`; result 20/20 PASS.
- Re-ran Step73–80 and reconstructed Step49–72 source checks: all PASS (Step80 16/16, Step79 12/12, Step78 7/7, Step77 7/7, Step76 7/7, Step75 7/7, Step74 5/5, Step73 5/5, Step49–72 24/24).
- No Android runtime, PostgreSQL/Supabase staging, APK build, device test, or deployment performed.
- Latest checkpoint: `KhoLinhKienAndroid-step81-movement-consistency-20261103.zip`.

## Step82 – Chặn lịch sử đồng bộ trùng ID nhưng khác nội dung (2026-11-03)
- Audit `applyPulledMovement()` phát hiện `INSERT OR IGNORE` bỏ qua xung đột unique `syncId` nhưng vẫn trả success; sự kiện trùng ID với payload khác hoặc owner khác có thể làm cursor tiến mà không báo lỗi.
- Thêm lookup theo `syncId` trong cùng Room transaction; replay chỉ idempotent nếu toàn bộ trường lịch sử bất biến khớp (bỏ qua local auto-id). Nếu payload/owner khác, fail-closed; nhánh race sau INSERT IGNORE cũng đọc lại và xác minh.
- Pull mapper yêu cầu UUID chuẩn, từ chối owner_id lệch, kiểm tra loại giao dịch và số lượng trước/sau như Step81; timestamp thiếu/sai bị từ chối thay vì tự thay bằng giờ thiết bị.
- Không đổi schema; giữ unique index hiện có trên movements.syncId.
- `tools/verify_step82_movement_idempotency.py`: 14/14 PASS, gồm model replay chính xác, payload collision và cross-owner collision.
- Chạy lại Step81 20/20; Step80 16/16; Step79 12/12; Step78 7/7; Step77 7/7; Step76 7/7; Step75 7/7; Step74 5/5; Step73 5/5; Step49–72 24/24 + SQLite smoke test; tất cả PASS.
- Chưa chạy Room runtime, Supabase/PostgreSQL staging, test đa thiết bị/concurrency/fault-injection, APK, thiết bị hoặc deploy. Không push GitHub.
- Review: `STEP82_REVIEW.md`.

## Step83 – Khóa loại giao dịch và chống tràn số khi thay đổi tồn kho trực tiếp (2026-11-03)
- Audit `InventoryDao.changeStock()` phát hiện nhánh `else -> amount` cho phép loại giao dịch bất kỳ đi vào như kiểm kê, ghi movement không hợp lệ và enqueue Outbox; phép cộng/trừ được thực hiện bằng Int trước khi kiểm tra.
- Giới hạn thao tác trực tiếp chỉ còn NHẬP/XUẤT/KIỂM KÊ; ĐẦU KỲ chỉ được tạo nguyên tử trong `insertPartWithOpeningMovement()` khi thêm linh kiện.
- Từ chối tồn cục bộ âm; tính bằng Long, kiểm tra miền 0..Int.MAX_VALUE rồi mới chuyển về Int. Giữ update Part + insert Movement + enqueue Outbox trong cùng @Transaction.
- Thêm `tools/verify_step83_live_stock_guard.py`: 16/16 PASS, gồm nhập/xuất/kiểm kê hợp lệ và từ chối loại lạ, giả mạo đầu kỳ, tồn âm, xuất vượt tồn, overflow và kiểm kê âm.
- Chạy lại Step82 14/14; Step81 20/20; Step80 16/16 (gồm SQLite migration/state); Step79 12/12; Step78 7/7; Step77 7/7; Step76 7/7; Step75 7/7; Step74 5/5; Step73 5/5; Step49–72 24/24 + SQLite schema/atomic replacement smoke test; tất cả PASS.
- Chưa chạy Android Room runtime/instrumentation, PostgreSQL/Supabase staging, test đa thiết bị/concurrency/fault injection; không build APK, test thiết bị, deploy SQL hoặc push GitHub.
- Review: `STEP83_REVIEW.md`.

## Step84 – Khóa định danh và tồn kho khi sửa thông tin linh kiện (2026-11-03)
- Audit `InventoryDao.update(part)` phát hiện DAO chưa tự bảo vệ các trường mà UI hiện không cho sửa: quantity có thể bị đổi không kèm Movement; ownerId/syncId/code/serverVersion có thể bị thay qua caller khác; trackingMode chưa allowlist tại DAO.
- Đổi API thành `update(ownerId, part)`; UI truyền `profile.id`; kiểm tra owner khớp và Part tồn tại trong owner scope.
- Giữ bất biến `syncId`, mã QR `code`, `serverVersion`, và `quantity`; thay đổi tồn kho buộc đi qua NHẬP/XUẤT/KIỂM KÊ để lưu Movement + Outbox cùng transaction.
- Kiểm tra name/unit không rỗng, minQuantity không âm, trackingMode chỉ SINGLE/BULK; chặn BULK→SINGLE khi tồn > 1 để không biến một kho nhiều món thành mã đơn chiếc không nhất quán.
- `@Update` phải tác động đúng một dòng; PART_UPSERT vẫn enqueue trong cùng Room transaction và giữ xử lý previous photo path.
- Không đổi schema. QR code tiếp tục bất biến trong màn hình sửa; chưa tạo luồng đổi mã riêng vì cần unique collision check và thao tác rename nguyên tử ở server.
- Thêm `tools/verify_step84_part_edit_identity.py`: 20/20 PASS. Chạy lại Step83 16/16, Step82 14/14, Step81 20/20, Step80 16/16, Step79 12/12, Step78–76 21/21, Step75–73 17/17, Step49–72 24/24 + SQLite smoke test; tất cả PASS.
- Chưa chạy Android Room runtime/instrumentation, PostgreSQL/Supabase staging, test đa thiết bị/concurrency/fault injection; không build APK, test thiết bị, deploy SQL hoặc push GitHub.
- Review: `STEP84_REVIEW.md`.

## Step85 – An toàn vòng đời ảnh và xác nhận Outbox khi ảnh thay đổi liên tiếp (2026-11-03)
- Rà soát `MainActivity`, `Data.kt`, `SyncOutboxSender`, `BackupManager`, `ZipRestore`, `RestoreCloudCoordinator` và `RestorePhotoUploadCoordinator`.
- Phát hiện lỗi thực: khi ảnh cũ đang upload mà người dùng đổi ảnh lần nữa, RPC cũ có thể đã áp dụng trên cloud nhưng `completePhotoOutbox()` bắt buộc đường dẫn trong Part phải còn là ảnh cũ; giao dịch bị đánh FAILED và chặn FIFO. Xóa Part trong lúc upsert ảnh cũ đang bay cũng gây kẹt tương tự, dù đã có PART_DELETE tiếp nối.
- Sửa xác nhận ảnh: kiểm tra đúng Outbox owner/entity/type/IN_FLIGHT; chỉ thay photoPath nếu nó vẫn trỏ tới ảnh local đang upload; giữ nguyên ảnh mới nếu đã thay đổi; vẫn ghi nhận server_version và rebase các thao tác sau. Nếu Part đã bị xóa, chỉ xác nhận upsert cũ khi có PART_DELETE tiếp nối cùng owner/entity; trường hợp mất Part không có giao dịch xóa thì fail-closed.
- Giữ nguyên bảo vệ xóa file local: chỉ xóa sau khi RPC và ack cục bộ thành công, đồng thời không còn Outbox chưa xử lý nào tham chiếu đường dẫn đó.
- Không xóa object cloud cũ. Việc dọn ảnh cloud mồ côi được hoãn vì chưa có kiểm tra tham chiếu toàn hệ thống/đa thiết bị đủ an toàn; tránh xóa nhầm ảnh còn được backup, restore hoặc thiết bị khác sử dụng.
- Backup ZIP đã tải ảnh cloud về ZIP và đưa ảnh local đang chờ vào ZIP; restore upload/xác minh ảnh theo owner/restore/hash trước khi tạo payload cloud. Luồng restore vẫn yêu cầu Outbox rỗng và giữ owner scope.
- `tools/verify_step85_photo_outbox_race.py`: 13/13 PASS.
- Chưa chạy Room runtime/instrumentation, Supabase Storage/PostgreSQL staging, đa thiết bị/fault injection; không build APK, không test điện thoại, không deploy, không push GitHub.
- Review: `STEP85_REVIEW.md`.

## Step86 – Harden backup photo preflight for device migration (2026-11-03)
- Audit phát hiện `BackupManager.createZip()` mở/truncate URI ZIP trước khi tải và kiểm tra xong ảnh cloud. Nếu ảnh cloud thiếu hoặc tải thất bại, thao tác backup có thể để lại file đích dở dang và output stream chưa nằm trong phạm vi `use` của ZIP.
- Chuyển thời điểm mở output stream xuống sau vòng preflight toàn bộ ảnh local/cloud; preflight thất bại sẽ không mở file đích. Thư mục ảnh tạm vẫn được dọn trong `finally`.
- Phát hiện lệch chuẩn hóa đường dẫn: map `photoEntries` dùng `photoPath.trim()` nhưng JSONL tra cứu bằng path gốc. Sửa lookup dùng cùng khóa đã trim để không làm mất tham chiếu ảnh trong ZIP khi path có khoảng trắng ngoài.
- Giữ nguyên kiểm tra owner scope cho cloud path, giới hạn 20 MiB/ảnh, streaming ZIP và SHA-256 manifest; không di chuyển/xóa ảnh local hoặc xóa object cloud.
- `tools/verify_step86_backup_photo_preflight.py`: 7/7 PASS. Hồi quy Step85 13/13; Step84 20/20; Step83 16/16; Step82 14/14; Step81 20/20; Step80 16/16; Step79 12/12; Step78 7/7; Step77 7/7; Step76 7/7; Step75 7/7; Step74 5/5; Step73 5/5; Step49–72 24/24 + SQLite smoke test: tất cả PASS.
- Giới hạn: sau khi bắt đầu ghi ZIP, lỗi I/O của provider vẫn có thể để lại file ZIP một phần; không có bảo đảm thay thế nguyên tử chung cho mọi SAF provider. Chưa test Supabase Storage/PostgreSQL staging, Room runtime, đổi thiết bị thực, APK/điện thoại; không deploy SQL hoặc push GitHub.
- Review: `STEP86_REVIEW.md`.

## Step87 – Hiển thị ảnh cloud có xác thực trong chi tiết linh kiện (2026-11-03)
- Audit phát hiện `PartDialog` trước đây chỉ hiện nhãn “đã lưu trên cloud”, không có code tải/giải mã ảnh. Sau khi restore sang thiết bị mới, `photoPath` cloud hợp lệ vẫn không thể xem trực tiếp trong ứng dụng.
- Thêm `PartPhotoPreview(photoPath, ownerId)` vào màn hình chi tiết. Ảnh local đọc từ file riêng của app; ảnh cloud tải bằng `downloadAuthenticatedTo` với session Supabase hiện tại, không dùng public URL.
- Kiểm tra path cloud: không cho path tuyệt đối/backslash, segment rỗng/dot/dot-dot, và segment đầu phải đúng ownerId đang đăng nhập.
- Cache theo SHA-256 của object path; giới hạn file 20 MiB; giải mã bitmap theo kích thước mẫu để giảm RAM; I/O chạy trên Dispatchers.IO; lỗi tải/giải mã hiển thị thành thông báo trong dialog.
- Thêm `tools/verify_step87_cloud_photo_viewer.py`: 10/10 PASS. Chạy lại Step49–72 (24/24 + SQLite smoke) và Step73–86; tất cả đạt.
- Giới hạn: chưa có chính sách dọn/giới hạn tổng dung lượng cache; chưa chạy Android runtime, Supabase Storage thực, thiết bị thứ hai hoặc restore thực tế. Prefix validation phía app không thay thế Storage policy/RLS phía server. Không build APK, không deploy SQL, không push GitHub.
- Review: `STEP87_REVIEW.md`.

## Step88 – Giới hạn dung lượng và thời gian lưu cache ảnh cloud (2026-11-03)
- Bổ sung chính sách cache riêng cho file `.img` do `PartPhotoPreview` tạo trong `cacheDir/part_photo_cache`; không đụng đến ảnh nguồn, Room, ZIP backup hay object Storage.
- Giới hạn tổng cache 100 MiB, thời gian không hoạt động 30 ngày; xóa file cache rỗng/hết hạn và ưu tiên loại file lâu không được truy cập khi vượt ngân sách.
- Bảo vệ file ảnh đang được nạp trong lượt prune hiện tại; cập nhật `lastModified` sau lần sử dụng thành công; cache cũ hơn 30 ngày sẽ tải lại. Giữ giới hạn 20 MiB/ảnh.
- Dùng coroutine Mutex để tuần tự hóa kiểm tra/tải/prune, tránh nhiều lượt tải cùng đường dẫn và các lượt prune chạy chồng nhau.
- `tools/verify_step88_photo_cache_policy.py`: 11/11 PASS. Chạy lại Step49–72 24/24 + SQLite smoke và Step73–87; tất cả PASS.
- Giới hạn: giải mã bitmap xảy ra sau khi nhả Mutex; một lượt prune khác về lý thuyết có thể xóa file cache đúng lúc lượt preview trước đang giải mã. Chưa có runtime evidence; ghi nhận để xử lý bằng cơ chế pin/reference-count nếu kiểm thử thực tế tái hiện. Cache vẫn là dữ liệu tạm, Android có thể xóa bất kỳ lúc nào.
- Chưa chạy Android runtime/thiết bị, Supabase Storage thực, APK, deploy SQL hoặc push GitHub.
- Review: `STEP88_REVIEW.md`.

## Step89 – Xóa cache ảnh cloud khi kết thúc phiên tài khoản (2026-11-03)
- Audit phát hiện cache ảnh cloud từ phiên trước vẫn nằm trên thiết bị sau đăng xuất hoặc sau khi phiên khôi phục không còn hợp lệ. Owner-prefix đã chặn tài khoản khác yêu cầu path của chủ kho cũ, nhưng dữ liệu ảnh riêng tư vẫn còn tồn tại không cần thiết trên máy dùng chung.
- Thêm hàm chỉ xóa file `.img` do viewer tạo trong `cacheDir/part_photo_cache`; thao tác dùng chung Mutex với download/prune để không xóa giữa lúc đang lấy cache.
- Đăng xuất thủ công và lỗi xác minh phiên khi khởi động đều dọn cache trước khi kết thúc trạng thái hồ sơ hiện tại. Không xóa ảnh gốc, pending upload, Room, backup hoặc object Supabase Storage.
- `tools/verify_step89_logout_photo_cache.py`: 8/8 PASS. Chạy lại Step49–72 (24/24 + SQLite smoke) và toàn bộ Step73–88; tất cả PASS.
- Giới hạn: chưa test runtime trên máy dùng chung; bitmap đã giải mã có thể còn trong bộ nhớ đến khi Compose dispose/GC; sign-out server failure vẫn best-effort local logout/cache clear.
- Chưa chạy Android runtime/thiết bị, Supabase staging, APK, deploy SQL hoặc push GitHub.
- Review: `STEP89_REVIEW.md`.

## Step90 – Background sync session fencing (2026-11-03)
- Hardened `SyncOutboxSender`: exact Supabase user ID is rechecked before each queued operation, after claim, before photo Storage upload, before RPC, and after RPC before response acknowledgement.
- Hardened `SyncPullWorker`: exact session owner is rechecked after each page RPC and before every event is applied, preventing a page fetched under a switched session from being applied to the worker's captured owner partition.
- Session mismatch fails closed; no operation is acknowledged and no pull cursor is advanced for the discarded page. Existing operation ID/payload idempotency remains the retry mechanism.
- Verification: Step90 10/10; Step49–72 24/24 + SQLite smoke; Step73–89 regression scripts all passed.
- Checkpoint: `KhoLinhKienAndroid-step90-session-fencing-20261103.zip` (SHA-256 recorded alongside archive).
- Not performed: Android runtime, WorkManager/session race test, Supabase staging, APK build, SQL deployment, GitHub push, production deployment.
- Source remains reconstructed Step49–72 lineage, not byte-for-byte restoration of the missing original source.


## Step91 – NULL-safe authorization for Admin global movement feed (2026-11-03)
- Audit `pull_admin_movement_changes()` found `if lower(v_role) <> 'admin' then` was not NULL-safe. In PL/pgSQL, a NULL condition does not enter the IF branch; if a profile row had a NULL role, the Admin-only global feed gate could be bypassed.
- Added additive migration `202611030003_null_safe_admin_feed_role.sql`, replacing the RPC with explicit `v_role is null or lower(v_role) <> 'admin'` rejection. Existing active-profile check, `auth.uid()` binding, authenticated-only execute grant, and PART redaction remain unchanged. The prior migration is preserved.
- Added `tools/verify_step91_admin_role_null_guard.py` with static assertions and a SQL three-valued-logic truth-table model.
- This is a source-level SQL review only. No PostgreSQL/Supabase staging execution, migration deployment, or live role tests were performed. No APK build, device test, or GitHub push.
- Source lineage remains reconstructed Step49–72 branch, not byte-for-byte restoration of lost original source.
- Review: `STEP91_REVIEW.md`.

## Step92 – Lock down direct client access to profiles (2026-11-03)
- Audit of `AuthRepository` and `admin-users` found that the app directly reads `profiles` for self-profile and Admin KTV listing. The original/base schema migration and its permissive policies are absent from this reconstructed source archive, so the exact pre-existing RLS policy/grant state cannot be asserted.
- Added additive migration `202611030004_lock_down_profile_access.sql`: explicitly enables RLS; adds `is_active_admin_profile()` SECURITY DEFINER helper bound to `auth.uid()` and active Admin role; restrictive SELECT allows self or active Admin; restrictive INSERT/UPDATE/DELETE deny authenticated direct writes. Admin account management remains via service-role Edge Function; password completion remains via SECURITY DEFINER RPC.
- Added `tools/verify_step92_profile_rls.py`: 11/11 static assertions PASS.
- Full regression rerun: Step49–72 24/24 + SQLite smoke PASS; Steps73–92 all scripts PASS.
- Important: no staging execution. Before deployment, inspect actual Supabase base policies/grants/schema and validate service-role/SECURITY DEFINER write paths; do not infer missing base policy definitions from this archive. No SQL deployment, APK build/device test, or GitHub push.
- Review: `STEP92_REVIEW.md`.

## Step93 – Restrict direct parts/movements access to row owner (2026-11-03)
- Audit found that the reconstructed migration archive contains owner-scoped predicates in SECURITY DEFINER sync/restore RPCs and an active-profile restrictive policy, but does not include the original base migrations/permissive policies for `parts` and `movements`. Direct PostgREST isolation therefore could not be asserted from UI filters or RPC code alone.
- Added additive migration `202611030005_lock_down_inventory_owner_rls.sql`: enables RLS on both tables and adds RESTRICTIVE `FOR ALL TO authenticated` policies with `owner_id = auth.uid()` in both USING and WITH CHECK. This narrows existing permissive policies without granting access.
- Admin direct table access remains limited to own inventory; global Admin movement history remains through the SECURITY DEFINER admin feed RPC with its independent active-Admin check. Sync/restore RPC owner predicates remain explicit.
- Added `tools/verify_step93_inventory_owner_rls.py`: 12/12 static assertions PASS.
- Re-ran Step49–72 (24/24 + SQLite smoke test) and every Step73–93 regression script; all passed.
- Important: no staging execution. Before deployment, inspect actual base grants/policies and verify SECURITY DEFINER function owner privileges and live Admin/KTV PostgREST isolation. No SQL deployment, APK build, device test, or GitHub push.
- Review: `STEP93_REVIEW.md`.

## Step94 – Restrict direct client access to sync idempotency ledger (2026-11-03)
- Audit found no Android direct reference to `sync_operations`; inventory writes are routed through `apply_inventory_operation()`. The reconstructed archive does not contain the base ledger table migration or original grants/policies, so direct PostgREST safety could not be established.
- Added additive migration `202611030006_lock_down_sync_operation_ledger.sql` revoking all table privileges from `PUBLIC`, `anon`, and `authenticated`. The ledger is intended to be RPC-internal only; no schema or RPC behavior changed.
- Audited `apply_inventory_operation()`: SECURITY DEFINER, caller bound to `auth.uid()`, active profile check, owner/entity/type tuple check, exact payload check before cached replay, and authenticated-only RPC execute grant.
- Added `tools/verify_step94_sync_ledger_access.py`: 12/12 static assertions PASS.
- Regression: Step49–72 24/24 + SQLite smoke; every Step73–94 verifier passed.
- Deployment prerequisite: inspect actual `sync_operations` owner/grants/policies and ensure the SECURITY DEFINER function owner retains SELECT/INSERT/UPDATE privileges after revocation. Base schema/owner migration is absent from reconstructed archive. No staging run, SQL deployment, APK build, device test, or GitHub push.
- Review: `STEP94_REVIEW.md`.

## Step95 – Storage owner scope and Admin Edge Function input hardening (2026-11-03)
- Added additive restrictive Storage policy for `part-images`: every authenticated read/write/delete path must have first folder equal to `auth.uid()` and an active profile. This ANDs with all existing permissive policies, preventing a broad policy from widening owner access.
- Confirmed normal upload and restore paths both begin with the authenticated owner UUID.
- Hardened `admin-users` request parsing: object-only body, action allowlist, strict username/display_name types, and rejection of unexpected fields. Existing active-Admin bearer authorization remains; account creation hardcodes role `ktv`.
- `tools/verify_step95_storage_admin_guard.py`: 15/15 PASS. Re-ran Step49–72 (24/24 + SQLite smoke) and every Step73–94 verifier; all passed.
- No Supabase staging execution, Storage live test, Edge Function deployment, SQL deployment, APK/device test, or GitHub push. Inspect actual base Storage policies, bucket settings, function secrets and grants before deployment.
- Review: `STEP95_REVIEW.md`.

## Step96 – Restore and Admin RPC execute-grant hardening (2026-11-03)
- Audit covered restore staging, commit, snapshot read/verify, and Admin global movement feed SECURITY DEFINER RPCs.
- Existing migrations revoked PUBLIC and granted authenticated, but did not explicitly revoke a possible historical direct `anon` grant. Added additive migration `202611030008_harden_restore_rpc_execute_scope.sql` to revoke all privileges from PUBLIC/anon/authenticated and regrant EXECUTE only to authenticated for five restore RPCs and the Admin global feed RPC.
- Function bodies and owner-scoped predicates are unchanged; all functions retain their own `auth.uid()`, active-profile, or active-Admin checks. The superseded one-shot restore RPC is not reintroduced.
- `tools/verify_step96_restore_rpc_grants.py`: 21/21 PASS. Re-ran Step49–72 (24/24 + SQLite smoke) and every Step73–96 verifier; all passed.
- No staging execution, catalog inspection, SQL deployment, APK build, Android device test, Edge Function deployment, or GitHub push. Confirm exact function signatures/owners and live grants before staging deployment.
- Source lineage remains reconstructed Step49–72 branch, not byte-for-byte restoration of lost original source.
- Review: `STEP96_REVIEW.md`.

## Step97 – Bound restore staging resources (2026-11-03)
- Audit found restore staging had a 1 MiB / 200-row per-chunk limit but no cumulative per-owner byte cap, chunk-count cap, active-session cap, or Android-invoked stale-session cleanup.
- Added migration `202611030009_bound_restore_staging_resources.sql`: one active STAGING session per owner; maximum 8,192 chunks; maximum 256 MiB cumulative JSONB payload; owner-bound cleanup expires STAGING sessions idle for more than 14 days, marks them ABORTED, and deletes only chunks belonging to sessions expired by that exact invocation.
- COMMITTED snapshots and commit ledger are retained; no cleanup of immutable committed data is attempted because other devices may still need snapshot pull.
- `RestoreCloudSessionSender` calls the authenticated cleanup RPC before begin/resume and rechecks exact current user ID after the call.
- `tools/verify_step97_restore_staging_quota.py`: 25/25 PASS. Full regression: Step49–72 24/24 + SQLite smoke; every Step73–97 verifier passed.
- No PostgreSQL/Supabase staging execution, SQL deployment, APK build, Android device test, Edge Function deployment, GitHub push, or production change.
- Limitation: local restore journal older than 14 days may point to a server session now marked ABORTED; current behavior fails closed and may require reimporting ZIP to create a new restore ID. Committed snapshot retention remains unbounded pending an acknowledgement/retention protocol.
- Source lineage remains reconstructed Step49–72 branch, not byte-for-byte restoration of lost original source.
- Review: `STEP97_REVIEW.md`.

## Step98 – Serialize restore commits across devices (2026-11-03)
- Audited concurrent restore begin/stage/commit paths. Same-session staging serializes on the session row; Step97 quota operations use a per-owner advisory lock; commit uses an owner-wide commit lock and exact session row lock.
- Found a legacy-state gap: owners could already have multiple STAGING sessions created before Step97 quota enforcement. Distinct devices could commit those sessions sequentially and the later restore would replace the first restore's live inventory.
- Added `202611030010_single_restore_commit_per_owner.sql`: a BEFORE status-transition trigger requires exactly one owner-scoped STAGING session when a session transitions to COMMITTED. It shares Step97's owner quota advisory lock. Failure rolls back the whole commit transaction, including preceding live inventory replacement statements.
- Already-COMMITTED idempotent retries are unaffected; other owners are isolated; trigger function is not client-callable.
- Added `tools/verify_step98_restore_concurrency.py`: 16/16 PASS.
- Full regression: Step49–72 24/24 + SQLite smoke; every Step73–98 verifier passed.
- No PostgreSQL/Supabase staging execution, multi-device runtime test, SQL deployment, APK build/device test, Edge Function deployment, GitHub push, or production change.
- Before deployment, inspect and safely reconcile any pre-existing multiple STAGING sessions per owner. Do not delete COMMITTED snapshots as part of reconciliation.
- Source lineage remains reconstructed Step49–72 branch, not byte-for-byte restoration of lost original source.
- Review: `STEP98_REVIEW.md`.

## Step99 – Coordinate live inventory mutations with restore commits (2026-11-03)
- Audit found owner-wide restore commits take an exclusive transaction advisory lock keyed by `hashtextextended(owner_id::text, 0)`, but ordinary `apply_inventory_operation()` did not participate. Row-level CAS alone did not establish a clean owner-wide ordering between snapshot replacement and live mutation.
- Added `202611030011_fence_inventory_mutations_against_restore.sql`, replacing the inventory RPC with the Step75 CAS implementation plus a transaction-scoped shared advisory lock using the same owner/key-0 namespace. Existing staged and legacy restore paths take the matching exclusive lock.
- Shared lock allows ordinary mutations on different parts to proceed concurrently; existing row-level `FOR UPDATE` and server-version CAS remain responsible for same-part serialization/conflict. Restore waits for active mutations; later mutations wait until restore commits/rolls back.
- Added `tools/verify_step99_restore_inventory_fence.py`: 13/13 PASS, including static preservation checks and a deterministic reader/writer interleaving model.
- Full regression: Step49–72 24/24 + SQLite smoke; all Step73–99 verifier scripts passed.
- No PostgreSQL/Supabase staging execution, two-device runtime/fault-injection test, SQL deployment, APK build/device test, Edge Function deployment, GitHub push, or production change.
- Before deployment, verify every live inventory mutation path participates in this lock protocol and inspect live SECURITY DEFINER function definitions/owners/grants.
- Source lineage remains reconstructed Step49–72 branch, not byte-for-byte restoration of lost original source.
- Review: `STEP99_REVIEW.md`.

## Step100 – Enforce active QR/code uniqueness across devices (2026-11-03)
- Audit found Room's unique `(ownerId, code)` index protects only one device; the reconstructed SQL history did not establish a corresponding live server unique constraint. Two devices could create different part UUIDs with the same owner's code before pulling each other's changes if the live base schema lacks such a constraint.
- Added `202611030012_unique_active_part_code_per_owner.sql`: fail-closed preflight for duplicate active `(owner_id, code)` rows, then a unique partial index scoped to owner and active rows. Soft-deleted tombstones release the code; different KTV owners may reuse it.
- Existing Android Room uniqueness, create transaction, stable entity UUID, restore duplicate validation, owner checks, and Step99 restore fence remain unchanged.
- `tools/verify_step100_qr_code_concurrency.py`: 15/15 PASS. Full regression: Step49–72 24/24 + SQLite smoke; every Step73–100 verifier passed.
- No PostgreSQL/Supabase staging concurrency test, SQL deployment, APK build/device test, Edge Function deployment, GitHub push, or production change. Before deployment inspect actual base table/indexes and reconcile any duplicate active owner/code rows; do not auto-merge them.
- Constraint violation currently may surface as a generic database constraint error; a dedicated user-facing conflict message remains a follow-up.
- Source lineage remains reconstructed Step49–72 branch, not byte-for-byte restoration of lost original source. Review: `STEP100_REVIEW.md`.

## Step101 – Concurrent part delete vs stock movement (2026-11-03)
- Audited the two server commit orders for a delete racing an inbound/outbound/stocktake operation.
- Confirmed `PART_DELETE` uses expected-version CAS; `STOCK_CHANGE` locks the active part row, checks expected version, updates quantity/version, and inserts the movement in the same RPC transaction. The part/movement change-feed events and idempotency ledger result are also committed atomically.
- Deterministic model confirms exactly one stale competing operation wins: stock-first leaves the part and movement and blocks stale delete; delete-first leaves the tombstone and inserts no movement for the rejected stock operation.
- Confirmed local delete queues immutable PART_DELETE intent transactionally; pull skips entities with unsent operations; BLOCKED outbox stops later sends.
- No production logic change: existing CAS/transaction boundaries already protect authoritative quantity/history. Documented remaining UX limitation: a blocked local delete is not automatically restored from server state; must not auto-discard movement or resurrect tombstone.
- Added `tools/verify_step101_delete_stock_race.py`: 16/16 PASS. Full Step49–72 and Step73–101 regression run completed.
- No PostgreSQL/Supabase staging test, two-device runtime test, SQL deployment, APK build/device test, Edge Function deployment, GitHub push, or production change. Review: `STEP101_REVIEW.md`.

## Step103 – Implement isolated KEEP_SERVER conflict resolution (2026-11-03)
- Audit found `KEEP_SERVER` could be persisted for `PART_UPSERT`, but no executor existed; UI correctly warned that the queue remained blocked.
- Added `SyncConflictKeepServerResolver`: fresh authenticated owner-scoped snapshot read; reject missing/deleted server part; validate owner/entity UUID and positive server version; map canonical server fields into local `Part`.
- Added atomic DAO path limited to READY_FOR_SERVER_RECHECK + KEEP_SERVER + PART_UPSERT, existing local target, no dependent/attempted operations, no active restore, and no local code collision. It replaces only the target part, marks the original blocked operation RESOLVED and decision SERVER_APPLIED in one Room transaction. Movement history is untouched.
- Added explicit confirmation UI; queue resumes only after successful transaction. All other operation types, tombstones, missing rows, and dependent chains remain manual-only.
- `tools/verify_step103_keep_server_apply.py`: 13/13 PASS. Full regression: Step49–72 24/24 + SQLite smoke; every Step73–103 verifier passed.
- No Android compile/runtime/device test, PostgreSQL/Supabase staging, SQL deployment, APK build, Edge Function deployment, GitHub push, or production change. Verify deployed snapshot RPC JSON contract before production use.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source. Review: `STEP103_REVIEW.md`.

## Step104 – Reject automatic conflict choices when an Outbox dependency chain exists (2026-11-03)
- Audit found a mismatch: the owner-scoped FIFO queue could contain unattempted successor operations, and decision preparation allowed KEEP_LOCAL/KEEP_SERVER for some same-entity chains, while the actual executors later rejected every non-empty chain. This could leave a persisted choice that cannot execute and imply unsupported replay safety.
- Hardened `prepareConflictDecision()`: non-manual choices now require zero owner-queue successors, regardless of entity/type. The rejection reports the exact successor count. MANUAL_REVIEW remains available and does not mutate Outbox/history.
- Kept resolver-level checks as defense in depth. No operation is deleted, rewritten, replayed, or rebased; movement history and immutable payloads remain untouched.
- Added `tools/verify_step104_outbox_chain_guard.py`: 12/12 PASS. Full regression: Step49–72 24/24 + SQLite smoke; every Step73–104 verifier passed.
- No Android compile/runtime/device test, PostgreSQL/Supabase staging, SQL deployment, APK build, Edge Function deployment, GitHub push, or production change. Multi-operation conflict-chain replay remains manual-only.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source. Review: `STEP104_REVIEW.md`.

## Step105 – Crash-safe local acknowledgement after server commit (2026-11-03)
- Audited crash/network-loss windows around the Outbox RPC. Server idempotency already locks the operation ledger row, checks the exact immutable payload, and returns the cached result after a prior successful commit. Stock mutation, movement insertion, change-feed events and the ledger result are committed atomically in the same server transaction.
- Found a local Room atomicity gap: `completeOutboxWithServerVersion()` could update server version/rebase successors and return `false` if marking the Outbox row SYNCED failed. Returning false does not force a Room transaction rollback; the method also lacked exact owner/entity/type/IN_FLIGHT validation.
- Hardened local completion: validate the exact claimed row and positive server version; update version/rebase successors; require exactly one SYNCED transition and throw on failure so the entire Room transaction rolls back.
- Retry continues with the same immutable operation ID and payload; no new operation or SQL migration added.
- Added `tools/verify_step105_outbox_crash_ack_atomicity.py`: 16/16 PASS. Full regression: Step49–72 24/24 + SQLite smoke; all Step73–105 verifier scripts passed.
- No Android process-kill/network fault-injection, PostgreSQL/Supabase staging, SQL deployment, APK build/device test, Edge Function deployment, GitHub push, or production change.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source. Review: `STEP105_REVIEW.md`.

## Step106 – Logout/account-switch fencing for in-flight sync (2026-11-03)
- Found that logout did not cancel owner-scoped WorkManager push/pull jobs and swallowed sign-out errors before clearing the UI profile. `CancellationException` could also be caught as an ordinary `Exception` by sender/worker code.
- Added owner-specific cancellation for push/pull one-shot and periodic jobs (including Admin pull job names) before ending the shared Supabase session.
- Logout now verifies the shared client session is actually cleared. If sign-out fails and the old session remains active, keep the current profile and restore that owner's scheduled sync instead of falsely showing a signed-out state. Clear account photo cache only after the session ends.
- Sender, pull worker, and outbox worker now propagate coroutine cancellation. A cancelled in-flight send keeps its exact `IN_FLIGHT` state, immutable payload and operation ID for conservative lease recovery/idempotent retry; cancellation is not mislabeled as a business failure.
- Added `tools/verify_step106_logout_inflight_fence.py`: 13/13 PASS. Full regression: Step49–72 24/24 + SQLite smoke; all Step73–106 source verifiers passed.
- No Android lifecycle/network fault-injection, live Supabase staging, SQL deployment, APK build/device test, Edge Function deployment, GitHub push, or production change. Review: `STEP106_REVIEW.md`.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source.

## Step107 – Same-device KTV local-photo isolation (2026-11-03)
- Audited owner-scoped Room queries, WorkManager names/input, cloud photo paths/cache, local pending-photo paths, and sender upload validation.
- Found new camera/gallery images were written to one shared app-private pending-photo directory, and the sender accepted arbitrary absolute local paths from Outbox payloads. Existing database rows were owner-scoped, but the local file namespace was not.
- Changed new camera/gallery photo writes to `filesDir/pending_part_photos/<ownerId>/` with UUID filenames; helper requires owner ID. Sender now validates canonical file parent against the authenticated owner's directory before upload.
- Added narrow compatibility for existing legacy Outbox photo files directly under `pending_part_photos/`; no new writes use that directory. Preview rejects local paths outside app-private storage and verifies owner for newly scoped pending paths.
- `tools/verify_step107_owner_local_photo_isolation.py`: 14/14 PASS. Full regression: Step49–72 24/24 + SQLite smoke; every Step73–107 verifier passed.
- No Android runtime/multi-account test, Supabase staging, SQL deployment, APK build/device test, Edge Function deployment, GitHub push, or production change. Legacy restored `part_photos` files are not relocated in this step; a reference-aware local file migration would be required.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source. Review: `STEP107_REVIEW.md`.

## Step108 – KTV + date/time backup filename (2026-11-03)
- Implemented suggested ZIP filename `SL<USERNAME><ddMMHHmm>.zip`, using the authenticated KTV login username, uppercase stable ASCII alphanumeric token, and device-local date/time. Example: `SLKTVA03111219.zip`.
- Added a safe `KTV` fallback for blank/non-ASCII username tokens; Android document picker still permits manual rename.
- Backup ZIP payload, manifest owner ID, checksums, photo preflight, and restore logic are unchanged.
- Added `tools/verify_step108_backup_filename.py`: 11/11 PASS. Full regression: Step49–72 24/24 + SQLite smoke; all Step73–108 verifier scripts passed.
- No Android runtime/document-picker test, Supabase staging/deployment, APK build/install, Edge Function deployment, or GitHub push. Review: `STEP108_REVIEW.md`.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source.

## Step109 – Fixed account-bound backup filename (2026-11-03)
- User clarified that backup filenames must not be editable and must be generated from the signed-in account automatically.
- Replaced the ZIP `CreateDocument` picker flow (which allows user rename) with direct save using the immutable app-generated filename `SL<USERNAME><ddMMHHmm>.zip`.
- Android 10+: save to `Downloads/KiemSoatLinhKien/` through MediaStore with `DISPLAY_NAME` set by the app and `IS_PENDING` until the ZIP is fully written. No filename prompt or rename field is shown.
- Android 8/9 fallback: save under app-specific `Documents/KiemSoatLinhKien/` with the same fixed filename.
- Reject same-name collisions instead of overwriting a prior backup. On write failure, remove the incomplete destination. ZIP payload, manifest, checksums, photo preflight and restore format remain unchanged.
- UI disables repeated backup taps while saving and displays the final generated filename/location. The account login username is supplied by the authenticated profile; owner ID remains mandatory.
- `tools/verify_step109_fixed_backup_filename.py`: 13/13 PASS. Updated Step108 filename verifier 11/11 PASS. Updated Step86 preflight verifier for the new output-stream abstraction 7/7 PASS. Full regression: Step49–72 24/24 + SQLite smoke; every Step73–109 verifier passed.
- Not performed: Android device/MediaStore runtime test, PostgreSQL/Supabase staging/deployment, APK build/install, Edge Function deployment, GitHub push.
- Step108's statement that Android's document picker permits manual rename is superseded by Step109; ZIP backup no longer opens that picker. Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source.

## Step110 – Backup identity metadata and restore preflight preview (2026-11-03)
- Added optional `backupUsername`, `createdAt`, and fixed `fileName` metadata to the existing ZIP v2 manifest; owner ID, counts, checksums and v2 compatibility remain intact.
- Added bounded manifest-only preview after ZIP selection. It shows KTV, backup time, filename, part count and movement count before confirmation.
- Rejects owner mismatch before enabling restore confirmation. The authoritative ZipRestore flow independently rechecks owner, archive paths, entries, checksums and payload before staging/applying.
- Old v2 archives without optional metadata remain readable and show a legacy label/time-unavailable message.
- `tools/verify_step110_backup_metadata_preview.py`: 20/20 PASS. Step109 13/13, Step108 11/11, Step86 7/7; Step49–72 24/24 + SQLite smoke; all Step73–110 verifiers passed.
- No Android compilation/device UI test, ZIP picker runtime test, Supabase staging/deployment, APK build/install, Edge Function deployment, or GitHub push.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source. Review: `STEP110_REVIEW.md`.

## Step111 – Backup/restore size and entry-limit parity (2026-11-03)
- Found a mismatch: restore limited ZIPs to 10,002 entries, 300 MiB aggregate uncompressed bytes, 100 MiB per JSONL entry, 20 MiB per photo, and 1 MiB manifest, while backup only checked each photo's initial file length. Backup could therefore produce an archive that its own restore rejected.
- Added matching limits to BackupManager. Reject >9,999 photo entries; count actual uncompressed bytes for JSONL rows, photo stream chunks, and manifest; enforce aggregate/per-entry limits during writing; retain preflight-before-output and incomplete-file cleanup.
- ZIP v2 format, checksums, metadata, account-bound filename, owner validation and old archive compatibility unchanged.
- `tools/verify_step111_backup_restore_size_parity.py`: 14/14 PASS. Full regression: Step49–72 24/24 + SQLite smoke; all Step73–111 verifiers passed.
- No Android runtime/low-storage/large-archive test, Supabase staging/deployment, SQL deployment, APK build/install, Edge Function deployment, or GitHub push. Review: `STEP111_REVIEW.md`.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost source.

## Step112 – Low-storage restore preflight and abandoned ZIP-temp cleanup (2026-11-03)
- Found that a process kill could bypass `ZipRestore`'s `finally` cleanup and leave `cacheDir/zip_restore_*` extraction folders; subsequent restores did not reclaim them. There was also no minimum free-cache check before starting extraction.
- Added a 32 MiB minimum available-cache preflight with a clear user-facing message.
- Added conservative cleanup before restore: delete only canonical direct-child `zip_restore_*` folders under this app's cache directory that are at least 24 hours old. Current/new folders and unrelated cache content are not touched.
- Kept immediate best-effort `finally` cleanup. The sweep never touches durable `filesDir/restore_staging`, Room rows/journals, live inventory, Outbox, or cloud Storage.
- Existing ZIP 300 MiB aggregate/per-entry limits, owner checks, checksums and resumable durable staging remain unchanged.
- `tools/verify_step112_restore_temp_cleanup.py`: 10/10 PASS. Full regression: Step49–72 24/24 + SQLite smoke; all Step73–112 verifiers passed.
- No Android low-storage/process-kill/device test, Android compilation, APK build/install, PostgreSQL/Supabase staging, SQL deployment, Edge Function deployment, or GitHub push.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source. Review: `STEP112_REVIEW.md`.

## Step113 – Restore cancellation and process-interruption safety (2026-11-03)
- Audit found broad `catch (Exception)` handlers and `runCatching` around suspend restore phases; Kotlin `CancellationException` could be misclassified as an ordinary error during logout, lifecycle shutdown, or Android task cancellation.
- ZIP staging now propagates cancellation and leaves its durable `STAGING` journal/batches for safe same-archive re-import instead of trying to write `FAILED`.
- Cloud chunk staging preserves `CLOUD_STAGING` on cancellation; retries use the same idempotent restore/chunk keys and immutable payloads.
- Commit RPC cancellation preserves the pre-written `COMMIT_UNKNOWN` marker so resume can reissue the same idempotent RPC and resolve ambiguous server outcome.
- Snapshot download cancellation preserves `SNAPSHOT_DOWNLOADING`; retry clears only partial snapshot buckets and re-downloads verified data.
- Completed-journal cleanup no longer swallows cancellation; ordinary cleanup errors still leave the COMPLETE journal available for retry.
- `tools/verify_step113_restore_cancellation_resume.py`: 14/14 PASS. Step49–72: 24/24 + SQLite smoke. All 41 Step verifiers passed; Python tooling syntax check passed.
- No Android lifecycle/process-kill test, Android compilation/APK/device test, live Supabase staging, SQL/Edge Function deployment, or GitHub push. Review: `STEP113_REVIEW.md`.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source.

## Step114 – Restore journal and staging isolation across KTV accounts (2026-11-03)
- Audited the same-device account-switch path for restore journal visibility, resume/abort, local staging paths, and cloud photo staging ownership.
- Replaced restore journal reads keyed only by `restoreId` with DAO queries constrained by both `ownerId` and `restoreId` across all restore phases, lifecycle operations, ZIP import/retry, and the transactional Room snapshot apply.
- Tightened canonical staging directory validation to require the exact direct child `filesDir/restore_staging/<restoreId>`.
- Existing profile-scoped journal list and resume callback owner checks remain in place; staged-row DAO operations and cloud photo paths remain owner-scoped.
- `tools/verify_step114_restore_account_isolation.py`: 11/11 PASS. Step49–72 regression: 24/24 + SQLite schema/atomic replacement smoke PASS. All 42 Step verifiers passed; Python tooling syntax check passed.
- No Android account-switch/process-kill/device test, Android compilation/APK build/install, live Supabase staging, SQL/Edge Function deployment, or GitHub push.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source. Review: `STEP114_REVIEW.md`.

## Step115 – Per-account ZIP extraction temp isolation (2026-11-03)
- Follow-up audit after Step114 found that durable restore journals/staging were owner-scoped, but transient ZIP extraction directories still shared one global `zip_restore_<random>` namespace. A different KTV starting restore could therefore reclaim another account's abandoned temp workspace during the 24-hour cleanup sweep.
- New extraction workspaces now use `zip_restore_<ownerId>_<random>` after validating the owner ID format. Stale cleanup receives the active owner and removes only that owner's new-format workspace plus age-expired legacy unscoped UUID directories from older app versions; it does not select another owner's new-format workspace.
- Retained canonical direct-child validation, 24-hour age threshold, 32 MiB free-space preflight, immediate `finally` cleanup, ZIP entry/size/checksum limits, and durable `filesDir/restore_staging` behavior.
- Added `tools/verify_step115_zip_temp_account_isolation.py`: 10/10 PASS. Updated Step112 verifier for the owner-scoped namespace: 10/10 PASS. Step113: 14/14 PASS; Step114: 11/11 PASS; Step49–72 regression: 24/24 + SQLite schema/atomic replacement smoke PASS; Python tooling syntax check passed.
- A broad loop over every `verify_step*.py` was attempted but hit the command time limit while running the existing Step92 verifier; therefore no claim is made that the complete suite was rerun in this step. Step73–91 and Step93–100 were not re-certified as a complete batch here.
- No Android compilation/runtime/account-switch test, APK build/install, live Supabase/PostgreSQL staging, SQL/Edge Function deployment, or GitHub push. No live inventory, movement history, Outbox, sync cursor, user photo, or cloud object was intentionally modified.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source. Review: `STEP115_REVIEW.md`.

## Step116 – Strict owner identity and legacy ZIP-temp isolation (2026-11-03)
- Continued strictly from the Step115 ZIP checkpoint; no older source archive was used as the working base.
- Tightened owner ID validation to canonical UUID 8-4-4-4-12 format plus UUID parse/round-trip validation. `restore()` validates before stale cleanup, StatFs, or filesystem creation; cleanup independently rejects malformed IDs.
- Removed KTV-triggered deletion of legacy unscoped `zip_restore_<UUID>` folders. Since those names contain no authenticated owner marker, another KTV cannot safely determine ownership. Cleanup now only deletes aged, canonical direct-child directories in the active owner's namespace.
- Retained 24-hour threshold, immediate `finally` cleanup, ZIP size/entry/photo bounds, SHA-256 checks, 32 MiB preflight, and durable owner-scoped restore staging.
- Added `tools/verify_step116_restore_temp_legacy_isolation.py`: 11/11 PASS. Updated current-source regression assertions in Step112 and Step115 verifiers: 10/10 each. Step113: 14/14 PASS; Step114: 11/11 PASS; Step49–72: 24/24 + SQLite schema/atomic replacement smoke PASS; Python tooling compileall PASS.
- No Android compilation/runtime test, APK build/install, live Supabase/PostgreSQL staging, SQL/Edge Function deployment, or GitHub push.
- Legacy unscoped cache folders are intentionally left for Android cache management; cleanup by an arbitrary KTV is not safe without a trustworthy owner marker.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source. Review: `STEP116_REVIEW.md`.

## Step117 – ZIP-temp directory shape and path hardening (2026-11-03)
- Continued from the verified Step116 ZIP; SHA-256 matched before extraction and ZIP integrity passed.
- Tightened stale-temp cleanup to enumerate only directories whose suffix is a canonical UUID generated for that owner. Regular files and malformed lookalike names are excluded.
- Revalidated immediately before deletion that the candidate is still a directory, its canonical path equals its listed absolute path (excluding symlink redirection), it is a direct child of the canonical cache root, and its name still matches the active owner plus canonical UUID.
- Preserved 24-hour threshold, strict owner validation, exclusion of legacy unscoped folders, immediate `finally` cleanup, ZIP size/checksum protections, and durable restore staging.
- `tools/verify_step117_restore_temp_directory_shape.py`: 11/11 PASS. Updated current-source checks: Step116 11/11, Step115 10/10, Step112 10/10; Step113 14/14; Step114 11/11; Step49–72 24/24 + SQLite schema/atomic replacement smoke PASS; Python tooling compileall PASS.
- No Android compilation/runtime/device test, APK build/install, Supabase/PostgreSQL staging, SQL/Edge deployment, or GitHub push.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source. Review: `STEP117_REVIEW.md`.

## Step118 – Backup local-photo ownership validation (2026-11-03)
- Continued from the verified Step117 ZIP; SHA-256 matched before extraction and ZIP integrity passed.
- Found `BackupManager` accepted any absolute local `photoPath` if it existed, unlike the owner-scoped checks already applied to new pending-photo preview and Outbox upload. A bad/cross-account local path could be copied into another KTV's backup.
- Backup now validates canonical owner UUIDs, canonicalizes local paths, and requires them to remain inside app-private `filesDir`. New pending photos must be direct children of `pending_part_photos/<activeOwnerId>/`; legacy direct `pending_part_photos/pending_*` files remain narrowly supported for old Outbox rows. Other KTV pending directories and arbitrary external absolute paths are rejected before ZIP output opens.
- Cloud object paths still use the owner-prefix validator and authenticated download. Original local photos are read in place and never moved/deleted. Legacy restored files under shared app-private `part_photos` remain supported because older paths have no owner marker; their ownership cannot be independently proven from the path alone.
- `tools/verify_step118_backup_local_photo_owner_isolation.py`: 12/12 PASS. Updated Step86 preflight verifier: 7/7 PASS. Step107 owner local photo isolation: 14/14 PASS. Step111 backup/restore size parity: 14/14 PASS.
- No Android compilation/runtime or multi-account device test, APK build/install, live Supabase/PostgreSQL staging, SQL/Edge deployment, or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source. Review: `STEP118_REVIEW.md`.


## Step119 – Bounded temporary cloud-photo cache during backup (2026-11-03)
- Continued from Step118; verified the exact Step118 SHA-256 before extraction and ZIP integrity passed.
- Audit found that cloud photos were all downloaded into a cache workspace before ZIP writing, while each photo was limited to 20 MiB but cumulative temporary cache use had no explicit bound. A large inventory could consume substantial cache space before the ZIP writer later enforced its 300 MiB archive limit.
- Added a pre-download available-space check requiring room for the maximum permitted 20 MiB photo plus a 32 MiB reserve. Each downloaded file is immediately checked as a regular, non-empty file no larger than 20 MiB. Cumulative downloaded cloud-photo bytes are capped at the existing 300 MiB restore-compatible aggregate limit.
- Existing `finally` cleanup remains responsible for deleting the temporary cloud-photo workspace; local photos remain read in place and are not counted as temporary cloud downloads. ZIP format, checksums, owner validation, entry count, and aggregate ZIP accounting remain unchanged.
- `tools/verify_step119_backup_cloud_photo_cache_bounds.py`: 12/12 PASS. Step118: 12/12; Step107: 14/14; Step111: 14/14; Step109: 13/13; Step110: 20/20; Step112–118 focused checks and Step49–72 regression rerun as recorded below.
- No Android runtime/cache-pressure test, compilation, APK build/install, live Supabase/PostgreSQL staging, SQL/Edge deployment, or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source. Review: `STEP119_REVIEW.md`.

## Step120 – Interrupted backup partial-file isolation and publication (2026-11-03)
- Continued from Step119 after verifying its exact SHA-256 and ZIP integrity.
- Audit confirmed Step109 already used MediaStore `IS_PENDING`, but abrupt process death could leave a hidden unpublished row; Android 8/9 wrote directly to the final filename and could leave a truncated ZIP that looked complete.
- Android 8/9 now writes to an owner-scoped hidden `.partial` file, closes the ZIP, then renames to the final fixed filename. Existing backups are not overwritten. Exception paths delete the partial.
- Added conservative stale cleanup for Android 8/9 partials: current owner prefix + canonical UUID suffix, regular direct-child file, canonical path checks, and age >=24 hours.
- Android 10+ pending MediaStore rows now carry an exact owner marker. Later cleanup only deletes rows still pending, in the exact app Downloads subfolder, marked for the current owner, and at least 24 hours old. Published backups are excluded.
- ZIP v2 payload, fixed filename, duplicate-name protection, owner/photo checks, checksums, and restore compatibility unchanged.
- `tools/verify_step120_backup_interruption_atomic_publish.py`: 13/13 PASS. Focused Step119, Step118, Step109, Step108, Step111, Step107, Step86 and Step49–72 sync/SQLite checks rerun; ZIP integrity and Python tooling syntax passed.
- No Android process-kill/MediaStore/Android 8-9 rename/device test, compilation, APK build/install, live Supabase/PostgreSQL staging, SQL/Edge deployment, or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost source. Review: `STEP120_REVIEW.md`.

## Step121 – Read-back verification before backup publication (2026-11-03)
- Continued from the exact Step120 ZIP after verifying its SHA-256 (`5349729537ce2fd1bdd139168b4e4be9354cb0852351f64c16b00812a635678b`) and ZIP integrity.
- Found that Step120 only published after successful ZIP stream completion; it did not reopen the produced archive and validate the actual stored payload before making the backup visible.
- Added a bounded read-back pass in `BackupArchiveInspector`: validates entry count, safe names, duplicate entries, allowed payload names, per-entry and aggregate uncompressed limits, required manifest/JSONL files, ZIP v2, exact owner, JSONL record counts, exact checksum-name set and SHA-256 of every non-manifest entry.
- Android 10+ MediaStore rows remain pending until read-back passes; failures follow the existing deletion path. Android 8/9 partial files are verified before rename; failures delete the partial.
- `ZipRestore` retains independent full extraction and validation. ZIP v2 format and cloud-photo cache design unchanged.
- `tools/verify_step121_backup_readback_validation.py`: 15/15 PASS. Step120 13/13; Step119 12/12; Step118 12/12; Step113 14/14; Step112 10/10; Step111 14/14; Step109 13/13; Step108 11/11; Step107 14/14; Step86 7/7; Step49–72 24/24 + SQLite schema/atomic replacement smoke PASS. Python tooling compileall PASS.
- Limitation: this pass verifies local ZIP entries and payload checksums using `ZipInputStream`; it does not independently parse central-directory/EOCD records. This is recorded for a later design review rather than staging a second up-to-300 MiB archive in cache.
- No Android compilation, provider/filesystem fault injection, process-kill, low-storage/device test, APK build/install, live Supabase/PostgreSQL staging, SQL/Edge deployment, or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost source. Review: `STEP121_REVIEW.md`.

## Step122 – Strict ZIP Central Directory and EOCD verification (2026-11-03)
- Continued from exact Step121 ZIP after verifying its SHA-256 (`18583aecc041eebe65fa1af16f382f4f086aa4709aadbd9a3dda9a928e5922cc`) and ZIP integrity.
- Closed Step121's recorded limitation by adding a bounded random-access parser for EOCD, Central Directory records, Local File Headers and data descriptors. Rejects multi-disk/ZIP64, encrypted/unsupported entries, unsafe names, extra fields/comments, noncanonical attributes, inconsistent CRC/size metadata, overlapping/gapped local records and trailing bytes.
- The Central Directory name set must exactly match Step121's read-back verified entries. MediaStore remains pending until both passes succeed; Android 8/9 partial remains unpublished until both passes succeed. Existing cleanup paths handle failure.
- `tools/verify_step122_zip_central_directory.py`: 15/15 PASS. Standalone Kotlin/JVM parser exercise: 6/6 PASS (valid writer archive accepted; five corrupted structures rejected). Step121 15/15; Step120 13/13; Step119 12/12; Step118 12/12; Step113 14/14; Step112 10/10; Step111 14/14; Step109 13/13; Step108 11/11; Step107 14/14; Step86 7/7; Step49–72 24/24 + SQLite schema/atomic replacement smoke PASS. Python tooling compileall PASS.
- No Android compilation, MediaStore seekability/provider test, Android 8/9 filesystem test, device test, process-kill, low-storage or provider-fault injection. No APK build/install, live Supabase/PostgreSQL staging, SQL/Edge deployment, or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost source. Review: `STEP122_REVIEW.md`.

## Step123 – ZIP edge-case and BACKUP/RESTORE contract validation (2026-11-03)
- Continued from the exact Step122 archive after SHA-256 verification and ZIP integrity check.
- Verification-only step; production Kotlin source was left unchanged because targeted checks found no defect requiring a source edit.
- Expanded the actual Kotlin/JVM parser smoke to 15 cases: accepts app-shaped DEFLATED ZIP and zero-byte photo entry; rejects corrupted EOCD/comment/disk metadata, Central Directory CRC/method/flags/name length, encrypted entries, Local File Header mismatches, Data Descriptor CRC/size mismatches, Central Directory size mismatch and truncation.
- Added `tools/verify_step123_backup_zip_contract.py` to compare BACKUP writer, read-back inspector and RESTORE size/count/photo/checksum/owner contracts.
- Results: Step123 JVM parser 15/15; contract 20/20; Step122 15/15; Step121 15/15; Step120 13/13; Step119 12/12; Step118 12/12; Step113 14/14; Step112 10/10; Step111 14/14; Step109 13/13; Step108 11/11; Step107 14/14; Step86 7/7; Step49–72 24/24 + SQLite schema/atomic replacement smoke PASS. Python tooling compileall PASS.
- Environment check found no Android SDK, adb, or emulator executable; therefore this step ran the actual parser on JVM, not Android emulator. No Android framework compile, APK build/install, MediaStore provider/device test, process-kill/low-storage test, live Supabase/PostgreSQL staging, SQL/Edge deployment, or GitHub push. No Gradle used.
- Review: `STEP123_REVIEW.md`. Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost source.

## Step124 – Restore snapshot-applier cancellation propagation (2026-11-03)
- Continued from the exact Step123 ZIP after SHA-256 verification and ZIP integrity check.
- End-to-end restore review found `RestoreCloudSnapshotApplier.apply()` wrapped the suspend post-commit cleanup in `runCatching`, which could swallow coroutine `CancellationException` after the Room snapshot had already committed.
- Changed cleanup handling to rethrow `CancellationException`; ordinary cleanup exceptions still return `localCleanupComplete=false`. The retained `COMPLETE` journal and cursor guard continue to make cleanup safely retryable. No live inventory, ZIP, cloud protocol, or sync logic changed.
- Added `tools/verify_step124_restore_applier_cancellation.py`: 9/9 PASS. Step113 cancellation/resume: 14/14 PASS.
- Regression checks: Step123 20/20; Step122 15/15; Step121 15/15; Step120 13/13; Step119 12/12; Step118 12/12; Step117 11/11; Step116 11/11; Step115 10/10; Step114 11/11; Step112 10/10; Step111 14/14; Step110 20/20 (updated stale verifier assertions); Step109 13/13; Step108 11/11; Step107 14/14; Step86 7/7; Step49–72 24/24 + SQLite schema/atomic replacement smoke PASS. Python tooling compileall PASS.
- No Android SDK/emulator/device test, Android framework compilation, APK build/install, live Supabase/PostgreSQL staging, SQL/Edge deployment, or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost source. Review: `STEP124_REVIEW.md`.

## Step125 – Preserve movement history for deleted parts (2026-11-03)
- Continued from the exact Step124 ZIP after SHA-256 verification and ZIP integrity validation.
- End-to-end BACKUP/RESTORE audit found a product/data-integrity mismatch: the app deliberately retains movement history after deleting a part, and BACKUP serializes those rows, but both Room snapshot validation and cloud `commit_inventory_restore` required every movement code to exist in the current PART catalog. SQL also used an inner join that would omit orphan history.
- Room now validates the historical movement's own code/name/type/quantity/timestamp/UUID without requiring a current part row.
- Added `supabase/migrations/202611030013_restore_preserve_deleted_part_history.sql`: makes `movements.part_id` nullable, replaces the commit RPC while preserving owner/auth checks, transaction validation, duplicate checks, idempotency, chunk/count validation and cursor/event behavior, and inserts all movements using a LEFT JOIN. Historical rows retain their code/name and use NULL `part_id` only when the corresponding part is no longer in the current catalog. RPC execution remains authenticated-only.
- Added `tools/verify_step125_restore_deleted_part_history.py`: 20/20 PASS. Step124 9/9; Step123 20/20; Step122 15/15; Step121 15/15; Step120 13/13; Step119 12/12; Step118 12/12; Step117 11/11; Step116 11/11; Step115 10/10; Step114 11/11; Step113 14/14; Step112 10/10; Step111 14/14; Step110 20/20; Step109 13/13; Step108 11/11; Step107 14/14; Step86 7/7; Step49–72 24/24 + SQLite schema/atomic replacement smoke PASS. Python tooling compileall PASS.
- No PostgreSQL/psql available: migration not executed against a live database. No Android SDK/emulator/device test, Android framework compile, APK build/install, live Supabase RPC/Storage test, SQL/Edge deployment or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost source. Review: `STEP125_REVIEW.md`.
- Additional restore SQL regressions: Step96 RPC grants 21/21; Step97 staging quotas 25/25; Step98 single-commit concurrency 16/16; Step99 mutation/restore fence 13/13.

## Step126 – Verify normal-sync photo uploads before publishing cloud paths (2026-11-03)
- Continued from the exact Step125 ZIP after verifying its SHA-256 and ZIP integrity.
- Photo lifecycle audit found normal KTV Outbox uploads did not read the private Storage object back before publishing its `photo_path` in the database RPC, unlike the already-verified ZIP RESTORE path.
- Added pure Kotlin/JVM `PhotoIntegrity` helper. Normal Outbox now reads pending bytes once, enforces the 20 MiB limit on the exact byte array, computes SHA-256, uploads those bytes, rechecks active owner, downloads the object with authenticated Storage, rechecks owner, and requires exact byte count + SHA-256 before constructing the cloud payload. Failed verification leaves Outbox/local pending photo available for retry and does not publish the cloud path.
- Preserved deterministic owner/entity/operation paths, FIFO Outbox, RPC acknowledgement, delayed local-photo deletion, RESTORE verification, and cloud-only preview cache policy (100 MiB, 30-day sliding retention, logout cleanup).
- Step126 verifier 17/17; actual Kotlin/JVM PhotoIntegrity smoke 7/7. Photo regressions: Step85 13/13, Step86 7/7, Step87 10/10, Step88 11/11, Step89 8/8, Step95 15/15, Step107 14/14, Step114 11/11, Step118 12/12, Step119 12/12. Restore/backup regressions: Step96 21/21, Step97 25/25, Step98 16/16, Step99 13/13, Step110 20/20, Step111 14/14, Step112 10/10, Step113 14/14, Step120 13/13, Step121 15/15, Step122 15/15, Step123 contract 20/20 + actual Kotlin/JVM ZIP parser 15/15, Step124 9/9, Step125 20/20. Step49–72 sync: 24/24 + SQLite schema/atomic replacement smoke PASS. Python tooling compileall PASS.
- No live Supabase Storage integration/network-fault/account-switch test, Android SDK/emulator/device test, Android framework compile, APK build/install, PostgreSQL staging, SQL/Edge deployment, or GitHub push. No Gradle used.
- No live inventory, Outbox, cursor, local source photo, or cloud object was intentionally modified. Source lineage remains reconstructed from preserved checkpoints. Review: `STEP126_REVIEW.md`.

## Step127 – Photo retry idempotency and upload session fencing (2026-11-03)
- Continued from the exact Step126 ZIP after SHA-256 verification and ZIP integrity validation.
- Audited Storage upload → read-back → inventory RPC → Room Outbox acknowledgement → retry/cancellation → local pending-photo cleanup.
- Confirmed deterministic owner/entity/operation-ID Storage paths; SQL operation ledger serializes duplicate operation IDs, rejects changed payloads, and returns the cached result for an already-processed exact payload. A lost RPC response can therefore retry without duplicating the inventory mutation.
- Confirmed local source photos remain after failed/ambiguous RPCs and are only best-effort deleted after exact Room acknowledgement and a check that no other unresolved Outbox row references the path. No cloud object deletion is attempted; possible orphan objects from permanently rejected operations are intentionally retained pending a separately designed reference-aware server-side garbage collector.
- Found and fixed a narrow session-switch window: the shared auth session could change during local hashing after the prior owner check but before Storage upload. Added an exact active-owner check immediately after hashing and immediately before the Storage network call.
- Updated Step90 verifier to assert semantic ordering after hashing instead of brittle adjacency.
- Step127 verifier 18/18; Step126 photo read-back 17/17; Step85 photo/Outbox race 13/13; Step90 session fencing 10/10; Step106 logout/in-flight fence 13/13; Step49–72 sync 24/24 + SQLite schema/atomic replacement smoke PASS; Step113 cancellation/resume 14/14; Step125 deleted-part history 20/20; actual Kotlin/JVM PhotoIntegrity smoke 7/7; Python tooling compileall PASS.
- No live Supabase Storage/RPC/network-fault/account-switch test, PostgreSQL staging or migration deployment, Android SDK/emulator/device test, Android framework compile, APK build/install, or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost source. Review: `STEP127_REVIEW.md`.

## Step128 – Photo lifecycle and cloud-object safe-retention audit (2026-11-03)
- Continued from the exact Step127 ZIP after SHA-256 verification and ZIP integrity validation.
- Audited Part deletion, immutable PART_DELETE payload, local pending-photo cleanup, Storage owner policies, operation idempotency ledger, and append-only change-feed retention.
- Confirmed local pending photos are deleted only after exact RPC/Room acknowledgement and no unresolved Outbox references; RPC failure/cancellation does not reach the local deletion block. Confirmed Android client/current Edge Functions do not delete cloud objects.
- Confirmed a naive cloud garbage collector would be unsafe: `sync_operations.payload` retains replayable immutable payloads and `inventory_sync_events` retains append-only payload snapshots. There is currently no server-side GC function or explicit ledger/change-feed retention/compaction contract. Cloud orphan cleanup is therefore deliberately deferred; objects are retained rather than risking deletion of a referenced image.
- Added `tools/verify_step128_photo_lifecycle_retention.py`: 19/19 PASS. Step127 18/18; Step126 17/17; Step85 13/13; Step90 10/10; Step106 13/13; Step125 20/20; Step49–72 24/24 + SQLite schema/atomic replacement smoke PASS.
- No production Kotlin/SQL/Edge/Storage policy changes in this audit-only step. No live Supabase Storage/RPC, network-fault/cross-device test, PostgreSQL staging/deployment, Android SDK/emulator/device test, Android framework compile, APK build/install, or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints. Review: `STEP128_REVIEW.md`.

## Step129 – Deny client-side deletion of private part images (2026-11-03)
- Continued from the exact Step128 ZIP after verifying its SHA-256 (`86e9e15bfbb8dc6cd95fba11d5e0dfaf6f05eeae24ff18a52756e9d08c5f101b`) and ZIP integrity.
- Reviewed all repository Storage policy migrations, Android source call sites, and current Edge Functions. Found the historical permissive `part_images_owner_delete` policy still allowed an authenticated owner client to delete cloud objects directly, despite no current app code invoking it.
- Added migration `202611030014_deny_authenticated_part_image_deletes.sql`: drops that legacy permissive policy and adds a restrictive `FOR DELETE` policy for `anon` and `authenticated` that denies deletion from `part-images`; other buckets are outside this guard. Existing owner-folder/active-profile restrictive guard and read/upload/update behavior are unchanged.
- Added `docs/PHOTO_CLOUD_GARBAGE_COLLECTION_CONTRACT.md`. It documents retained photo references in current Parts, immutable operation ledger payload/results, append-only change-feed payloads, restore staging and ambiguous operations, and prerequisites for a future trusted collector. No candidate queue, collector, scheduler, or actual object deletion was implemented.
- Added `tools/verify_step129_server_only_photo_delete.py`: 18/18 PASS. Step128 19/19; Step127 18/18; Step126 17/17; Step95 15/15; Step85 13/13; Step90 10/10; Step106 13/13; Step49–72 24/24 + SQLite schema/atomic replacement smoke PASS. Python tooling compileall PASS.
- No PostgreSQL/Supabase staging execution or live Storage API delete-denial test; `psql` unavailable. Deployment must inspect actual hosted policies/grants and verify anon/authenticated denial plus trusted service-role behavior. No Android SDK/emulator/device test, Android framework compilation, APK build/install, Edge Function/scheduler deployment, or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost source. Review: `STEP129_REVIEW.md`.

## Step130 – Serialize change-feed event IDs by transaction commit order (2026-11-03)
- Continued from the exact Step129 ZIP after verifying SHA-256 (`38ddae87c63ba97a44342ac24f26d7441605a263d425978f5ae1533da2ba0c7c`) and ZIP integrity.
- Audited per-owner and Admin global keyset pagination, Room cursor persistence, event application idempotency, restore control markers, and all SQL change-feed writers.
- Found a real cursor correctness race: PostgreSQL `bigserial` values are allocated before commit and do not imply commit order. Transaction A could reserve event ID N, transaction B reserve N+1 and commit first, and a pull could advance beyond N before A commits, permanently skipping A. This affects both per-owner and Admin global feeds.
- Added `202611030015_serialize_sync_event_commit_order.sql`: removes the column default and installs a SECURITY DEFINER `BEFORE INSERT` trigger. The trigger takes a transaction-scoped global advisory lock before assigning the owned sequence value; the lock remains held until commit/rollback. The table-level trigger covers normal inventory operations, restore commits and future SQL writers. Global serialization is required by the Admin feed's single cross-owner cursor; throughput impact must be measured in staging.
- Updated `SyncPullWorker`: when the fifth page is full, it returns `Result.retry()` rather than success, continuing large backlogs from the last durably persisted event cursor instead of waiting only for the next 15-minute periodic run. A full page that was exactly the end can cause one harmless empty follow-up run.
- Added `docs/SYNC_CURSOR_COMMIT_ORDER.md` and `tools/verify_step130_sync_cursor_commit_order.py`.
- Step130 static/contract verifier: 29/29 PASS; deterministic model reproduces the old sequence/commit-order skip and verifies serialized assignment ordering. This is a model and source-contract check, not a live PostgreSQL concurrency test.
- No feed rows were deleted or rewritten. Existing per-event cursor persistence, Room idempotency, active-restore guard, owner partitioning, Admin PART redaction, restore marker validation, and cancellation propagation remain unchanged.
- No PostgreSQL/Supabase staging available; migration syntax, trigger locking, actual concurrent commit order, query latency and advisory-lock throughput are not live-verified. Migration must be deployed before relying on this fix. No Android SDK/emulator/device test, Android framework compilation, APK build/install, or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost source. Review: `STEP130_REVIEW.md`.

## Step131 – Prevent cursor rollback from overlapping pull workers (2026-11-03)
- Continued from the exact Step130 ZIP after SHA-256 verification (`58d02a770eedede6f34de8d637dd8b8cc4f02bdbbf4547b264236c14e6eb20e1`) and ZIP integrity validation.
- Found that one-time and periodic pull work use distinct WorkManager unique names and may overlap. Both workers read the cursor at startup; the previous `INSERT OR REPLACE` cursor persistence allowed a slower worker to overwrite a newer cursor with an older event ID.
- Updated `InventoryDao.savePulledCursorIfUnlocked` to advance monotonically with a Room-transactional `UPDATE ... CASE` plus `INSERT OR IGNORE`. It validates nonnegative event IDs and restricts the key to the owner's own feed or `admin-global:<ownerId>`. The active-restore check remains in the same transaction.
- Kept the exact `saveSyncCursor(REPLACE)` path only for authoritative RESTORE snapshot application, where the snapshot cursor must be set exactly in the same transaction as replacing rows and marking the journal COMPLETE.
- Used UPDATE + INSERT IGNORE rather than SQLite UPSERT syntax to preserve compatibility with older Android SQLite versions. No SQL migration or feed contract changed.
- `tools/verify_step131_monotonic_cursor_concurrency.py`: 18/18 source checks PASS; SQLite interleaving smoke proves cursor 400 cannot be rolled back by a stale 200 write, duplicate advances are harmless, owner/Admin cursor partitions remain isolated, and RESTORE can still set its exact snapshot cursor.
- Regressions: Step130 29/29; Step129 18/18; Step128 19/19; Step127 18/18; Step126 17/17; Step125 20/20; Step124 9/9; Step123 20/20; Step122 15/15; Step121 15/15; Step120 13/13; Step113 14/14; Step90 10/10; Step85 13/13; Step49–72 24/24 + SQLite schema/atomic replacement smoke PASS. Python tooling compileall PASS.
- No Android/Room compilation or instrumentation, actual WorkManager overlap, process-kill, live multi-device/Admin/KTV, PostgreSQL/Supabase, emulator/device, APK build/install, SQL/Edge deployment, or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte recovery of the original source archive. Review: `STEP131_REVIEW.md`.

## Step132 – Multi-device CAS review and Admin global movement feed integration (2026-11-03)
- Continued from exact Step131 ZIP after SHA-256 verification (`c874b1d880f1b5f9f65571b9e7e6eaafb406b8a6e5a5479e9b161629ef93576e`) and ZIP integrity validation.
- Reviewed multi-device stock/metadata/delete conflicts, Outbox FIFO/acknowledgement, event replay, Admin global feed and account-role scheduling.
- Confirmed server-side `expected_server_version` CAS, operation-ledger idempotency, one local in-flight Outbox row, sequential successor rebase, local pull guards and immutable movement replay prevent two stale devices from silently overwriting each other. Stock/delete conflicts remain manual-only.
- Found and fixed Admin global history integration gaps: app scheduling had always used the default owner-only pull; Admin global worker was never scheduled from MainActivity. Scheduler now always pulls the Admin's own inventory feed and additionally schedules the separate Admin-only global movement feed with its own cursor; role downgrade cancels global work.
- Fixed Admin global movement parsing/storage: require a valid server-supplied movement owner UUID, preserve the actual KTV ownerId, use an Admin-scoped restore fence, and dedupe Admin's own movement across owner/global feeds.
- `owner_name` is profile display metadata rather than immutable transaction data. Both feed paths now compare all immutable movement fields excluding ownerName, enrich from a nonblank global-feed owner name, and never erase an existing name with a blank owner-feed replay. Unique syncId and owner identity remain enforced.
- Added pure Kotlin `MovementOwnerResolver` and JVM smoke 8/8 PASS. `tools/verify_step132_multidevice_conflict_safety.py`: 53/53 PASS.
- Regressions: Step82 16/16; Step101 16/16; Step102 10/10; Step103 13/13; Step104 12/12; Step105 16/16; Step106 13/13; Step130 29/29; Step131 18/18 + SQLite interleaving smoke PASS; Step49–72 24/24 + SQLite schema/atomic replacement smoke PASS. Step124 9/9; Step125 20/20; Step126 17/17; Step127 18/18; Step128 19/19; Step129 18/18 also retained in regression run. Python compileall PASS.
- No SQL migration added; the existing Admin-only RPC is reused. No live Supabase/PostgreSQL role/feed test, real two-device CAS race, Room instrumentation, WorkManager overlap, emulator/device test, APK build/install, SQL/Edge deployment or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints. Review: `STEP132_REVIEW.md`; contract: `docs/ADMIN_GLOBAL_MOVEMENT_SYNC.md`.

## Step133 – Admin global history pagination, RESTORE partition and session audit (2026-11-03)
- Continued from exact Step132 ZIP after SHA-256 verification (`a7cc4637464300c4a107df0ba7871d7f2c70f8f5286c4057bd7be22b7623d5f3`) and ZIP integrity validation.
- Verification-only step; no production Kotlin/SQL/RPC changes were made because the audited contracts passed.
- Confirmed Admin RPC keyset pagination (`event_id > cursor`, ascending, server cap 500), Android page size 200/five-page run cap, per-event durable cursor advancement, retry after a full fifth page, redacted PART/SKIP markers, Admin role/session checks and separate personal/global cursor keys.
- Confirmed RESTORE replaces only rows for the restored owner, sets only that owner's personal snapshot cursor, preserves foreign KTV movement rows and leaves `admin-global:<adminId>` cursor untouched. Sign-out cancels account-scoped push/pull work before ending session and does not clear Room history. KTV history remains owner-scoped; Admin-only global history remains gated in UI; Admin backup/tools use own movements only.
- Added `tools/verify_step133_admin_history_pagination_restore.py`: 45/45 PASS. Deterministic 10,137-event model drains 200-row pages, max five pages/run, in 11 runs with no skipped or duplicate IDs. `MovementOwnerResolver` Kotlin/JVM smoke 8/8 PASS.
- Regressions: Step132 53/53; Step131 monotonic cursor + SQLite interleaving PASS; Step130 commit-order model PASS; Step82 16/16; Step90 10/10; Step91 10/10; Step96 21/21; Step99 13/13; Step107–123 all PASS; Step124–129 all PASS; Step49–72 24/24 + SQLite schema/atomic replacement smoke PASS. Python compileall PASS.
- Important UI limitation recorded: Admin `allMovements()` still materializes the entire local history into a `Flow<List<Movement>>`, and query ordering has no stable secondary key. Step133 verifies server/worker pagination only, not large-list UI performance. A later step should implement bounded local keyset paging without losing live updates or machine-history behavior.
- No PostgreSQL/Supabase staging, live multi-device/role transition, Android Room/WorkManager instrumentation, emulator/device test, APK build/install, SQL/Edge deployment or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints. Review: `STEP133_REVIEW.md`.

## Step134 – Bounded Admin history UI pagination (2026-11-03)
- Continued from the Step133 audit. Step133 had found that Admin server/worker synchronization was paginated but the UI still materialized every local movement row through `allMovements()`.
- Removed the unbounded `allMovements()` query and added bounded Room keyset queries for the newest page and older pages, ordered by `(time DESC, id DESC)` for deterministic ordering when timestamps tie.
- Admin UI loads at most 200 history rows initially, offers explicit refresh and on-demand 200-row load-more, deduplicates appended rows by `syncId`, and performs page reads on IO. Opening the History tab refreshes the first page. KTV history remains owner-scoped and live via its existing owner Flow.
- Machine QR history now uses a dedicated database query by machine/store code; Admin sees matching movements across owners, KTV only sees its own. Admin backup/tools continue using Admin's personal `ownMovements`, not global history.
- No Room schema/version change and no SQL/RPC change; this step adds DAO queries only.
- `tools/verify_step134_admin_history_local_paging.py`: 29/29 PASS. SQLite smoke read 1,250 rows in seven pages of 200 with no gaps/duplicates and stable tie ordering; machine history owner isolation PASS.
- Regressions: Step133 45/45; Step132 53/53; Step131 monotonic cursor + SQLite interleaving PASS; Step130 commit-order model PASS; Step82 16/16; Step90 10/10; Step91 10/10; Step96 21/21; Step99 13/13; Step124 9/9; Step125 20/20; Step126 17/17; Step127 18/18; Step128 19/19; Step129 18/18; Step49–72 24/24 + SQLite schema/atomic replacement smoke PASS. `MovementOwnerResolver` Kotlin/JVM smoke 8/8; Python compileall PASS.
- Remaining verification: Android Room annotation processing/Compose UI/scrolling, WorkManager-driven refresh and real device performance are not run. No live Supabase/PostgreSQL staging, APK build/install, SQL/Edge deployment or GitHub push. No Gradle used.
- Review: `STEP134_REVIEW.md`.

## Step135 – Admin history freshness, RESTORE refresh and role downgrade audit (2026-11-03)
- Continued from exact Step134 ZIP after verifying SHA-256 (`b21ae7532ab981f69c0b7d2012daf53af8869ecc47a931d49a9ecd5a484d4e8e`) and ZIP integrity.
- Added a scalar Room `Flow<Long?>` observation of `MAX(movements.id)`. Admin history now detects newly inserted rows even when a delayed sync carries an older event timestamp, and displays a refresh notice without replacing the currently loaded keyset pages. This avoids an unbounded history Flow.
- Confirmed the current Admin account UI has no display-name rename action (it supports create/reset/lock/unlock). Existing owner names are mutable metadata enriched on replay, but no retroactive rename of every historical row is claimed; a future rename feature needs an explicit policy and metadata refresh mechanism.
- Confirmed RESTORE continues replacing only the restored owner's movement rows and keeps the Admin-global cursor/foreign KTV history intact. Returning from Tools to History refreshes the first page; the watermark signals changed local rows where applicable.
- Added role-downgrade navigation guard: if the role changes from Admin to KTV while the Admin-only tab is selected, the UI returns to Inventory. KTV history remains owner-scoped; existing Admin worker cancellation and server role guards remain unchanged.
- `tools/verify_step135_admin_history_freshness_role_restore.py`: 15/15 PASS. SQLite smoke inserted delayed events with older timestamps and verified the insertion watermark detects them; keyset pages remain disjoint and descending.
- Regressions: Step134 29/29; Step133 45/45; Step132 53/53; Step131 monotonic cursor + SQLite interleaving; Step130 commit-order model; Step82 idempotency; Step90 session fencing; Step91 Admin role guard; Step96 restore RPC grants 21/21; Step99 restore fence 13/13; Step124–129 cancellation/restore/photo regressions; Step49–72 24/24 + SQLite schema/atomic replacement smoke. Python compileall PASS.
- No Android Room annotation processing, Compose runtime/rendering, WorkManager execution, emulator/device test, live Supabase/PostgreSQL staging, cross-device role transition, SQL/Edge deployment, APK build/install, or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints. Review: `STEP135_REVIEW.md`.

## Step136 – Stable Admin history freshness watermark (2026-11-03)
- Continued from the exact Step135 ZIP after verifying SHA-256 (`d268e62ddec5a37d011a3185754f6e06701a636f1554e56491bf0dacbc65836e`) and ZIP integrity.
- Found a false-positive in Step135 freshness detection: comparing global `MAX(id)` with the max ID of the visible time-ordered page could leave the stale-history warning permanently visible when a delayed event had a newer local insertion ID but an old event timestamp outside page 1.
- Added `currentMovementMaxId()` and now snapshot the insertion watermark BEFORE fetching the first page. Admin compares the live Room Flow with this independent refresh snapshot; delayed rows already present before refresh no longer keep the warning stuck, while rows inserted after the snapshot remain detectable. Role downgrade clears the snapshot.
- No Room schema/version, SQL/RPC, Edge, Storage policy, sync payload, or RESTORE transaction changes.
- Step136 verifier 14/14; updated Step135 15/15; Step134 29/29; Step133 45/45; Step132 53/53; Step131 monotonic cursor interleaving; Step130 29/29; Step129 18/18; Step128 19/19; Step127 18/18; Step126 17/17; Step125 20/20; Step124 9/9; Step99 13/13; Step96 21/21; Step91 10/10; Step90 10/10; Step82 16/16; Step49–72 24/24 + SQLite schema/atomic replacement smoke PASS. Python tooling compileall PASS.
- No Android Room annotation processing, Compose runtime/rendering, WorkManager execution, emulator/device test, live Supabase/PostgreSQL staging, cross-device sync, SQL/Edge deployment, APK build/install, or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints. Review: `STEP136_REVIEW.md`.

## Step137 – Fresh-install / empty-business-data test baseline (2026-11-03)
- Continued from exact Step136 ZIP after SHA-256 verification (`d51ea94a7cb7be08822910cba6ffbda29696881bdc0859e65a6d6a8065c8d23a`) and ZIP integrity validation.
- User confirmed old app already deleted; the new test cycle uses a fresh app-local database, no old backup restore, and re-registers/scans all part QR codes.
- Audit-only step: no production Kotlin, Room schema/version, SQL/RPC, Edge, Storage, sync payload, or RESTORE changes.
- Confirmed fresh Room database has no prepackaged DB import and starts with empty local parts, movements, outbox, and cursors. First owner pull starts at cursor 0; Admin global feed is separate. Under the stated assumption that the hosted owner/global event feeds contain no old events, no old records are imported. Live Supabase feed emptiness was not queried; if old server events remain, cursor-zero pull will import them. No server data was deleted.
- Confirmed first new part registration writes owner-scoped Part + PART_UPSERT outbox atomically; opening movement only exists when initial quantity > 0. Existing QR lookup is owner-scoped. Unknown scanned QR is treated as machine QR, not auto-created; new part must first be registered via Add Part or batch import.
- Step137 verifier 18/18; Step100–128 regressions PASS; Step129 rerun 18/18 after aggregate-run timeout; Step130 29/29; Step131 cursor interleaving; Step132 53/53; Step133 45/45; Step134 29/29; Step135 15/15; Step136 14/14; Step82 16/16; Step90/91 10/10 each; Step96 21/21; Step99 13/13; Step49–72 24/24 + SQLite schema/atomic replacement smoke PASS. Kotlin/JVM MovementOwnerResolver 8/8; Python compileall PASS.
- No actual Room/Compose compile, camera/ML Kit QR scan, WorkManager run, Android device/emulator, APK build/install, live Supabase/PostgreSQL, SQL/Edge deployment, or GitHub push. No Gradle used.
- Review: `STEP137_REVIEW.md`.

## Step138 – Live Supabase backend alignment and initial sync deployment (2026-11-03)
- Continued from exact Step137 ZIP after SHA-256 and ZIP integrity verification.
- Connected directly to Supabase Cloud project `ihhxxxqojelbxisreqns` (PostgreSQL 17.11, Singapore). Before changes: two active accounts (one Admin, one KTV), one Admin link, zero parts/movements/sync operations/Storage objects, and no tracked migrations despite pre-existing bootstrap schema.
- Deployed seven versioned, tracked migrations for password completion, sync schema/security foundation, inventory mutation and pull RPCs, Admin redacted feed, conflict snapshot key correction, Admin role revalidation, and explicit anon revoke. The correct private `part-images` bucket now exists; legacy typo bucket `part-images.` was deliberately retained.
- Deployed `admin-users` Edge Function v3. KTV creation now updates the profile created by the auth trigger instead of inserting a duplicate profile ID.
- Hardened `is_app_admin()` to require both an app_admins link and an active `profiles.role='admin'`, so a stale link cannot retain Admin RLS access after role downgrade.
- Live rollback test exercised new PART_UPSERT + STOCK_CHANGE + owner pull + Admin global pull; verified event count, stock quantity and PART-to-SKIP redaction; transaction rolled back. KTV impersonation test confirmed Admin global RPC rejects KTV with `ADMIN_REQUIRED`. No user rows or Auth accounts were added/deleted.
- The 25 original SQL migrations are preserved in `supabase/migrations_pending_reconciliation/`; only six reviewed migrations are in active `supabase/migrations`. The original restore RPC chain remains pending and must not be replayed blindly. `supabase db push` is not approved until migration history is reconciled.
- Regression suites Step134 29/29, Step133 45/45, Step132 53/53, Step130 cursor checks, Step129–124, Step101, Step100, Step99–90, Step82, Step76–73, and Step49–72 were rerun; see `STEP138_REVIEW.md`. `verify_sync_step49_72.py` was updated to check `parts.id` (the actual schema key) rather than the obsolete `sync_id`.
- Remaining backend blocker: cloud restore RPCs (`begin_inventory_restore`, `stage_inventory_restore_chunk`, `commit_inventory_restore`, `read_inventory_restore_snapshot_chunk`, `verify_inventory_restore_snapshot_chunk`, `cleanup_my_inventory_restore_staging`) have not yet been deployed. Do not test cloud backup/restore yet.
- Security/performance advisor findings remain documented in Step138 review. No Android framework compile, Room/Compose/WorkManager runtime, device test, APK build/install, GitHub push or Gradle use.
- Review: `STEP138_REVIEW.md`.


## Step141 — sửa preview ảnh cloud và đồng bộ RESTORE_COMMIT giữa thiết bị (03/11/2026)
- Đối chiếu lại Step140 phát hiện archive cũ có verifier 11/11 nhưng `MainActivity.kt` vẫn dùng `downloadAuthenticatedTo` và cache ảnh 30 ngày; báo cáo 11/11 cũ không hợp lệ. Đã sửa implementation và verifier đồng bộ, không giữ kết quả sai.
- Preview ảnh cloud hiện dùng `downloadAuthenticated` trong RAM, xác thực prefix owner và path traversal, giới hạn 20 MiB, decode ảnh tối đa 1.200 px, zero byte buffer trong `finally`, truyền cancellation; không ghi preview cloud vào `cacheDir`/`filesDir`. Ảnh pending local vẫn chỉ đọc trong thư mục riêng của app và đúng owner.
- Phát hiện `PartDialog` được gọi nhưng thiếu định nghĩa trong source archive; đã bổ sung dialog hiển thị ảnh, thông tin linh kiện, nhập/xuất/kiểm kê, sửa/xóa và tạo QR.
- Bổ sung Room schema v13 / migration 12→13 với `sourceType`, `partChunkCount`, `movementChunkCount`; journal cũ mặc định `LOCAL_ZIP`.
- `SyncPullWorker` giờ xử lý RESTORE_COMMIT ở owner feed: chỉ tiến cursor sau khi tải/verify snapshot và Room transaction thay kho hoàn tất; journal `REMOTE_FEED` có thể tự resume sau process death. Nếu còn outbox local, marker không bị bỏ qua và thao tác chưa đồng bộ không bị xóa.
- Admin global feed chỉ advance global cursor; không thay kho cá nhân Admin bằng snapshot của KTV khác. UI báo phiên restore nhận từ cloud và không yêu cầu chọn lại ZIP.
- Supabase read-only audit: Step139 migrations đã hiện diện; 3 bảng restore và 6 RPC restore đang có. Test transaction rollback xác nhận begin/stage/commit/read/verify và marker Admin feed; test replay cùng chunk idempotent, payload khác bị từ chối, KTV không đọc/commit/reuse restore của Admin, và không có direct SELECT grant vào bảng staging. Tất cả fixture được rollback; dữ liệu thật vẫn trống.
- Kiểm tra ảnh: Step87 12/12, Step88 8/8, Step89 5/5, Step126 19/19, Step140 13/13; Python compileall PASS. SQLite migration 12→13 có test giữ nguyên journal cũ.
- Chưa biên dịch Android framework/Room, chưa build APK, chưa cài/kiểm thử điện thoại, chưa push GitHub. Không chạy Gradle.
- Không có SQL migration, thay đổi Auth users hoặc thay đổi Storage objects trong Step141. Cảnh báo Supabase còn: leaked password protection đang tắt; advisor cũng ghi RLS no-policy trên bảng staging/event (đã cố ý thu hồi direct table grants, RPC là đường truy cập), và 14 SECURITY DEFINER RPC callable by authenticated (đây là RPC nghiệp vụ có kiểm soát owner/role).

## Step142 – Đối chiếu fingerprint Supabase và kiểm tra Admin Edge Function (03/11/2026)
- Tiếp tục trực tiếp từ working tree Step141, không khôi phục archive cũ.
- Read-only audit lại migration ledger: đúng 16 migrations (7 Step138 + 9 Step139), version đầu `20261103094857`, cuối `20261103101525`. Ledger chỉ lưu version/name, không cung cấp nội dung SQL gốc; vì vậy không giả lập lại chín migration Step139 từ trạng thái schema cuối và không đưa các file `migrations_pending_reconciliation` vào active migrations.
- Ghi 12 fingerprint `md5(pg_get_functiondef(...))` của các RPC/guard nhạy cảm vào `supabase/REMOTE_MIGRATION_MANIFEST.md`; ghi rõ đây là fingerprint trạng thái hiện tại, không phải schema dump hay migration có thể replay. Catalog trả về 10 bảng public (tất cả bật RLS), 22 policies, 22 indexes và 15 dòng table grants cho authenticated.
- Kiểm tra Edge Function live: `admin-users` ACTIVE version 3, `verify_jwt=false`, handler tự xác thực bearer token qua `auth.getUser`; v3 kiểm tra profile Admin hoạt động nhưng chưa check `app_admins`. Candidate v4 có check link sau khi xác thực profile; vẫn chỉ là source, KHÔNG deploy. Không thử bypass safety guard.
- Bổ sung `STEP142_REVIEW.md` và `tools/verify_step142_remote_reconciliation.py` (12/12 PASS). Sửa một assertion cũ trong Step141 verifier: marker `RESTORE_COMMIT` được xử lý khác nhau cho owner feed (apply snapshot) và Admin-global feed (chỉ tiến cursor); verifier mới kiểm tra đúng logic thay vì phụ thuộc comment đã đổi. Step141 15/15 PASS.
- Step140 cloud-photo memory-only 13/13 PASS; Step96 restore grants 21/21; Step97 quota 25/25; Step98 concurrency 16/16; Step99 restore fence 13/13; Step126 photo readback 19/19; Step127 retry 18/18; Step128 lifecycle 19/19; Step129 server-only delete 18/18; Step133 Admin pagination 45/45; Step134 local paging 29/29; Step135 freshness/role 15/15; Step136 watermark 14/14; Step137 fresh-install empty baseline 18/18. Python compileall PASS.
- Không có live SQL/migration/data changes, không deploy Edge Function, không thay đổi tài khoản/Storage, không build/cài APK, không test QR trên điện thoại, không push GitHub. Không chạy Gradle.
- Còn tồn tại hai việc chưa đóng: (1) cần bản SQL migration gốc Step139 hoặc export được schema/migration history chính xác từ công cụ có hỗ trợ để source chain reproducible; (2) candidate `admin-users` v4 cần được deploy thủ công qua Dashboard nếu người dùng phê duyệt. Chưa cần user cấp quyền cho audit chỉ đọc.
- Review: `STEP142_REVIEW.md`.

## Step143 – Dọn cache lịch sử Admin khi hạ quyền (03/11/2026)
- Tiếp tục từ Step142; giữ nguyên toàn bộ trạng thái Supabase và dữ liệu máy chủ.
- Audit phát hiện khi profile chuyển từ Admin sang KTV, UI đã xóa `adminHistoryRows`/watermark và WorkManager hủy global pull, nhưng các movement của KTV khác do global feed tải về vẫn còn trong Room.
- Bổ sung DAO chỉ xóa movement có `ownerId` khác tài khoản hiện tại khi profile là KTV. Movement của chính tài khoản Admin (đồng thời là kho KTV của người dùng) vẫn được giữ.
- Xóa riêng cursor `admin-global:<ownerId>` khi hạ quyền để lần được cấp Admin lại có thể tải lại toàn bộ global history. Cursor cá nhân, outbox, kho cá nhân và lịch sử máy chủ không bị đụng tới.
- Cleanup chạy trên IO dispatcher; scheduler hiện có tiếp tục hủy hai Admin-global worker khi role là KTV.
- `verify_step143_admin_downgrade_local_cache.py`: 11/11 PASS; SQLite smoke xác nhận giữ dữ liệu own, xóa foreign-owner cache, reset đúng global cursor và giữ cursor không liên quan.
- Regression: Step142 12/12; Step141 15/15 + restore feed 13/13 và SQLite migration smoke; Step140 13/13; Step135 15/15; Step136 14/14; Step133 45/45; Step134 29/29; Step132 53/53; Step130 29/29; Step138 24/24; Step131 monotonic cursor; Python compileall PASS.
- Supabase read-only count sau thay đổi: profiles 2, app_admins 1; parts/movements/sync_operations/sync_events/restore sessions/chunks/commits đều 0. Không có live SQL/migration/Edge/Auth/Storage change.
- Chưa chạy Room instrumentation/Android runtime, build/cài APK, QR test, test hai thiết bị hoặc push GitHub. Không dùng Gradle.
- Review: `STEP143_REVIEW.md`.

## Step144 – Làm mới quyền tài khoản khi app quay lại foreground (03/11/2026)
- Tiếp tục từ đúng working tree Step143; không khôi phục ZIP cũ.
- Bổ sung `LifecycleEventObserver` nghe `ON_START`: mỗi lần app quay lại foreground, tải lại `UserProfile` từ Supabase trên IO dispatcher. Có chốt tránh refresh đồng thời và chỉ áp dụng kết quả nếu vẫn là cùng tài khoản đang đăng nhập.
- Phân biệt profile bị khóa bằng `InactiveProfileException`: trường hợp này kết thúc phiên và xóa UI có quyền. Lỗi mạng/transport tạm thời không sign out, không xóa profile/kho local; app giữ chế độ offline-first và báo không thể refresh.
- Nếu quyền Admin đổi sang KTV, state profile mới kích hoạt các effect hiện có: dọn lịch sử Admin UI, Step143 xóa foreign-owner movement cache/reset Admin-global cursor và hủy Admin-global workers. Nếu được nâng lại Admin, scheduler bật lại Admin feed.
- Verifier Step144: 14/14 PASS. Regression Step143 11/11; Step142 12/12; Step141 15/15; Step140 13/13. Python compileall PASS.
- Việc còn lại được ghi rõ: cold-start hiện vẫn sign out khi `getCurrentProfile()` gặp bất kỳ exception nào, kể cả mạng tạm mất. Step144 chưa sửa luồng cold-start; sẽ xử lý riêng để có đường retry an toàn mà không làm mất phiên offline.
- Không có Room schema, Supabase SQL/migration/RPC, Edge, Storage, Auth account hoặc dữ liệu máy chủ thay đổi. Chưa compile Android/Compose, chưa chạy WorkManager thật, chưa build/cài APK, chưa QR/2 thiết bị, chưa push GitHub. Không dùng Gradle.
- Review: `STEP144_REVIEW.md`.

## Step145 — Khôi phục phiên an toàn khi mở app (03/11/2026)
- Tiếp tục từ Step144. Sửa cold-start: trước đây mọi lỗi `getCurrentProfile()` đều bị xem như phiên hỏng và tự sign out, kể cả khi chỉ mất mạng.
- Tạo luồng `restoreSession` dùng chung cho lúc mở app và nút thử lại. Profile được xác minh trên IO dispatcher; coroutine cancellation được truyền tiếp.
- Nếu tài khoản bị khóa (`InactiveProfileException`), xóa profile trong bộ nhớ; repository thử sign out và giữ trạng thái khóa rõ ràng. Nếu lỗi mạng/server tạm thời, không sign out và không xóa Supabase session.
- Khi chưa xác minh được profile, app fail-closed: không hiển thị kho hoặc UI Admin/KTV cũ; màn hình khôi phục phiên cho phép thử lại hoặc đăng xuất chủ động. Dữ liệu local không bị xóa.
- Verifier Step145: 12/12 PASS. Regression Step144 14/14; Step143 11/11; Step142 12/12; Step141 15/15; Step140 13/13. Python compileall PASS.
- Không thay đổi Supabase SQL/migration/RPC/Edge/Auth/Storage hoặc dữ liệu máy chủ; không xóa dữ liệu local; không dùng Gradle.
- Chưa compile Android/Compose, chưa build/cài APK, chưa thử airplane mode/khôi phục mạng trên điện thoại, chưa test QR/2 thiết bị, chưa push GitHub. Các kiểm tra hiện tại là source/static checks.
- Review: `STEP145_REVIEW.md`.

## Step146 — Chặn Admin-global worker chạy bằng quyền cũ (03/11/2026)
- Rà soát WorkManager cho thấy SyncOutboxSender đã kiểm tra owner/session trước khi claim, trước/sau Storage và RPC; SyncPullWorker cũng kiểm tra user ID sau RPC và trước từng event. Tuy nhiên, Admin-global worker chỉ xác minh user ID, chưa xác minh lại role Admin nếu quyền bị hạ trong khi app ở background.
- Bổ sung kiểm tra profile authoritative (`profiles.is_active` và `role=admin`) cho Admin-global pull: trước khi bắt đầu feed, sau khi RPC trả về, sau khi xử lý mỗi trang và trước khi worker kết thúc. Mỗi lần kiểm tra đều xác nhận session vẫn thuộc đúng owner.
- Nếu phát hiện role không còn Admin, worker dừng, chỉ xóa movement cache của owner khác, reset riêng cursor `admin-global:<ownerId>`; không xóa lịch sử máy chủ, movement của kho cá nhân, outbox hoặc cursor cá nhân. KTV owner-feed không bị phụ thuộc vào quyền Admin.
- Lỗi mạng khi đọc profile không bị hiểu thành downgrade: worker trả retry qua catch hiện có; không xóa cache/cursor.
- Verifier Step146: 12/12 PASS. Regression Step145 12/12; Step144 14/14; Step143 11/11; Step142 12/12; Step141 15/15; Step140 13/13. Python compileall PASS.
- Không có Supabase schema/RPC/Edge/Auth/Storage/data change. Không dùng Gradle.
- Chưa compile Android/Compose, chưa chạy WorkManager runtime, chưa build/cài APK, chưa thử downgrade khi worker đang chạy trên điện thoại, chưa test QR/2 thiết bị, chưa push GitHub.
- Review: `STEP146_REVIEW.md`.

## Step147 — Khóa owner cho các chuyển trạng thái lỗi của outbox (03/11/2026)
- Tiếp tục trực tiếp từ Step146. Rà soát luồng đăng xuất/đăng nhập và worker cũ: sender đã khóa session theo owner trước khi đọc outbox, trước khi claim và quanh các điểm Storage/RPC; worker được đặt tên riêng theo owner; đăng xuất yêu cầu hủy các work của owner trước khi gọi Supabase signOut.
- Phát hiện hai DAO update cuối luồng lỗi (`markOutboxFailed`, `markOutboxBlocked`) chỉ lọc theo `operationId`, chưa thể hiện `ownerId` trong điều kiện SQL. Dù operationId là UUID, bổ sung owner fence giúp bảo vệ rõ ràng khi worker cũ của tài khoản A còn chạy trong lúc đổi sang B.
- Hai câu UPDATE giờ yêu cầu đồng thời `ownerId`, `operationId` và `status='IN_FLIGHT'`; sender truyền ownerId đã capture từ đầu. Không đổi RPC payload, operationId/idempotency, FIFO, lease, retry policy hoặc logic xác nhận thành công.
- `verify_step147_outbox_owner_fence.py`: 12/12 PASS, gồm SQLite smoke test tạo cùng operationId dưới hai owner và xác nhận worker A không đổi row B. Step146 Admin role fence 12/12 PASS. Python compileall PASS.
- Không có thay đổi Supabase, tài khoản, dữ liệu server, Storage hoặc Room schema. Không dùng Gradle.
- Chưa compile Android/Room, chưa chạy WorkManager runtime hoặc test đổi tài khoản thật, chưa build/cài APK, chưa QR/2 thiết bị, chưa push GitHub.
- Review: `STEP147_REVIEW.md`.

## Step148 — Cô lập trạng thái giao diện khi đổi tài khoản (03/11/2026)
- Tiếp tục trực tiếp từ Step147. Rà soát phát hiện một số state Compose trong màn hình đã đăng nhập chưa gắn key `profile.id`; khi chuyển KTV, trạng thái UI tạm thời có thể còn tồn tại trong composition.
- Gắn `remember(profile.id)` cho 24 state holder: dữ liệu/luồng xử lý xung đột, tìm kiếm/chọn linh kiện, dialog thêm/sửa, QR scan, chọn lịch sử máy, ảnh chờ, URI/preview/tiến trình restore và nhập mã hàng loạt.
- Khi đổi profile, UI nhận state slot mới; không xóa ảnh pending, outbox, journal restore, backup hay dữ liệu local của tài khoản cũ. Các dữ liệu đó vẫn giữ nguyên để chủ tài khoản tương ứng tiếp tục sử dụng.
- `verify_step148_account_switch_ui_state.py`: 31/31 PASS. Regression Step140 13/13; Step141 15/15; Step142 12/12; Step143 11/11; Step144 14/14; Step145 12/12; Step146 12/12; Step147 12/12. Python compileall PASS.
- Chưa chạy Compose/Room/WorkManager runtime, chưa build/cài APK, chưa test đổi tài khoản thật, chưa test QR/2 thiết bị, chưa push GitHub. Không dùng Gradle.
- Ghi nhận việc riêng cần rà soát: compatibility path ảnh pending legacy trực tiếp dưới `files/pending_part_photos/` chưa mã hóa owner; hiện vẫn được sender/backup cho phép. Không xóa hoặc thay đổi đường dẫn cũ trong Step148.
- Review: `STEP148_REVIEW.md`.

## Step149 — Chặn ảnh local legacy dùng chéo giữa các KTV (03/11/2026)
- Tiếp tục trực tiếp từ Step148. Rà soát thấy sender/backup còn chấp nhận ảnh legacy nằm thẳng trong `files/pending_part_photos/` mà chưa chứng minh chủ sở hữu; các ảnh local cũ trong `part_photos/` cũng có thể không mang owner trong đường dẫn.
- Bổ sung DAO `isLocalPhotoReferencedOnlyByOwner(ownerId, photoPath)`: chỉ cho phép khi đường dẫn tuyệt đối được tham chiếu bởi `parts` hoặc `sync_outbox` của đúng owner, đồng thời không có tham chiếu từ owner khác ở cả hai bảng.
- Sender chỉ retry file pending legacy sau khi DAO xác nhận độc quyền. Backup yêu cầu xác nhận này cho mọi ảnh local tuyệt đối trước khi đưa vào ZIP. Ảnh owner-scoped mới giữ nguyên luồng. Ảnh không rõ chủ, dùng chung, bị owner khác tham chiếu hoặc không còn tham chiếu đều bị từ chối an toàn; không xóa/move file và không sửa payload outbox bất biến.
- UI preview tiếp tục từ chối ảnh pending legacy không có owner folder; không thêm fallback có thể lộ ảnh. Cloud photo path vẫn phải có owner prefix.
- `verify_step149_legacy_photo_owner_fence.py`: 14/14 PASS, gồm SQLite smoke cho owner-only, shared path, foreign outbox, outbox-only retry và orphan. Regression Step140 13/13; Step141 15/15; Step142 12/12; Step143 11/11; Step144 14/14; Step145 12/12; Step146 12/12; Step147 12/12; Step148 31/31. Python compileall PASS.
- Không đổi Room schema, Supabase SQL/migration/RPC/Edge/Auth/Storage hoặc dữ liệu server. Không xóa/move ảnh local, không sửa outbox. Không dùng Gradle.
- Chưa compile Android/Room, chưa chạy runtime trên điện thoại, chưa build/cài APK, chưa QR/2 thiết bị, chưa push GitHub.
- Review: `STEP149_REVIEW.md`.

## Step150 — Khóa phiên khi sao lưu/khôi phục giữa lúc đổi tài khoản (03/11/2026)
- Tiếp tục từ Step149. Audit `BackupManager`, ZIP restore, photo upload coordinator, payload preparer, chunk sender và commit response khi phiên đăng nhập thay đổi trong lúc thao tác đang chạy.
- Backup kiểm tra session owner trước khi bắt đầu, sau mỗi lần tải ảnh cloud, trong lúc ghi dữ liệu/ảnh vào ZIP và trước manifest. Chỉ công bố bản MediaStore sau kiểm tra owner + verify archive; nếu lỗi sẽ xóa đúng URI pending do lần chạy này tạo. Thư mục cloud-photo cache riêng luôn được dọn trong `finally`; Android 8/9 partial file cũng được dọn khi lỗi.
- ZIP restore kiểm tra owner trước giải nén, trong lúc đọc ZIP và staging từng batch, trước khi đánh dấu `STAGED`. Không thay đổi dữ liệu kho đang hoạt động.
- Photo uploader bắt buộc có callback kiểm tra owner, factory production gắn với Supabase current user. Kiểm tra trước/sau Storage verify, trước/sau upload và trước ghi checkpoint. Payload preparer kiểm tra lại sau upload. Sender kiểm tra trước begin RPC và trước/sau từng chunk RPC; Committer không ghi nhận kết quả RPC nếu tài khoản đã đổi.
- Upload Storage đã bắt đầu có thể hoàn tất vào đường dẫn deterministic của owner cũ trước khi guard sau transfer phát hiện đổi tài khoản; đường dẫn vẫn thuộc owner cũ và không thể được map vào restore của owner mới. Không tự động xóa object, tránh xóa nhầm dữ liệu hợp lệ hoặc can thiệp dữ liệu server.
- `verify_step150_backup_restore_session_fence.py`: 21/21 PASS. Regression Step140 13/13; Step141 restore remote contract 15/15; Step141 remote feed 13/13; Step142 12/12; Step143 11/11; Step144 14/14; Step145 12/12; Step146 12/12; Step147 12/12; Step148 31/31; Step149 14/14 — PASS. Python compileall PASS.
- Đây là source/static checks và behavior model, chưa phải Android runtime hoặc Storage integration test. Không compile Android/Compose/Room, không build/cài APK, không test đổi tài khoản thật, không QR/2 thiết bị, không push GitHub. Không dùng Gradle.
- Không thay đổi Supabase SQL/migration/RPC/Edge/Auth/Storage config hoặc dữ liệu server; không xóa tài khoản, kho, lịch sử, ảnh hay restore staging. Review: `STEP150_REVIEW.md`.


## Step151 — Khôi phục ZIP sau khi ứng dụng bị đóng đột ngột (03/11/2026)
- Tiếp tục trực tiếp từ Step150. Audit luồng process death/hết pin trong lúc ZIP được đọc và durable staging được ghi.
- Phát hiện UI trước đây đưa cả `STAGING`/`FAILED` vào nút “Tiếp tục khôi phục”, nhưng coordinator không thể tiếp tục ZIP stream cũ sau khi process chết. Journal được giữ đúng cách, nhưng người dùng không có luồng chọn lại archive để dựng lại staging.
- Sửa UI: với journal local `STAGING`/`FAILED`, nút chuyển thành “Chọn lại ZIP để dựng lại staging”; yêu cầu chọn lại đúng archive. Khi xác nhận, chỉ cho phép retry journal local ở hai trạng thái này. `ZipRestore` đối chiếu deterministic `restoreId` từ owner + manifest SHA-256; archive khác bị từ chối.
- Re-import xóa đúng journal/batches abortable và thư mục staging cùng restoreId, sau đó dựng lại. Không thay đổi live inventory; apply vẫn chỉ sau `SNAPSHOT_DOWNLOADED`. Các giai đoạn cloud vẫn resume trực tiếp; remote-feed không yêu cầu ZIP.
- `verify_step151_restore_crash_recovery_ui.py`: 12/12 PASS. Regression Step140 13/13; Step141 restore remote contract 15/15; Step141 remote feed SQLite PASS; Step142 12/12; Step143 11/11; Step144 14/14; Step145 12/12; Step146 12/12; Step147 12/12; Step148 31/31; Step149 14/14; Step150 21/21 — PASS. Python compileall PASS.
- Đây là source/static checks; chưa test Android process-kill, pin depletion, SAF URI permission hoặc Room/Compose/WorkManager runtime. Chưa compile Android, chưa build/cài APK, chưa QR/2 thiết bị, chưa push GitHub. Không dùng Gradle.
- Không thay đổi Supabase SQL/migration/RPC/Edge/Auth/Storage hoặc dữ liệu máy chủ; không xóa tài khoản/kho/lịch sử/cloud objects/restore staging. Review: `STEP151_REVIEW.md`.

## Step152 — Bảo vệ khôi phục ZIP khi điện thoại thiếu dung lượng (03/11/2026)
- Tiếp tục trực tiếp từ Step151. Rà soát phát hiện kiểm tra 32 MB trống chỉ diễn ra lúc bắt đầu; dung lượng có thể cạn khi giải nén, nhân đôi ảnh vào durable staging hoặc ghi batch Room.
- Bổ sung kiểm tra dung lượng động trước mỗi buffer giải nén và mỗi batch Room, giữ 16 MB dự phòng. Trước khi copy ảnh sang durable staging, tính tổng kích thước ảnh và yêu cầu đủ dung lượng cho bản sao thứ hai cộng dự phòng; copy theo buffer có kiểm tra và xác nhận kích thước đích.
- Khi thiếu dung lượng, dừng trước lần ghi tiếp theo; live inventory vẫn chưa bị áp dụng. Cache ZIP tạm được dọn trong finally; durable staging/journal dở dang được giữ để retry theo Step151 hoặc hủy bằng lifecycle hiện có. Nếu ENOSPC cũng chặn ghi trạng thái FAILED, giữ nguyên lỗi gốc để journal STAGING/orphan được recovery xử lý.
- `verify_step152_restore_low_storage_guard.py`: 15/15 PASS, gồm boundary model. Regression Step140–152 PASS: Step140 13/13; Step141 restore contract 15/15; Step141 remote feed 13/13 + SQLite migration smoke PASS; Step142 12/12; Step143 11/11; Step144 14/14; Step145 12/12; Step146 12/12; Step147 12/12; Step148 31/31; Step149 14/14; Step150 21/21; Step151 12/12; Step152 15/15. Python compileall PASS.
- Đây là source/static checks và boundary model; chưa mô phỏng ENOSPC thật trên Android, chưa test StatFs/Room/SAF runtime hoặc điện thoại. Chưa compile Android, chưa build/cài APK, chưa QR/2 thiết bị, chưa push GitHub. Không dùng Gradle.
- Không thay đổi Room schema, Supabase SQL/migration/RPC/Edge/Auth/Storage hoặc dữ liệu server; không xóa tài khoản/kho/lịch sử/cloud objects/restore staging. Review: `STEP152_REVIEW.md`.

## Step153 — Tuần tự hóa Hủy/Import/Resume khôi phục (03/11/2026)
- Phân tích tình huống chạm nhiều lần, rời màn hình, Activity/process bị hủy và abort staging chạy cạnh import/resume.
- Phát hiện coordinator đã tuần tự hóa import/resume bằng Mutex nhưng lifecycle abort chưa dùng cùng khóa; nếu abort được gọi đồng thời, có nguy cơ xóa staging khi import/resume đang dùng.
- Tạo `RestoreOperationGate.mutex` dùng chung cho import, resume và abort. Abort giữ khóa từ lúc kiểm tra owner/trạng thái đến transaction xóa journal + staged rows và dọn đúng thư mục staging. Conditional DELETE vẫn là lớp bảo vệ thứ hai; chỉ trạng thái STAGING/STAGED/FAILED được abort, không thể abort sau khi cloud commit bắt đầu.
- Không thêm nút Hủy trong lúc restore chạy: hiện UI không cung cấp nút này; tránh gây hiểu nhầm rằng có thể hủy/rollback cloud commit. Rời tab Tools không hủy coroutine ở scope cấp màn hình chính; process death trong ZIP vẫn giữ journal/batches để chọn lại đúng ZIP.
- `verify_step153_restore_abort_serialization.py`: 14/14 PASS. Regression Step140 13/13; Step141 contract 15/15; Step141 remote feed 13/13 + SQLite smoke; Step142 12/12; Step143 11/11; Step144 14/14; Step145 12/12; Step146 12/12; Step147 12/12; Step148 31/31; Step149 14/14; Step150 21/21; Step151 12/12; Step152 15/15 — PASS. Python compileall PASS.
- Chưa chạy Android coroutine/Room runtime, chưa test tap storm/process death thật, chưa compile Android/build/cài APK, chưa test điện thoại, chưa push GitHub. Không dùng Gradle. Không thay đổi Supabase hoặc dữ liệu server.
- Review: `STEP153_REVIEW.md`.

## Step154 — Không làm lùi trạng thái commit đã xác nhận (03/11/2026)
- Dự đoán lỗi: server đã commit và Room đã lưu `COMMIT_CONFIRMED`, nhưng người dùng resume lại khiến committer gọi RPC thừa. Nếu mạng chập chờn, code cũ có thể ghi `COMMIT_UNKNOWN`, làm lùi trạng thái đã xác nhận dù dữ liệu server không bị commit lặp.
- Sửa `RestoreCloudCommitter`: `COMMIT_CONFIRMED` kiểm tra snapshot cursor dương rồi trả lại metadata đã journal; không gọi RPC và không ghi journal. `CLOUD_STAGED` mới bắt đầu commit; `COMMIT_UNKNOWN` tiếp tục replay đúng restoreId để nhận `already_committed` từ server ledger.
- Không đổi RPC/SQL/schema Room, idempotency, owner fence hoặc luồng snapshot. Không có thay đổi Supabase/server data.
- `verify_step154_confirmed_commit_short_circuit.py`: 13/13 PASS. Regression Step140–153 toàn bộ PASS: 13, 15, 13, 12, 11, 14, 12, 12, 12, 31, 14, 21, 12, 15, 14 lượt kiểm tra tương ứng; Step154 13/13. `python3 -m compileall -q tools`: PASS.
- Chưa compile Android/Room/Compose, chưa test fault injection trên Android hoặc mạng thật, chưa build/cài APK, chưa QR/2 thiết bị, chưa push GitHub. Không dùng Gradle.
- Review: `STEP154_REVIEW.md`.

## Step155 — Bảo vệ snapshot cloud khi điện thoại thiếu dung lượng (03/11/2026)
- Dự đoán lỗi: server đã commit nhưng điện thoại hết bộ nhớ khi đọc snapshot vào Room staging. Dữ liệu kho thật chưa bị apply, nhưng cần dừng trước khi ghi chunk kế tiếp.
- `RestoreCloudSnapshotReader` nhận application context, kiểm tra `StatFs(filesDir)` trước mỗi chunk; dự phòng 16 MiB và ước lượng ghi SQLite/WAL ở mức 2x payload UTF-8. Kiểm tra owner trước/sau mỗi chunk. Chunk chỉ vào `SNAPSHOT_PART`/`SNAPSHOT_MOVEMENT`; retry dùng logic hiện có để xóa riêng hai bucket snapshot dở dang và tải lại. ZIP payload staging và live inventory không bị đụng tới.
- Coordinator và SyncPullWorker truyền application context. Hai verifier Step141 cập nhật assertion constructor do reader nay cần Context.
- `verify_step155_snapshot_low_storage_guard.py`: 15/15 PASS. Regression Step140–154 toàn bộ PASS. Python compileall PASS. ZIP integrity PASS.
- Chưa fault-inject ENOSPC/SQLite WAL trên Android runtime; chưa compile Android, build/cài APK, thử điện thoại, QR/2 thiết bị, hoặc push GitHub. Không dùng Gradle. Không thay đổi Supabase/server data.
- Review: `STEP155_REVIEW.md`.

## Step156 — Tuần tự hóa toàn bộ chu trình tải và áp dụng snapshot cloud (03/11/2026)
- Dự đoán lỗi: SyncPullWorker và Coordinator có thể cùng thao tác hai bucket snapshot. Reader và Applier trước đó dùng hai mutex riêng nên không loại trừ nhau; Worker không nằm trong RestoreOperationGate.
- Thêm `RestoreSnapshotGate` dùng chung xuyên suốt download + apply. Bỏ mutex riêng trong Reader/Applier để caller quản lý một khóa duy nhất. Coordinator và cả hai nhánh RESTORE_COMMIT/active-restore của SyncPullWorker cùng dùng khóa này.
- Sau khi lấy khóa, đọc lại journal và kiểm tra owner/sourceType/manifest/cursor/counts; chỉ áp dụng khi trạng thái hiện tại là `SNAPSHOT_DOWNLOADED`. Nếu reservation không thành công, Worker dừng nhánh và retry, không tải/apply tiếp.
- `verify_step156_snapshot_pipeline_serialization.py`: 15/15 PASS. Regression Step140–156 PASS; Step141 remote-feed SQLite migration smoke PASS; Python compileall PASS.
- Đây là source/static checks, chưa stress-test hai coroutine/WorkManager thật, chưa compile Android, build/cài APK, thử điện thoại/2 thiết bị hoặc push GitHub. Không dùng Gradle.
- Không thay đổi Supabase hoặc dữ liệu server, không xóa tài khoản/kho/lịch sử/cloud objects.
- Review: `STEP156_REVIEW.md`.

## Step157 — Audit và thiết kế sao lưu Cloud tự động hàng tuần (03/11/2026)
- Theo quyết định của người dùng, chốt mô hình mỗi KTV có backup riêng; Admin là owner của kho cá nhân và có thể xem trạng thái backup toàn hệ thống qua quyền Admin, không tạo ZIP trộn dữ liệu nhiều KTV.
- Audit cho thấy BackupManager hiện đã tạo ZIP v2 có manifest/checksum/ảnh và kiểm tra archive nhưng chỉ lưu local; chưa có upload Cloud. UI hiện truyền list Compose, không thể tái sử dụng trực tiếp cho WorkManager. DAO chưa có transaction snapshot API chuyên dụng. WorkManager hiện có scheduler per-owner, có thể mở rộng.
- Chốt thiết kế: worker 7 ngày/owner, network + charging + battery-not-low; snapshot lấy từ DAO trong Room transaction; chỉ sao lưu khi outbox sạch, không có restore và owner feed đã cập nhật; private bucket riêng `inventory-backups`; metadata có checksum/cursor/counts/status; retention 4 weekly + 1 monthly + 1 pre-update; chỉ xóa cũ sau khi bản mới VERIFIED qua RPC owner-bound; Admin chỉ xem metadata; restore phải do người dùng chọn và đi qua pipeline restore hiện có.
- `part-images` không phù hợp để chứa ZIP backup; `app-updates` là public nên tuyệt đối không dùng. Không có backup bucket/metadata API riêng trong source/live audit hiện tại.
- Chưa thay đổi runtime code hoặc Supabase. Migration/bucket/RLS/RPC mới trên project thật sẽ được chuẩn bị candidate trước và xin xác nhận riêng trước khi áp dụng.
- Review/design: `STEP157_WEEKLY_CLOUD_BACKUP_DESIGN.md`.

## Step158 — Snapshot nhất quán và ZIP riêng tư cho backup tuần (03/11/2026)
- Thêm `InventoryDao.captureAutomaticBackupSnapshot(ownerId)` chạy trong Room `@Transaction`: chỉ tạo snapshot khi không có restore đang hoạt động và outbox không còn thao tác chờ; đọc parts, movements, cursor trong cùng transaction và lọc owner.
- Thêm `BackupManager.prepareWeeklyCloudBackup`: lấy dữ liệu trực tiếp từ DAO, tạo ZIP trong `cacheDir`, kiểm tra session owner, verify payload + Central Directory, tính SHA-256 và xóa file tạm nếu lỗi. Không dùng list từ Compose UI.
- Manifest ZIP v2 bổ sung `backupKind=WEEKLY_AUTO` và `snapshotCursor`; manual backup giữ mặc định `MANUAL`; inspector chấp nhận file cũ không có backupKind và yêu cầu cursor hợp lệ với weekly archive.
- Step158 verifier 21/21 PASS; regression Step140–156 PASS; Step141 remote-feed SQLite migration smoke PASS; Python compileall PASS.
- Chưa compile Room/Android, chưa chạy ZIP thật trên điện thoại, chưa tạo worker/lịch 7 ngày, chưa upload Cloud. Chưa build/cài APK, QR/2 thiết bị hoặc push GitHub. Không dùng Gradle.
- Không thay đổi Supabase hoặc dữ liệu server; chưa tạo bucket/metadata/RPC và chưa xóa bất kỳ backup nào.
- Review: `STEP158_REVIEW.md`.

## Step159 — Kiểm tra Storage và hợp đồng backup Cloud (03/11/2026)
- Kiểm tra read-only project Supabase, migration ledger, public tables, Storage buckets, `storage.objects` grants/policies và backup-related tables/functions. Hiện có `app-updates` (public, 50 MiB), `part-images` (private, 20 MiB) và bucket legacy `part-images.` (private, 10 MiB); chưa có bucket/bảng/RPC backup.
- Đọc tài liệu Supabase Kotlin/Storage hiện hành và đối chiếu dependency BOM `3.0.1`: upload file có `upsert=false`, resumable upload từ `File`, authenticated download-to-file và delete API. Chọn upload immutable theo `owner_id/backup_uuid.zip`, không đưa ZIP lớn vào RAM.
- Kiểm tra `polpermissive` trực tiếp và đính chính phân loại: `part_images_owner_scope_guard` và `part_images_no_authenticated_delete` là RESTRICTIVE; chúng không tự cấp quyền. Các PERMISSIVE policy hiện tại đều giới hạn theo bucket `app-updates` hoặc `part-images`; không thấy policy cấp quyền tổng quát cho bucket mới. New bucket mặc định vẫn deny cho tới khi thêm policy owner-scoped riêng.
- Chốt contract dự thảo: bucket private `inventory-backups`; metadata owner-bound; KTV chỉ xem backup của mình; Admin xem trạng thái metadata toàn hệ thống nhưng không xem path/download payload của KTV khác; retention 4 weekly + 1 monthly + 1 pre-update, chỉ xóa cũ sau khi bản mới VERIFIED; không tự restore; không dùng service-role key ở client.
- Làm rõ giới hạn SHA-256: client tính checksum trước upload; nếu cần xác minh byte thực tế sau upload thì download-to-private-temp và stream hash, hoặc cần verifier tin cậy phía server. RPC SQL chỉ kiểm tra object metadata/size không thể tự chứng minh checksum nội dung.
- `verify_step159_backup_storage_contract.py`: 15/15 PASS. Regression Step140–159 PASS: Step140 13/13; Step141 restore contract 15/15; Step141 remote feed 13/13 + SQLite migration smoke PASS; Step142 12/12; Step143 11/11; Step144 14/14; Step145 12/12; Step146 12/12; Step147 12/12; Step148 31/31; Step149 14/14; Step150 21/21; Step151 12/12; Step152 15/15; Step153 14/14; Step154 13/13; Step155 15/15; Step156 15/15; Step158 21/21; Step159 15/15. Python compileall PASS; Step158 ZIP integrity PASS.
- Đây là audit read-only + tài liệu/contract static; không có DDL, bucket/policy/RPC, dữ liệu, object hay deployment nào thay đổi. Chưa compile Android, chưa build/cài APK, chưa chạy WorkManager hay upload Storage thật, chưa test điện thoại, chưa push GitHub. Không dùng Gradle.
- Review: `STEP159_REVIEW.md`.

## Step161 — Adapter upload backup Cloud (03/11/2026)
- Thêm `SupabaseWeeklyBackupUploader.kt`: kiểm tra owner/session, UUID backup, file ZIP private cache, giới hạn 256 MiB và tính lại SHA-256 trước RPC. Gọi `begin_inventory_backup`, upload TUS bằng File-based resumable API, tải object về temp file private để so byte size + SHA-256, rồi mới gọi `complete_inventory_backup`.
- Đối chiếu trực tiếp source tag `supabase/supabase-kt` 3.0.1: `ResumableClient.kt`, `ResumableUtils.kt`, `Storage.kt`, `JvmUtils.kt`. Xác nhận `createOrContinueUpload(path, file, options)` và download-to-file API có trong đúng version đang pin; adapter đặt `upsert=false`, `contentType=application/zip`; không nâng version dependency.
- Uploader kiểm tra free space trước download xác minh, stream SHA-256 qua buffer 64 KiB, kiểm tra owner trước/sau ranh giới RPC/Storage, truyền CancellationException, xóa temp verification trong finally; không xóa ZIP gốc hoặc cloud object.
- Cập nhật SQL candidate Step160: thêm `upload_authorized_until`; begin retry đúng metadata refresh cửa sổ upload 24h để tương thích TUS URL expiry. Không áp dụng candidate.
- Phát hiện quan trọng trước khi nối Worker: SDK fingerprint resumable theo source file path + size. Phải giữ đúng cùng file path và bytes cho mọi retry. Step158 hiện tạo tên ZIP ngẫu nhiên trong cacheDir và cache có thể bị Android dọn; do đó chưa nối WorkManager. Cần upload journal owner-scoped + staged ZIP bền trong app-private filesDir, cleanup sau VERIFIED.
- `verify_step160_backup_backend_candidate.py`: 31/31 PASS; `verify_step161_weekly_backup_uploader.py`: 21/21 PASS; regression Step140–156, Step158–159 PASS; Python compileall PASS.
- Chưa Kotlin/Android compile, chưa APK build/cài, chưa test TUS/RLS/Storage thật, WorkManager hoặc điện thoại. Không dùng Gradle.
- Không gửi SQL lên Supabase thật; không tạo bucket/table/policy/RPC/migration/Edge Function; không thay đổi hoặc xóa user, profile, kho, ảnh, object hay dữ liệu máy chủ.
- Review: `STEP161_REVIEW.md`; contract: `docs/STEP161_WEEKLY_BACKUP_UPLOADER.md`.

## Step163 — Weekly backup crash recovery (2026-11-03)
- Added owner-scoped recovery for the Step162 durable weekly backup journal.
- Startup recovery runs after the authenticated profile loads, on IO dispatcher; recovery also runs defensively before prepare/upload.
- PREPARING crash residue is deleted locally while preserving the journal identity; READY/UPLOADING archives are preserved only if path, size and SHA-256 match; mismatches become FAILED; VERIFIED ZIP cleanup is retried idempotently; FAILED/ABANDONED records remain for audit.
- Added exact canonical path checks and owner-filtered DAO query. No schema change (Room v14), no network/Supabase access, no remote deletion.
- Verification: Step162 23/23 PASS; Step163 19/19 PASS; Python compileall PASS.
- Not done: Kotlin/Android compile, APK build/install, phone test, WorkManager scheduling, live Supabase tests, SQL application, GitHub push. Gradle not used by explicit instruction.
- Next: Step164 coordinator/Worker contract; scheduling remains disabled pending backend approval and worker safety review.

## Step164 — Weekly backup WorkManager worker contract (03/11/2026)
- Thêm `WeeklyBackupWorker.kt`: xác thực UUID owner và Supabase session trùng owner, tải lại profile active, chặn tài khoản inactive/chưa đổi mật khẩu, phục hồi journal owner-scoped trước khi chạy.
- Worker tái sử dụng backupId đang PREPARING/READY/UPLOADING; chỉ tạo UUID mới khi không có journal hoạt động. Cùng backupId đi xuyên suốt prepare và uploader; không tái tạo ZIP READY/UPLOADING. Uploader vẫn xác minh download SHA-256 trước khi gọi complete.
- Hủy coroutine được truyền tiếp; lỗi tạm thời trả WorkManager retry. Sai tài khoản/phiên không chạy chéo owner.
- Thêm scheduler API periodic 7 ngày/owner, yêu cầu mạng CONNECTED, exponential backoff, unique work + KEEP, và cancel theo đúng owner.
- Scheduler CHƯA được gọi từ login/startup/SyncWork; chưa enqueue lịch tự động. Chờ backend backup candidate được phê duyệt/áp dụng và kiểm thử staging trước khi kích hoạt.
- `verify_step164_weekly_backup_worker.py`: 22/22 PASS. Regression Step140–164: 416/416 PASS qua 25 verifier outputs; Step141 SQLite smoke PASS; Python compileall PASS.
- Không đổi Room v14, không đổi Supabase/live server, không tạo bucket/RPC/policy, không upload/xóa object, không thay đổi tài khoản/kho/lịch sử. Không push GitHub.
- Chưa compile Kotlin/Android, chưa build/cài APK, chưa test WorkManager trên thiết bị hoặc upload TUS/RLS thật. Không dùng Gradle.
- Review: `STEP164_REVIEW.md`.

## Step165 — Shared weekly-backup identity fence and logout lifecycle (03/11/2026)
- Rà soát Step163/164 phát hiện hai component gọi `isCanonicalOwnerId` / `requireActiveOwner` nhưng helper tương ứng chỉ là `private` bên trong `BackupManager`/các lớp khác. Static verifier cũ không kiểm tra phạm vi Kotlin nên đã bỏ sót lỗi compile tiềm ẩn.
- Thêm `WeeklyBackupIdentity.kt` với helper top-level `internal`: kiểm tra UUID canonical và xác minh Supabase session owner đúng tài khoản đang thao tác. Recovery và Worker dùng helper chung; helper private hiện có trong `BackupManager` được giữ nguyên, không đổi hành vi các luồng backup khác.
- Explicit logout hủy unique weekly work đúng owner trước khi sign out; nhánh sign-out ở màn hình khôi phục phiên cũng hủy nếu đã có owner profile. Không hủy backup của owner khác.
- Admin→KTV không hủy lịch backup kho cá nhân: Admin vẫn là owner KTV của kho riêng. Tài khoản inactive/password-change bị worker chặn như trước.
- Lịch tuần vẫn CHƯA tự động enqueue và chưa nối login/startup; chờ backend candidate được phê duyệt/áp dụng và staging được kiểm thử.
- `verify_step165_weekly_backup_identity_lifecycle.py`: 12/12 PASS; Step163 19/19; Step164 22/22. Regression Step140–165: 428/428 PASS qua 26 verifier outputs; Step141 SQLite smoke PASS; Python compileall PASS. Vòng chạy gộp chạm timeout khi đang chạy Step161, sau đó Step161–165 chạy riêng đều PASS.
- Không đổi Room v14; không thay đổi Supabase/live server, bucket, SQL, RPC, RLS, Storage object hoặc dữ liệu tài khoản/kho/lịch sử; không push GitHub.
- Chưa Kotlin/Android compile, chưa build/cài APK, chưa test WorkManager trên thiết bị, chưa upload/download TUS hoặc test RLS thật. Không dùng Gradle.
- Review: `STEP165_REVIEW.md`.

## Step166 — Phân loại lỗi retry và integrity cho backup tuần (03/11/2026)
- Bổ sung `WeeklyBackupFailure.kt`: `WeeklyBackupIdentityException` cho đổi tài khoản/hết phiên và `PermanentWeeklyBackupException` cho lỗi archive/metadata integrity không thể tự phục hồi.
- Worker không còn coi mọi `IllegalArgumentException` là thành công. Lỗi identity dừng lần chạy owner hiện tại; lỗi integrity terminal đánh dấu journal owner hiện tại `FAILED` và trả `Result.failure()`; lỗi mạng/backend/tạm thiếu dung lượng tiếp tục `Result.retry()`.
- Uploader phân biệt checksum/size mismatch sau khi download hoàn tất (terminal) với lỗi download/network (ném lỗi để WorkManager retry). Temporary verification file vẫn luôn được dọn; cancellation vẫn propagate.
- Giữ nguyên backupId, ZIP staging path/bytes và TUS fingerprint; không upload lại archive khác dưới cùng backupId; không thêm remote delete.
- `verify_step166_weekly_backup_retry_classification.py`: 19/19 PASS. Step161 21/21; Step162 23/23 + SQLite migration smoke; Step163 19/19; Step164 22/22; Step165 12/12. Regression Step140–166: 27 verifier scripts, 448 dòng PASS, 0 FAIL; Step141 SQLite migration smoke PASS; Python compileall PASS.
- Không đổi Room v14; không thay đổi Supabase/live server, SQL, bucket, RLS, RPC, Storage objects hoặc dữ liệu tài khoản/kho/lịch sử. Không push GitHub.
- Chưa Kotlin/Android compile, chưa build/cài APK, chưa chạy WorkManager thật, chưa test TUS/RLS hoặc staging. Lịch tự động vẫn chưa enqueue. Không dùng Gradle.
- Review: `STEP166_REVIEW.md`.

## Step167 — Weekly backup stale-journal lifecycle diagnostics (03/11/2026)
- Thêm `WeeklyBackupLifecycle.kt` để phân loại tuổi journal mà không phụ thuộc DAO, filesystem, Supabase hay WorkManager.
- PREPARING được gắn cờ stale/recoverable sau 24 giờ; READY/UPLOADING được gắn cờ stale-but-resumable sau 30 ngày. Mốc tuổi chỉ phục vụ chẩn đoán, không tự abandon, không tạo backupId thay thế và không xóa archive.
- VERIFIED chỉ thuộc nhóm local-cleanup-only; FAILED/ABANDONED được giữ làm audit record. Clock lùi được clamp tuổi về 0; trạng thái lạ fail-closed.
- Recovery report bổ sung số lượng stale PREPARING, stale upload còn resumable và terminal journal được giữ lại. Không thay đổi Room v14.
- `verify_step167_weekly_backup_lifecycle.py`: 17/17 PASS. Regression Step140–166: 448 PASS / 0 FAIL; Step167 17/17 PASS. Step141 SQLite migration smoke, Step162 journal SQLite smoke 23/23, Step163 recovery 19/19, Step164 worker 22/22, Step165 identity 12/12, Step166 retry 19/19 đều PASS. Python compileall PASS.
- Không xóa local/remote backup theo tuổi; không đổi Supabase/live server, bucket, SQL, RLS, RPC, user, profile, kho hoặc lịch sử. Chưa enqueue/kích hoạt lịch tuần; không push GitHub.
- Chưa Kotlin/Android compile, chưa build/cài APK, chưa test WorkManager/TUS/RLS trên thiết bị hoặc staging. Không dùng Gradle.
- Review: `STEP167_REVIEW.md`.

## Step168 — Weekly backup low-storage protection (03/11/2026)
- Thêm `WeeklyBackupGuardedOutputStream.kt`: kiểm tra dung lượng trống trước mỗi lần ghi ZIP, giữ lại 16 MiB dự phòng và giới hạn archive tuần tối đa 256 MiB đúng giới hạn backend.
- Luồng tạo ZIP tuần dùng guarded stream. Nếu thiết bị thiếu dung lượng hoặc archive vượt giới hạn, phát sinh `WeeklyBackupStorageException` để Worker retry; archive PREPARING dở dang được dọn theo failure path, journal identity vẫn được giữ để lần sau phục hồi an toàn.
- Preflight tải ảnh Cloud tính thêm khoảng trống dành cho archive cuối cùng. Trước khi tải ZIP Cloud về cache để xác minh, xóa temp xác minh dở dang cũ rồi mới đo dung lượng; thiếu chỗ là lỗi retryable, không đụng ZIP staging bền vững.
- Temp xác minh tiếp tục được dọn trong `finally`; không xóa Storage object, không đổi backupId/TUS fingerprint, không đánh dấu FAILED chỉ vì thiếu dung lượng tạm thời.
- `verify_step168_weekly_backup_storage_guard.py`: 18/18 PASS. Regression Step140–167: 465 PASS / 0 FAIL; Step168 18/18 PASS. Step141 + Step162 SQLite smoke PASS; Step162 23/23, Step163 19/19, Step164 22/22, Step165 12/12, Step166 19/19, Step167 17/17. Python compileall PASS.
- Không đổi Room v14; không thay đổi Supabase/live server, SQL, bucket, RLS, RPC, object, tài khoản, profile, kho hoặc lịch sử. Không kích hoạt lịch tuần, không push GitHub.
- Chưa Kotlin/Android compile, chưa build/cài APK, chưa test WorkManager/TUS/RLS trên điện thoại hoặc staging. Không dùng Gradle.
- Review: `STEP168_REVIEW.md`.

## Step169 — Weekly backup cancellation responsiveness (03/11/2026)
- Bổ sung cancellation probe vào `WeeklyBackupGuardedOutputStream`; mỗi lần ghi ZIP tuần đều gọi `ensureActive()` trước khi kiểm tra dung lượng/ghi dữ liệu, giúp tác vụ WorkManager phản hồi hủy ngay giữa các lần ghi đồng bộ.
- `BackupManager.prepareWeeklyCloudBackup` truyền coroutine context vào guard; vòng tính SHA-256 archive cũng kiểm tra cancellation theo từng buffer.
- `createZip` kiểm tra cancellation trong lúc duyệt ảnh, tuần tự hóa parts/movements, sao chép từng chunk ảnh và trước khi ghi manifest. `finally` vẫn xóa cache ảnh cloud tạm.
- Khi bị hủy trong trạng thái PREPARING, failure path xóa ZIP chưa hoàn tất nhưng giữ journal identity để recovery dựng lại an toàn. READY/UPLOADING vẫn được xử lý ngoài nhánh tạo ZIP; không ghi lại bytes/TUS fingerprint.
- Worker tiếp tục rethrow `CancellationException`. Cập nhật verifier Step150 để phản ánh thêm cancellation check nhưng vẫn bắt buộc kiểm tra owner trước khi ghi ZIP/serialize/stream ảnh.
- `verify_step169_weekly_backup_cancellation.py`: 14/14 PASS. Regression Step140–169: 30 verifier scripts, 497 checks PASS / 0 FAIL (bao gồm Step150 cập nhật 21/21). Python compileall PASS.
- Không đổi Room v14, SQL/backend, bucket, RPC, RLS, tài khoản, profile, kho, lịch sử hoặc Storage objects. Không kích hoạt lịch tuần, không push GitHub.
- Chưa Kotlin/Android compile, chưa build/cài APK, chưa test hủy WorkManager trên thiết bị, chưa test TUS/RLS/Storage hoặc staging. Không dùng Gradle.
- Review: `STEP169_REVIEW.md`.

## Step170 — Weekly backup recovery cancellation responsiveness (03/11/2026)
- `WeeklyBackupRecovery.recoverOwner` now checks coroutine cancellation and active owner before each journal row.
- READY/UPLOADING SHA-256 validation is suspendable and checks cancellation + active owner before each 64 KiB read and before digest completion. This closes a gap where a large resumable archive could keep startup/retry recovery busy after WorkManager/coroutine cancellation.
- PREPARING archive deletion, invalid-journal FAILED transition, and VERIFIED local cleanup recheck cancellation + owner immediately before the operation.
- READY/UPLOADING archive bytes remain read-only; exact canonical path and bytes are preserved for TUS resume. Suspect archive bytes remain available for diagnosis after FAILED transition.
- Updated Step163 verifier for the owner-aware suspend hash signature.
- `verify_step170_weekly_backup_recovery_cancellation.py`: 11/11 PASS; Step169 verifier 14/14 PASS; Step140–170 targeted regression: 31/31 verifier scripts PASS (Step163 19/19 included); Python compileall PASS.
- A broader all-tools verifier sweep was attempted but exceeded the execution time limit; three unrelated legacy checks (Steps105, 106, 127) also reported pre-existing source-contract mismatches. They are outside the Step140–170 targeted suite and were not changed in this step.
- No Room v14/schema, Supabase/live server, SQL, bucket, RLS, RPC, Storage object, account, profile, inventory, or history changes. No weekly schedule activation, no GitHub push.
- Not done: Kotlin/Android compile, APK build/install, on-device WorkManager cancellation, live TUS/RLS/Storage/staging tests. No Gradle used.
- Review: `STEP170_REVIEW.md`.

## Step171 — Weekly backup logout and account-lifecycle consistency (03/11/2026)
- `AuthRepository.currentUserId()` cho phép chụp owner ID trước khi kiểm tra profile có thể tự sign-out phiên inactive.
- Khi cold/session restore phát hiện profile inactive, `MainActivity` hủy unique weekly work của đúng owner đã chụp trước sign-out; không phụ thuộc `currentProfile` sau khi session đã mất.
- Nút sign-out trên màn hình khôi phục phiên (nơi `currentProfile == null`) lấy owner từ Supabase auth session hiện tại và chỉ hủy work nếu owner ID canonical.
- Worker khi gặp `InactiveProfileException` hủy periodic work của đúng owner trong input; không ảnh hưởng work của tài khoản khác.
- Giữ nguyên thứ tự explicit logout: cancel work owner trước rồi mới sign-out. Admin→KTV không hủy backup kho cá nhân. Lịch weekly vẫn opt-in, không tự enqueue từ login/startup.
- `verify_step171_weekly_backup_logout_consistency.py`: 11/11 PASS. Regression Step140–171: 32 verifier scripts PASS / 0 FAIL; Python compileall PASS.
- Không đổi Room v14/schema, SQL/backend, bucket, RLS, RPC, Storage object, tài khoản/profile/kho/lịch sử; không bật lịch tuần, không push GitHub.
- Chưa Kotlin/Android compile, chưa build/cài APK, chưa test WorkManager/logout trên điện thoại, chưa test Supabase Auth/TUS/RLS/Storage hoặc staging. Không dùng Gradle.
- Review: `STEP171_REVIEW.md`.

## Step172 — Weekly backup process-death and upload-resume safety (03/11/2026)
- Rà soát đường đi khi Android kết thúc tiến trình giữa prepare/register/upload/download-verify/complete. Journal `UPLOADING` được ghi bền vững trước remote registration; Worker tái sử dụng `backupId` đang hoạt động; recovery giữ nguyên ZIP READY/UPLOADING đúng canonical path + bytes. ZIP mất/hỏng được đánh dấu FAILED, không âm thầm dựng bytes mới dưới cùng backupId.
- Uploader bổ sung cancellation + owner fences tại các ranh giới ghi UPLOADING, registration, completion RPC và local VERIFIED commit.
- SHA-256 của ZIP staging và bản tải Cloud xác minh chuyển sang suspendable; kiểm tra cancellation và owner theo từng chunk 64 KiB và trước digest finalization. Cancellation tiếp tục propagate; `finally` chỉ xóa temp verification trong cache, không xóa ZIP staging bền vững. ZIP staging chỉ được dọn sau khi journal local đã VERIFIED.
- Thêm `verify_step172_weekly_backup_process_death.py` kiểm tra contract process-death/retry.
- Step172 verifier 14/14 PASS; Step169–171 verifiers 3 scripts PASS; Python compileall PASS.
- Giới hạn: đây là kiểm tra source contract, không phải thử nghiệm force-stop/reboot/WorkManager thực tế. Chưa Kotlin/Android compile, APK build/install, test điện thoại, TUS/Supabase/RLS/staging thật. Không kích hoạt lịch tuần, không push GitHub. Không dùng Gradle.
- Không đổi Room v14/schema, SQL/backend, bucket, RPC, RLS, Storage object hoặc dữ liệu tài khoản/profile/kho/lịch sử. Không có thao tác remote.
- Review: `STEP172_REVIEW.md`.

## Step173 — Weekly backup server-VERIFIED/local-journal reconciliation (03/11/2026)
- Rà soát tình huống completion RPC đã commit `VERIFIED` trên Cloud nhưng Android bị kết thúc trước khi `markWeeklyBackupVerified` cập nhật Room. Lần retry giữ nguyên journal `UPLOADING`, `backupId`, metadata và ZIP staging.
- Uploader gọi lại `begin_inventory_backup`; backend candidate trả `VERIFIED` chỉ sau khi owner/backupId và toàn bộ metadata khớp. Client vẫn tải object bằng authenticated Storage download và kiểm tra size + SHA-256 trước khi chấp nhận trạng thái server.
- Khi registration đã `VERIFIED` và object vừa được xác minh lại, client bỏ qua completion RPC lần hai, ghi trạng thái `VERIFIED` vào journal cục bộ theo owner + backupId, rồi mới dọn ZIP staging. Với registration `PENDING`, luồng completion RPC hiện tại vẫn giữ nguyên.
- Bổ sung cancellation/owner fence trước local VERIFIED commit; temp download tiếp tục được dọn trong `finally`. Không thay bytes/path của ZIP và không đổi TUS fingerprint.
- Cập nhật Step172 verifier để kiểm tra cả nhánh completion RPC có điều kiện; thêm `verify_step173_weekly_backup_verified_reconciliation.py`.
- Step173 verifier 12/12 PASS. Regression verifiers Step158–173: 16 scripts, 280/280 checks PASS. Python compileall PASS; ZIP integrity/SHA-256 kiểm tra sau đóng gói.
- Đây là source-contract verification; không gọi Supabase thật, không chạy RPC, không tải object thật. Chưa Kotlin/Android compile, APK build/install, force-stop/reboot/WorkManager test trên thiết bị hoặc TUS/RLS/staging thật. Lịch tuần vẫn chưa enqueue/kích hoạt; không push GitHub. Không dùng Gradle.
- Không đổi Room v14/schema, SQL/backend candidate, bucket, RLS, RPC, Storage objects hoặc dữ liệu tài khoản/profile/kho/lịch sử. Không có thao tác remote.
- Review: `STEP173_REVIEW.md`.

## Step174 — Weekly backup account-switch isolation (03/11/2026)
- Rà soát khe thời gian khi Worker nhận `PermanentWeeklyBackupException` cho owner A nhưng phiên Supabase có thể đã chuyển sang owner B trước khi catch handler cập nhật journal.
- Siết catch handler: chỉ mở DAO, đọc journal đang hoạt động và đánh dấu FAILED khi authenticated user vẫn khớp `ownerId` của Worker; kiểm tra lại ngay trước thao tác ghi. Nếu phiên đã đổi, giữ nguyên journal của owner cũ để chính owner đó đối soát ở lần đăng nhập sau.
- Xác minh lại các hàng rào hiện có: Worker chỉ chạy theo owner trong input; uploader kiểm tra owner trước commit VERIFIED và lần nữa trước xóa ZIP staging; recovery chỉ đọc journal của owner được truyền vào, xác thực row owner và giới hạn đường dẫn trong thư mục staging riêng của owner. Không quét/xóa thư mục owner khác.
- Thêm `verify_step174_weekly_backup_account_switch_isolation.py`. Step174 verifier 15/15 PASS; Step172 14/14 và Step173 12/12 PASS.
- Chỉ kiểm tra source tĩnh; chưa có test đăng nhập/chuyển tài khoản đồng thời trên Android/WorkManager, chưa Kotlin compile/APK/device test, chưa Supabase thật, chưa push GitHub. Room vẫn v14; không thay đổi SQL, archive/TUS fingerprint, dữ liệu hay lịch chạy. Không dùng Gradle theo yêu cầu.

## Step175 — Weekly backup same-owner re-login recovery (03/11/2026)
- Kiểm tra đầy đủ state matrix khi owner cũ đăng xuất rồi đăng nhập lại: `PREPARING` dọn ZIP dở dang nhưng giữ journal để dựng lại cùng backupId; `READY`/`UPLOADING` xác minh size + SHA-256 và giữ nguyên bytes/path để resume TUS; `VERIFIED` chỉ dọn archive local; `FAILED`/`ABANDONED` giữ audit record; state lạ fail-closed.
- Xác nhận Worker gọi recovery theo owner đã xác thực trước khi chọn active journal. DAO chỉ chọn `PREPARING`/`READY`/`UPLOADING` của đúng owner; trạng thái terminal/VERIFIED không chiếm active slot. Journal không hợp lệ được chuyển FAILED theo owner+backupId, không âm thầm tạo bytes mới dưới backupId cũ.
- Thêm `verify_step175_weekly_backup_relogin_recovery.py`: 18/18 PASS, gồm SQLite in-memory state matrix cho 6 trạng thái và hai owner để kiểm tra không lẫn journal giữa tài khoản.
- Đây là audit source + SQLite predicate smoke; không phải Room/WorkManager hoặc đăng xuất/đăng nhập lại thực tế trên Android. Không thay đổi Kotlin nghiệp vụ, Room schema, backend candidate, Supabase, Storage object hoặc dữ liệu. Không kích hoạt lịch tuần, không push GitHub. Chưa Kotlin compile/APK/device test, không dùng Gradle.
- Review: `STEP175_REVIEW.md`.

## Step176 — Weekly backup duplicate active-journal fail-closed handling (03/11/2026)
- Audit tình huống một owner có nhiều journal cùng ở trạng thái `PREPARING`/`READY`/`UPLOADING` do dữ liệu legacy, race hoặc sửa DB thủ công. Trước đây recovery có thể xử lý từng row rồi Worker chọn row mới nhất, khiến một upload hợp lệ khác bị bỏ lại mà không có quyết định rõ ràng.
- `WeeklyBackupRecovery.recoverOwner` nay preflight toàn bộ journal của owner trước mọi thao tác filesystem/hash/DB. Nếu có hơn một active row, recovery fail-closed và retry, giữ nguyên tất cả row/archive; không tự chọn bản mới nhất, không đánh dấu FAILED, không xóa file và không sinh backupId thay thế.
- DAO active query thêm tie-breaker `backupId ASC` khi `createdAt` bằng nhau để kết quả deterministic; conflict vẫn bị chặn trước khi Worker dùng lựa chọn đó. `beginWeeklyBackup` vẫn transactionally chặn tạo row mới khi đã có active row.
- `verify_step176_weekly_backup_multiple_active_journals.py`: 14/14 PASS, gồm SQLite model xác nhận conflict không thay đổi row/archive và không ảnh hưởng owner khác. Regression Step158–176: 19 verifier scripts, 327/327 checks PASS. Python compileall PASS.
- Đây là source-contract + SQLite model; chưa có Room/WorkManager concurrency test thật, chưa Kotlin compile/APK/device test, chưa Supabase/TUS/RLS/staging test. Không đổi Room schema, backend/SQL, bucket, RPC, Storage object hoặc dữ liệu. Không kích hoạt lịch tuần, không push GitHub. Không dùng Gradle.
- Review: `STEP176_REVIEW.md`.

## Step177 — Owner-scoped weekly-backup single-flight (03/11/2026)
- Bổ sung `WeeklyBackupExecutionLock` dùng coroutine `Mutex` theo từng owner để hai Worker cùng owner trong cùng app process không đồng thời đi vào recovery/prepare/upload; owner khác dùng lock riêng nên không bị chặn toàn cục.
- Worker lấy lock trước profile/recovery và kiểm tra lại Supabase authenticated owner sau khi chờ lock. Nếu tài khoản đã đổi trong lúc chờ, tác vụ dừng an toàn, không đụng journal của owner cũ. Cancellation vẫn được propagate.
- Giữ unique periodic work `KEEP` theo owner và transaction `beginWeeklyBackup` làm hàng rào bền vững cho active journal. Lock này là in-process, không tuyên bố đồng bộ cross-process; WorkManager hiện dùng cấu hình process mặc định.
- `verify_step177_weekly_backup_single_flight.py`: 13/13 PASS, gồm mô hình đa luồng kiểm tra cùng owner tuần tự và owner khác có thể chạy độc lập.
- Chưa test WorkManager concurrency thật, chưa Kotlin compile/APK/device test, chưa Supabase/TUS/RLS/staging thật. Không thay đổi Room schema/backend/server/data, không kích hoạt lịch, không push GitHub. Không dùng Gradle.
- Review: `STEP177_REVIEW.md`.

## Step178 — Weekly backup process-restart and lock recovery audit (03/11/2026)
- Rà soát khóa single-flight theo owner ở ranh giới Android process death và coroutine cancellation. `WeeklyBackupExecutionLock` chỉ nằm trong `ConcurrentHashMap` bộ nhớ tiến trình; khi process bị Android kết thúc, registry/mutex biến mất cùng process. Process mới khởi tạo registry rỗng nên không có persistent stale lock để dọn.
- Critical section dùng `Mutex.withLock` có cấu trúc; khi block kết thúc hoặc coroutine bị hủy, khóa được nhả theo contract của `withLock`. Worker vẫn rethrow `CancellationException`, không biến cancellation thành retry thông thường.
- Sau khi lấy lock, Worker kiểm tra lại authenticated owner, chạy recovery journal theo owner rồi mới đọc/tạo active journal. WorkManager unique periodic `KEEP` và Room transaction active-journal guard vẫn là các lớp bảo vệ bền vững; không thêm cross-process lock.
- Thêm `tools/verify_step178_weekly_backup_process_restart_lock.py`: 15/15 PASS, gồm source-contract checks và mô hình Python cho registry mới sau process death, worker đang chờ tiếp tục sau khi holder nhả khóa.
- Regression Step158–178: 21 verifier scripts, 355/355 checks PASS; Python compileall PASS.
- Giới hạn: chưa test Android force-stop/process kill/restart, coroutine Mutex hoặc WorkManager runtime thực tế; chưa Kotlin compile/APK/device test, chưa Supabase/TUS/RLS/staging thật. Không đổi Room schema/backend/server/data, không kích hoạt lịch, không push GitHub. Không dùng Gradle.
- Review: `STEP178_REVIEW.md`.

## Step179 — Weekly backup trigger deduplication audit (03/11/2026)
- Rà soát các nguồn kích hoạt định kỳ, retry và khả năng có thao tác thủ công cùng owner. Ở source production hiện tại, chỉ có một call site gọi `BackupManager.prepareWeeklyCloudBackup`: `WeeklyBackupWorker`; chưa tồn tại luồng sao lưu tuần thủ công riêng.
- Scheduler dùng unique name `inventory-weekly-backup-$ownerId` và `ExistingPeriodicWorkPolicy.KEEP`; gọi schedule lặp lại không thay thế/tạo chuỗi periodic thứ hai cho cùng owner. Worker tiếp tục được serialize bởi owner-scoped Mutex trong process và kiểm tra lại authenticated owner sau khi chờ.
- Nếu sau này thêm one-time/manual WorkRequest, hàng rào Room vẫn là lớp bền vững: `beginWeeklyBackup` chạy transaction, trả journal cũ nếu cùng backupId, từ chối tạo journal mới khi owner đang có PREPARING/READY/UPLOADING. Luồng mới bắt buộc đi qua cùng Worker/lock/journal path, không gọi trực tiếp prepare/uploader.
- `verify_step179_weekly_backup_trigger_deduplication.py`: 12/12 PASS. Regression đầy đủ Step158–179: 22 verifier scripts, 367/367 checks PASS. Python compileall PASS; ZIP integrity PASS.
- Chỉ audit source tĩnh; không test WorkManager runtime, không Kotlin compile/APK/device, không Supabase/TUS/RLS/staging thật. Không đổi Room schema/backend/server/data; không kích hoạt lịch tuần, không push GitHub. Không dùng Gradle.
- Review: `STEP179_REVIEW.md`.

## Step180 — Weekly backup new-trigger vs resume identity policy (03/11/2026)
- Tách quyết định identity khỏi biểu thức inline của Worker thành `WeeklyBackupLifecycle.backupIdForAttempt`: không có active journal thì dùng UUID mới canonical; nếu có `PREPARING`/`READY`/`UPLOADING` thì bắt buộc giữ backupId canonical cũ. State terminal/không hỗ trợ fail-closed, không âm thầm tạo bản mới.
- Worker truyền nguyên backupId đã phân giải qua `prepareWeeklyCloudBackup` và uploader. `PREPARING` có thể dựng lại cùng identity; `READY`/`UPLOADING` tiếp tục xác minh và dùng nguyên archive path/bytes/size/SHA-256/TUS identity. Room transaction vẫn chặn active journal thứ hai.
- Cập nhật verifier Steps164,166,172,179 theo policy helper; thêm `verify_step180_weekly_backup_new_vs_resume.py` 14/14 PASS.
- Regression Step158–180: 23 verifier scripts, 381/381 checks PASS; Python compileall PASS; ZIP integrity PASS.
- Chỉ kiểm tra source-contract. Chưa Kotlin/Android compile, APK/device/WorkManager runtime, TUS/Supabase/RLS/staging thật. Không đổi Room v14/schema, SQL/backend/server/data/Storage, không kích hoạt lịch, không push GitHub. Không dùng Gradle.
- Review: `STEP180_REVIEW.md`.

## Step181 — Weekly backup snapshot consistency audit (03/11/2026)
- Audit xác nhận `captureAutomaticBackupSnapshot(ownerId)` là Room `@Transaction`; kiểm tra active restore và outbox unresolved (`PENDING`, `FAILED`, `IN_FLIGHT`, `BLOCKED`) trước khi đọc, lấy parts + movements + sync cursor trong cùng transaction, rồi kiểm tra lại hai điều kiện trước khi trả snapshot.
- ZIP chỉ dùng các giá trị đã chụp trong `InventoryBackupSnapshot`; manifest cursor và số lượng parts/movements lấy từ cùng snapshot, không query lại bảng đang thay đổi giữa lúc ghi ZIP.
- Các đường local stock mutation, pulled part và pulled movement được transaction hóa; pull/restore có active-restore fence. Snapshot bị từ chối nếu còn outbox chưa đồng bộ hoặc restore active; không tạo archive bytes khi capture trả null.
- Thêm SQLite WAL two-connection smoke test: reader giữ nguyên snapshot trong khi writer commit; transaction mới sau đó nhìn thấy dữ liệu mới.
- `verify_step181_weekly_backup_snapshot_consistency.py`: 17/17 PASS. Regression Steps158–181: 24 verifier scripts, 398/398 checks PASS. Python compileall PASS; ZIP integrity/SHA-256 kiểm tra sau đóng gói.
- Chỉ audit source + Python SQLite smoke; chưa Room instrumentation/Android concurrency test, chưa Kotlin compile/APK/device/WorkManager runtime, chưa Supabase/TUS/RLS/staging thật. Không đổi Room v14/schema, SQL/backend/server/data/Storage, không kích hoạt lịch, không push GitHub. Không dùng Gradle.
- Review: `STEP181_REVIEW.md`.


## Step182 — Weekly backup photo consistency and cloud-object safety (03/11/2026)
- Audit luồng ảnh trong weekly ZIP: snapshot Step181 chặn outbox chưa đồng bộ; cloud path phải đúng owner trước authenticated download; xác thực lại owner sau transfer; thiếu/rỗng/quá 20 MB hoặc tổng cache vượt giới hạn thì fail-closed trước khi mở/truncate ZIP đích.
- Xác nhận đường dẫn ảnh cloud do SyncOutboxSender tạo theo owner/part syncId/operationId; sender đọc lại object và so khớp bytes/SHA-256 trước khi công bố `photo_path`. Local/legacy photo chỉ được đọc từ app-private storage và cần chứng minh chỉ owner hiện tại tham chiếu.
- Cloud photo chỉ tải vào cache tạm; thư mục được dọn trong `finally`. ZIP chỉ mở sau khi tất cả ảnh được resolve/validate; từng ảnh được SHA-256 trong lúc ghi và checksum lưu vào manifest để restore kiểm tra. Không sửa nghiệp vụ Kotlin vì các hàng rào hiện tại đã đáp ứng yêu cầu fail-closed và owner isolation.
- `verify_step182_weekly_backup_photo_consistency.py`: 15/15 PASS. Regression Steps158–182: 25 verifier scripts, 413/413 checks PASS. Python compileall PASS.
- Giới hạn: source-contract audit; chưa thử race xóa/thay object trên Supabase, chưa Room/Android test, chưa Kotlin compile/APK/device/WorkManager runtime, chưa TUS/RLS/staging thật. Không đổi Room schema, SQL/backend/server/data/Storage, không kích hoạt lịch, không push GitHub. Không dùng Gradle.
- Review: `STEP182_REVIEW.md`.

## Step183 — Weekly backup photo-after-archive immutability (03/11/2026)
- Xử lý khe thời gian khi ảnh nguồn Cloud/local thay đổi hoặc bị xóa sau khi ZIP đã tạo nhưng trước khi TUS upload. Sau khi ZIP hoàn tất, uploader chỉ dùng archive đã stage; không đọc lại `photo_path`, bucket `part-images` hay nguồn ảnh ban đầu. Bytes ảnh đã nhúng và SHA-256 từng entry trong manifest là dữ liệu bất biến của archive.
- `beginRegistration` nhận `archiveSizeBytes` từ journal bền vững thay vì đọc lại `File.length()` để khai báo metadata. Với trạng thái PENDING, ngay sau RPC registration và sát trước `createOrContinueUpload`, uploader kiểm tra lại ZIP canonical theo đúng kích thước journal và SHA-256 đã chuẩn bị; cancellation + owner được kiểm tra trước khi giao file cho TUS.
- Nếu ZIP bị đổi cùng kích thước hoặc khác kích thước, fail-closed bằng `PermanentWeeklyBackupException`; không dựng lại ảnh/ZIP, không thay bytes dưới cùng backupId/TUS fingerprint. TUS giữ `upsert=false`; readback Cloud tiếp tục xác minh exact size + SHA-256. Recovery READY/UPLOADING tiếp tục phát hiện archive khác journal và giữ bytes nghi vấn để chẩn đoán.
- `verify_step183_weekly_backup_photo_after_archive_immutability.py`: 15/15 PASS. Regression Steps158–183: 26 verifier scripts, toàn bộ checks PASS. Python compileall PASS; ZIP integrity/SHA-256 kiểm tra sau đóng gói.
- Chỉ source-contract verification; chưa test race thay/xóa ảnh thật, chưa upload TUS/Supabase thật. Chưa Kotlin compile/APK/device/Room/WorkManager runtime. Không đổi Room schema, SQL/backend/server/data/Storage, không kích hoạt lịch, không push GitHub. Không dùng Gradle.
- Review: `STEP183_REVIEW.md`.

## Step184 — Weekly backup storage-pressure and staging-access retry safety (03/11/2026)
- Xử lý tình huống máy gần hết dung lượng hoặc Android tạm thời không cho đọc ZIP staging/cache trong lúc recovery, TUS upload hay download xác minh. `WeeklyBackupRecovery` phân biệt file không truy cập được / `IOException` / `SecurityException` với trường hợp đã đọc được nhưng size hoặc SHA-256 thực sự sai.
- Với lỗi truy cập/I/O tạm thời, recovery ném `WeeklyBackupStorageException` trước khi cập nhật journal hoặc xóa ZIP. Worker bắt riêng exception này và trả `Result.retry()`; trạng thái READY/UPLOADING, `backupId`, archive path và bytes được giữ nguyên để tiếp tục TUS.
- Uploader phân loại ZIP không đọc được là lỗi storage retryable; ZIP đọc được nhưng size/fingerprint thay đổi vẫn là lỗi integrity terminal. SHA-256 read I/O được chuyển thành storage exception; cancellation tiếp tục propagate. Guard dung lượng cache trước download verify được giữ nguyên, temp verification file được dọn trong `finally`, ZIP staging bền vững không bị xóa.
- Chỉ đánh dấu FAILED khi archive đọc/đo được và xác nhận size hoặc SHA-256 khác journal; giữ bytes nghi vấn để chẩn đoán. File vĩnh viễn mất/không đọc được được giữ journal READY/UPLOADING để retry/chẩn đoán thủ công thay vì phỏng đoán và tự động hủy khả năng phục hồi.
- Thêm `tools/verify_step184_weekly_backup_storage_pressure_retry.py`: 16/16 PASS. Cập nhật các verifier Step163, Step170, Step172, Step175, Step183 theo phân loại mới. Regression Step158–184: 27 scripts, 444/444 checks PASS. Python compileall PASS.
- Giới hạn: source-contract verification; chưa mô phỏng low-storage, revoke permission, filesystem fault injection, WorkManager retry, TUS upload hay Supabase staging thực tế. Chưa Kotlin compile/APK/device test. Không đổi Room schema, SQL/backend/server/data/Storage; không kích hoạt lịch tuần, không push GitHub. Không dùng Gradle.
- Review: `STEP184_REVIEW.md`.

\n## Step185 — Uninstall/reinstall and remote-backup recovery safety (03/11/2026)
- Audit chính xác tình huống gỡ app/xóa dữ liệu/cài lại: Room journal, ZIP staging app-private và WorkManager state không còn; vì vậy TUS partial không thể resume an toàn nếu mất local archive/fingerprint. Không tự đoán backupId, không tạo bytes mới dưới identity cũ.
- Nếu không còn journal, Worker tạo UUID mới cho một backup mới; đây không phải phục hồi backup Cloud cũ. Object PENDING cũ có thể thành orphan nhưng không bị ghi đè (path theo backupId, TUS upsert=false).
- Remote object/metadata không đủ chứng minh thành công. Luồng hiện tại cần authenticated readback, exact size + SHA-256, completion/remote VERIFIED và commit Room VERIFIED.
- SQL candidate đã có `list_my_inventory_backups()` owner-scoped; Step186 thêm adapter đọc-only nhưng chưa nối UI/Worker và chưa chứng minh RPC đã deploy. Android hiện chưa có remote catalog/restore picker để tìm backup sau reinstall. Scheduler tuần cũng chưa được kích hoạt (không có call site schedule).
- Thêm `STEP185_REVIEW.md` và `tools/verify_step185_reinstall_recovery_safety.py` (15/15 PASS). Regression Steps158–185: 28 verifier scripts, 459/459 checks PASS; Python compileall và ZIP integrity PASS. Đây là audit + source-contract checks, không thay đổi production Kotlin/Room/SQL/backend/Cloud/data; không gọi Supabase, không deploy, không bật lịch, không push GitHub. Không dùng Gradle.
- Giới hạn: chưa thử uninstall/reinstall thật, chưa test TUS/WorkManager/restore trên thiết bị. Reinstall recovery từ backup VERIFIED Cloud chưa thể coi là đã có; cần owner-scoped remote catalog + authenticated download/verify + restore transaction ở bước kế tiếp.

## Step186 — Owner-scoped Cloud backup catalog contract (03/11/2026)
- Thêm `WeeklyBackupCloudCatalog.kt` dạng read-only để chuẩn bị truy vấn danh sách backup của đúng tài khoản sau reinstall. Xác thực UUID owner và Auth UID trước/sau RPC `list_my_inventory_backups()`; account switch trong lúc request làm hủy kết quả.
- Parse response all-or-nothing; từ chối duplicate backupId, UUID/path không khớp owner, kind/status lạ, size ngoài 1..256 MiB, SHA-256 sai định dạng, cursor/count sai giới hạn hoặc timestamp VERIFIED/DELETED thiếu.
- Chỉ đánh dấu ứng viên khôi phục nếu `VERIFIED`, có `verified_at`, chưa `DELETED`. PENDING/FAILED/DELETED vẫn được phân biệt rõ; không coi lỗi RPC là catalog rỗng.
- Chỉ thêm adapter, chưa gọi từ UI/Worker; RPC chỉ có trong SQL candidate, chưa xác nhận đã deploy. Không tải object, không restore, không sửa Room/Cloud/SQL và không gọi Supabase trong lượt kiểm tra.
- `verify_step186_cloud_catalog_contract.py`: 22/22 PASS. Regression Steps158–186: 29 verifier scripts, 481/481 checks PASS. Python compileall + ZIP integrity.
- Giới hạn: source-contract/static checks; chưa Kotlin compile/APK/device/RPC/RLS/Storage/checksum/restore runtime. Chưa có tính năng reinstall recovery hoàn chỉnh. Không bật scheduler, không push GitHub. Không dùng Gradle.
- Review: `STEP186_REVIEW.md`.


## Step187 — Deploy weekly Cloud backup backend to Supabase (03/11/2026)
- Connected to the active project `kiem-soat-linh-kien` (`ihhxxxqojelbxisreqns`); confirmed the project ref matches `supabase/REMOTE_MIGRATION_MANIFEST.md`.
- Deployed migration `20261103150733_step187_weekly_backup_backend`: private bucket `inventory-backups` (256 MiB, ZIP MIME only), `inventory_backup_objects` metadata table with RLS enabled and no client table grants, owner-scoped Storage INSERT/SELECT policies, and authenticated owner/admin RPCs. No existing bucket, user, profile, inventory, movement, restore data, or Storage object was modified.
- Authenticated-role testing exposed a real SQL runtime bug: `ON CONFLICT (backup_id)` was ambiguous with the PL/pgSQL OUT parameter. Fixed with migration `20261103150823_step187_01_fix_backup_registration_ambiguity` and `20261103151110_step187_02_deny_direct_backup_metadata_access`.
- Live tests after fix: Admin registration returned expected owner path + PENDING; Admin catalog returned its own row; another KTV catalog returned zero rows; KTV admin-status RPC correctly raised `ADMIN_REQUIRED`. Temporary test metadata rows were deleted; final backup metadata row count is zero.
- Verified live bucket settings, metadata table existence, RPC ACLs (`anon_exec=false`, `authenticated_exec=true`), Storage policies, and remote migration history. Security advisor still reports existing SECURITY DEFINER warnings for previously deployed restore/sync RPCs; the new owner/admin RPC warnings are expected by design and are guarded by `auth.uid()`, active-profile checks and admin checks. Existing unrelated warnings were not changed.
- Remaining runtime gates: no real TUS upload/download/Storage RLS request from Android session yet; no actual SHA-256 readback or `complete_inventory_backup` transition; catalog not yet wired to UI; no APK build/device test. Do not claim end-to-end backup/restore is ready. No Gradle.
- Review: `STEP187_REVIEW.md`.

## Step188 — Owner-scoped Cloud backup catalog UI (03/11/2026)
- Correction recorded in Step189: source base was the exact Step186 ZIP (SHA-256 `219750ca7ef7f474847197c6951cc6e8f523142a66ed074812cc7701a747c157`). Step187 was a live Supabase deployment, not a verified source ZIP. The previously recorded Step187 source archive name/hash were unverified and are withdrawn. Step188 implementation changes remain as delivered.
- Nối `WeeklyBackupCloudCatalog` vào màn Công cụ bằng nút tải danh sách chủ động; RPC chạy trên `Dispatchers.IO`, không tự gọi khi mở app/đăng nhập và không nối vào Worker.
- State danh sách/loading/error được khóa theo `ownerId`; adapter kiểm tra Auth UID trước/sau RPC. UI phân biệt lỗi RPC/mạng/xác thực với danh sách rỗng, không biến lỗi thành “chưa có backup”.
- Hiển thị riêng PENDING/VERIFIED/FAILED/DELETED, số linh kiện/giao dịch, dung lượng, thời điểm tạo. Chỉ VERIFIED + verified_at + chưa deleted mới được đánh dấu là ứng viên khôi phục; chưa tải hoặc khôi phục trong Step188. UI hiển thị tối đa 30 bản mới nhất.
- Step188 verifier 13/13 PASS. Regression Steps158–188: 31 verifier scripts, 518/518 checks PASS. Python compileall PASS.
- Giới hạn: chưa Kotlin compile/APK/phone test; chưa gọi RPC từ Android session; chưa Storage upload/download/TUS/readback/SHA-256/complete/restore E2E. Không chạy Gradle.
- Không thay đổi SQL/Supabase policy, dữ liệu nghiệp vụ, metadata backup hay Storage object. Không kích hoạt lịch tuần, không push GitHub.
- Review: `STEP188_REVIEW.md`.

## Step189 — Authenticated Cloud backup download and integrity verification (03/11/2026)
- Thêm `WeeklyBackupCloudDownloader`: chỉ tải entry `VERIFIED` có `verified_at`, chưa `deleted_at`, path phải chính xác `${ownerId}/${backupId}.zip`; gọi Storage `downloadAuthenticatedTo` trên bucket private `inventory-backups`.
- Trước tải kiểm tra dung lượng cache (archive + 16 MiB). Tải vào file `.partial` trong cache riêng tư; kiểm tra Auth UID trước/sau tải, khi băm SHA-256 và trước khi công bố file. Partial luôn được dọn khi lỗi/hủy.
- So sánh chính xác kích thước và SHA-256 với catalog; chạy `BackupArchiveInspector.verify` để kiểm tra cấu trúc ZIP, owner, entries, checksums nội bộ và record counts; so sánh tiếp manifest owner, partCount, movementCount, backupKind, snapshotCursor với metadata Cloud. Chỉ sau các kiểm tra mới rename thành file cache đã xác minh.
- Màn Công cụ có nút tải/xác minh riêng trên từng backup VERIFIED; chạy trên `Dispatchers.IO`. Hiển thị trạng thái đã xác minh nhưng chưa khôi phục. Không gọi RestoreCloudCoordinator, không sửa Room, không commit restore, không xóa/ghi Cloud.
- Cập nhật verifier Step188 để tiếp tục kiểm tra catalog/eligibility độc lập với luồng tải mới; Step188 13/13 PASS, Step189 18/18 PASS. Regression Steps158–189: 32 verifier scripts, 536/536 checks PASS. Python compileall PASS.
- Sửa lại provenance lịch sử Step188: source Step188 thực tế dựa trên Step186 ZIP SHA-256 `219750ca7ef7f474847197c6951cc6e8f523142a66ed074812cc7701a747c157`; Step187 là migration deploy trực tiếp Supabase, không phải source ZIP. Tên/hash Step187 source ZIP từng ghi trước đó không được xác minh và đã rút lại.
- Giới hạn: chưa Kotlin compile/APK/device test; chưa thực hiện Storage download thực từ Android session nên chưa chứng minh policy SELECT/transfer/SHA thực tế. File đã xác minh chỉ nằm trong cache (có thể bị Android dọn); chưa có luồng khôi phục từ file này. Không dùng Gradle.
- Không thay đổi Supabase/SQL, user data, backup metadata, Storage object, inventory, movement, profile hay restore session. Không kích hoạt scheduler, không push GitHub.
- Review: `STEP189_REVIEW.md`.

## Step190 — Durable owner-scoped Cloud restore inbox (03/11/2026)
- Thêm `WeeklyBackupRestoreInbox`: sau khi tải và xác minh Cloud backup ở Step189, ứng dụng sao chép vào `filesDir/cloud_restore_inbox/<ownerId>/` để không phụ thuộc cache có thể bị Android dọn.
- Chỉ nhận catalog entry VERIFIED hợp lệ và backupId khớp tệp đã xác minh; kiểm tra lại Auth UID, kích thước, SHA-256 so với metadata Cloud và digest từ bước tải, đồng thời chạy lại `BackupArchiveInspector.verify`.
- Ghi tệp `.partial`, `fd.sync()`, kiểm tra lại rồi rename sang UUID ZIP cuối; không ghi đè tệp cùng ID nếu bytes khác. Lưu sidecar metadata owner/backupId/path/size/SHA/kind/cursor/counts/thời điểm; không lưu token/credential.
- UI Công cụ chuyển file đã xác minh từ cache vào inbox bền vững và thông báo rõ “đã lưu, chưa khôi phục”. Không tạo RestoreJournal, không gọi ZipRestore/RestoreCloudCoordinator, không thay Room hay dữ liệu Cloud.
- `verify_step190_durable_cloud_restore_inbox.py`: 15/15 PASS. Regression Step150–Step190: 40 scripts, 656 checks PASS, 0 script lỗi; Python compileall PASS.
- Một lượt quét toàn bộ verifier lịch sử đã timeout và cho thấy một số lỗi đã tồn tại ở verifier cũ (Step105/106/127/138); không ghi nhận toàn bộ 117 scripts là PASS và không quy các lỗi cũ này cho Step190.
- Giới hạn: chưa Kotlin compile/APK/device test; chưa chạy Storage download/promotion thực trên Android; chưa có UI tự tìm lại inbox sau process death và chưa nối restore confirmation. Không chạy Gradle, không thay đổi Supabase hay dữ liệu nghiệp vụ.
- Review: `STEP190_REVIEW.md`.

## Step191 — Recoverable Cloud inbox and explicit restore confirmation (03/11/2026)
- Thêm `WeeklyBackupRestoreInbox.listVerified(ownerId)` để tự tìm lại backup đã lưu sau khi đóng/mở app; kiểm tra Auth UID trước/sau, owner folder, UUID, metadata owner/path/kind, kích thước, SHA-256 và ZIP inspector. Không hiển thị partial/orphan ZIP.
- UI Công cụ có thao tác chủ động quét inbox theo tài khoản hiện tại; state được key theo owner, không kế thừa danh sách giữa các lần đổi tài khoản.
- Bổ sung hộp thoại xác nhận có cảnh báo rõ khôi phục sẽ thay thế kho và lịch sử hiện tại. Hủy không làm thay đổi dữ liệu.
- Bổ sung `ZipRestore` overload nhận File nhưng chỉ chấp nhận ZIP nằm trực tiếp trong inbox riêng tư của owner; giữ nguyên đường nhập ZIP qua Uri.
- Bổ sung `RestoreCloudCoordinator.importAndRestore(File, ownerId)`: xác minh lại Auth, không có outbox chưa gửi, đường dẫn inbox và sự hiện diện trong danh sách inbox đã xác minh; sau đó tái sử dụng coordinator hiện có với remote commit + snapshot verification trước khi áp dụng Room.
- `verify_step191_cloud_inbox_restore_confirmation.py`: 16/16 PASS; Step190 verifier: 15/15 PASS. Regression backup/restore Step158–Step191: 34/34 scripts, 567 checks PASS. Python compileall PASS.
- Sweep rộng Step150–Step191 vượt timeout, không tính là PASS.
- Chưa Kotlin compile/build APK/kiểm thử thiết bị; chưa chạy luồng tải/khôi phục backup Cloud thật trên Android. Không chạy Gradle, không triển khai migration hay sửa dữ liệu Supabase trong Step191.
- Review: `STEP191_REVIEW.md`.

## Step192 — Restore resume owner-authentication fence (03/11/2026)
- Audit phát hiện `RestoreCloudCoordinator.resumeLocked()` chưa tự xác thực Auth UID trước khi đọc journal/đi vào nhánh `COMPLETE` cleanup. Dù UI thường truyền `profile.id`, coordinator phải tự bảo vệ khi logout/đổi tài khoản hoặc màn hình cũ còn callback.
- Bổ sung exact Auth UID guard tại hai điểm import ZIP (Uri và Cloud inbox) và đầu `resumeLocked`; kiểm tra lại sau khi đọc journal, trước cleanup `COMPLETE`. Kiểm tra owner được đặt hai phía của truy vấn pending outbox để chặn account-switch trong khoảng DAO read.
- Giữ nguyên các owner guard ở photo preparation/upload, Cloud chunk staging, commit, snapshot download và Room apply. Room live inventory vẫn chỉ được áp dụng khi journal ở `SNAPSHOT_DOWNLOADED`.
- `verify_step192_restore_resume_owner_guard.py`: 10/10 PASS. Step191: 16/16 PASS. Step190: 15/15 PASS. Regression Step158–Step192: 35/35 verifier scripts, 577 checks PASS. Python compileall PASS.
- Giới hạn: static/source-contract verification; chưa chạy concurrency/logout race, process death, network loss, low-storage fault injection, Storage/RPC/commit/Room restore thật trên Android. Chưa Kotlin compile/APK/device test. Không chạy Gradle.
- Không thay đổi Supabase, SQL, Room schema, dữ liệu nghiệp vụ, Storage, restore journal hoặc outbox; không push GitHub.
- Review: `STEP192_REVIEW.md`.

## Step193 — Snapshot retry and account-switch fences (03/11/2026)
- Tiếp tục đúng source Step192 ZIP SHA-256 `4fb5372e1643c31e046649672e686e8dc90dcfeb486a1ab3c9fc0ecc742c740d`; không thay bằng ZIP cũ.
- Rà soát commit mơ hồ: giữ `COMMIT_UNKNOWN` trước RPC và phát lại đúng `restoreId`; `COMMIT_CONFIRMED` không gọi commit lần nữa. Rà soát upload chunk: lỗi/hủy giữ `CLOUD_STAGING`, retry cùng khóa chunk.
- Siết `RestoreCloudSnapshotReader`: kiểm tra Auth UID sau khi đọc journal, trước khi ghi trạng thái tải, sau từng RPC đọc/xác minh snapshot, trước khi ghi chunk Room và trước khi đánh dấu `SNAPSHOT_DOWNLOADED`.
- Siết `RestoreCloudSnapshotApplier`: xác minh lại Auth UID ngay trước transaction thay snapshot Room.
- Xác nhận coordinator và SyncPullWorker dùng chung `RestoreSnapshotGate`; Room transaction kiểm tra đủ chunk/dòng trước khi xóa dữ liệu kho hiện tại. Snapshot tải dở chỉ nằm trong staging; retry xóa staging snapshot dở và tải lại, không đụng dữ liệu kho live.
- `verify_step193_snapshot_retry_fences.py`: 16/16 PASS; Step192 10/10; Step191 16/16; Step190 15/15. Tổng 4 verifier: 57/57 PASS. `python -m compileall -q tools`: PASS.
- Giới hạn: mới là static/source-contract checks; chưa fault-injection thực tế (mất mạng, process death, thiếu dung lượng, race worker/coordinator, đổi tài khoản), chưa gọi restore RPC/Storage thực tế, chưa thực hiện restore Room. Chưa Kotlin compile/build APK/cài/test điện thoại. Không chạy Gradle.
- Không thay đổi Supabase/migration, dữ liệu nghiệp vụ local/remote, Storage, restore journal/outbox hoặc GitHub.
- Review: `STEP193_REVIEW.md`.

## Step194 — Low-storage, large ZIP and interrupted staging safety (03/11/2026)
- Tiếp tục đúng source Step193; không thay bằng ZIP cũ.
- Giữ giới hạn ZIP cứng (10.002 entries, 300 MiB uncompressed tổng, giới hạn từng mục), đổi bộ đếm byte sang `Math.addExact` để tránh overflow; kiểm tra owner và dung lượng trống ngay trước từng lần ghi giải nén.
- Bổ sung kiểm tra lỗi tạo thư mục đích; ảnh copy sang durable staging được preflight tổng dung lượng + reserve, kiểm tra từng chunk, flush + `fsync` trước khi journal tham chiếu thư mục ảnh.
- Phân biệt chính xác journal `STAGING` đã ghi thành công hay chưa. Nếu ENOSPC/hủy trước journal, dọn đúng thư mục UUID mới tạo vì chưa có recovery contract; nếu journal đã tồn tại, giữ staging rows/files để re-import, thử ghi FAILED best-effort nhưng không che lỗi gốc. Chỉ ghi `STAGED` sau khi số dòng Room staging khớp chính xác; không xóa/sửa kho live trong bước ZIP staging.
- `verify_step194_low_storage_staging_safety.py`: 17/17 PASS. Regression Step190–194: 5 scripts, 74/74 checks PASS. Compatibility Step112/115/116/117/150: 63/63 PASS. Python compileall PASS.
- Giới hạn: mới static/source-contract; chưa fault-injection ENOSPC, thiếu dung lượng, ZIP 90 MB, process death/restart, filesystem crash hay concurrent restore thật. Chưa Kotlin compile/APK/device test; không chạy Gradle. Không gọi Supabase, không restore Room, không thay đổi dữ liệu nghiệp vụ/backup/Storage, không push GitHub.
- Review: `STEP194_REVIEW.md`.

## Step195 — Durable staging integrity and recovery eligibility (03/11/2026)
- Tiếp tục đúng source Step194; chỉ sửa tăng cường kiểm tra dữ liệu staging.
- `RestoreStagingLifecycle.inspect()` chỉ báo ảnh sẵn sàng khi source rows hợp lệ và mọi ảnh được tham chiếu tồn tại, không rỗng, đúng định dạng, nằm trong thư mục `photos` canonical.
- `canResumeStaged()` không còn chỉ dựa vào tổng số dòng. Bổ sung xác minh chính xác owner/restore/entity type, số dòng, chunk index liên tục từ 0, row index liên tục trong từng chunk, tối đa 200 dòng/chunk và payload không rỗng.
- `RestoreCloudPayloadPreparer` dùng chung validator trước khi upload ảnh/ánh xạ payload; dữ liệu lỗi không đủ điều kiện tiếp tục.
- Validator chỉ đọc, không tự sửa/xóa staging hay dữ liệu live. Staging sai cấu trúc cần nhập lại/kiểm tra có kiểm soát.
- `verify_step195_staging_integrity.py`: 14/14 PASS. Regression Step190–195: 6 scripts, 88/88 checks PASS. Compatibility Step112/115/116/117/150: 63/63 PASS. Python compileall PASS.
- Giới hạn: static/source-contract; chưa inject lỗi Room, thiếu ảnh, process death, filesystem fault, concurrency hay chạy Android thật. Chưa Kotlin compile/APK/device test; không dùng Gradle. Không gọi Supabase, không sửa dữ liệu local/remote, không commit restore, không push GitHub.
- Review: `STEP195_REVIEW.md`.

## Step196 — Staging lifecycle owner-authentication fences (03/11/2026)
- Tiếp tục đúng source Step195 ZIP SHA-256 `b0ebc0d1c63f2dd6995450a3fab140cf5c74827d07d91143fc37b9cd6fefa385`.
- Bổ sung exact Supabase Auth UID predicate trong `RestoreStagingLifecycle`. `inspect()` kiểm tra trước/sau truy vấn Room; `canResumeStaged()` fail-closed nếu sai tài khoản và kiểm tra lại sau xác minh rows/ảnh.
- `cleanupCompleted()` kiểm tra owner quanh việc đọc journal/cursor và ngay trước khi dọn staged rows/files; vẫn yêu cầu journal đúng owner, `COMPLETE`, cursor khớp.
- `abort()` kiểm tra owner trước truy vấn journal, trước Room transaction và trước dọn filesystem; vẫn chỉ cho phép `STAGING`, `STAGED`, `FAILED`, không thể abort sau khi commit bắt đầu.
- `verify_step196_staging_owner_fences.py`: 16/16 PASS. Regression Steps190–196: 7 scripts, 104/104 checks PASS. Compatibility Steps112/115/116/117/150: 63/63 PASS. Python compileall PASS.
- Giới hạn: static/source-contract; chưa chạy race import/abort/resume, account-switch timing, process death, filesystem fault, WorkManager/Room concurrency hoặc thiết bị thật. Chưa Kotlin compile/APK/device test; không dùng Gradle. Không gọi Supabase, không sửa dữ liệu local/remote, không push GitHub.
- Review: `STEP196_REVIEW.md`.

## Step197 — Serialize WorkManager sync workers with restore lifecycle (03/11/2026)
- Tiếp tục đúng source Step196 ZIP SHA-256 `077a8519578873c0bd44f801123900ae88f833be019256e0f327a2ff4201b26c`.
- Phát hiện chỉ khóa `SyncPullWorker` là chưa đủ: `SyncOutboxWorker` cũng có thể claim/gửi giao dịch đúng lúc coordinator kiểm tra outbox rỗng. Vì vậy cả hai WorkManager worker đều dùng chung `RestoreOperationGate` trước mọi DAO/network work.
- Nếu restore đang giữ gate, worker trả `Result.retry()` ngay, không đọc/ghi cursor, không claim outbox và không gọi mạng. Gate được nhả trong `finally` kể cả success/retry/exception/cancellation.
- Pull worker vẫn giữ `RestoreSnapshotGate` cho snapshot read/apply; thứ tự khóa thống nhất là operation gate trước snapshot gate. Không thay đổi sender idempotency, cursor semantics hay restore state machine.
- `verify_step197_sync_restore_serialization.py`: 17/17 PASS. Regression Step190–197: 121/121 PASS. Compatibility Step112/115/116/117/150: 63/63 PASS. Tổng bộ kiểm tra chọn lọc: 184/184 PASS. Python compileall PASS.
- Giới hạn: static/source-contract only; chưa chạy WorkManager runtime, race concurrency/process death/network, Kotlin compile, APK/device test. Mutex là process-local, giả định WorkManager chạy trong process ứng dụng thông thường; chưa phải khóa liên tiến trình. Không chạy Gradle.
- Không gọi Supabase, không thay đổi Room/user data/Storage, không chạy restore, không push GitHub.
- Review: `STEP197_REVIEW.md`.


## Step198 — Process-death recovery for confirmed restore sessions (04/11/2026)
- Re-started the sequential work from the exact Step197 archive SHA-256 `4d09d147d79fe5397c32e303aaa02054fd58050260cc4fb694fa4ffd15d6d744`; this is a new implementation of the previously planned Step198 scope, not a claim that a missing historical Step198 archive was recovered.
- Added owner + restoreId-scoped `RestoreRecoveryWorker` and unique WorkManager scheduling on authenticated app startup. Only already-confirmed `LOCAL_ZIP` journals in coordinator-safe states are resumed. `REMOTE_FEED` remains with `SyncPullWorker`.
- Interrupted `STAGING`, `FAILED`, and `ABORTED` imports are not auto-mutated; STAGED is resumed only after staged rows/photos validate. The worker delegates to the existing idempotent coordinator, preserving Outbox-empty checks, operation/snapshot gates, remote commit and `SNAPSHOT_DOWNLOADED` Room-apply boundary.
- Added a live owner-scoped Room Flow for restore journals so Compose lock/status reacts to WorkManager recovery. Sign-out/inactive-profile retirement cancels only that owner's recovery work.
- `verify_step198_process_death_restore_recovery.py`: 28/28 PASS. Regression Step190–197: 121/121 PASS. Combined targeted checks: 149/149 PASS. Python compileall PASS.
- Limitations: source-contract checks only; no Kotlin compile/APK/device process-death test, no live Supabase/Storage/RPC, no restore execution, no data/schema mutation, no Gradle, no GitHub push. Local ZIP interrupted during STAGING still requires re-selecting the same archive; live inventory remains untouched.
- Review: `STEP198_REVIEW.md`.


## Step199 — Atomic restore reservation and concurrent-restore race fence (04/11/2026)
- Tiếp tục từ đúng ZIP Step198 SHA-256 `281a725430bf817398d3000e711db94f324576242a44b2c06cbd65787ff1ca71`; giữ nguyên toàn bộ recovery Worker/scheduler/Room Flow của Step198.
- Bổ sung `InventoryDao.beginLocalRestoreIfSafe()` trong một Room `@Transaction`: chỉ chấp nhận `LOCAL_ZIP` + `STAGING`, kiểm tra owner/restoreId, từ chối journal cùng ID đã tồn tại, từ chối bất kỳ active restore nào khác và insert bằng `OnConflictStrategy.ABORT`.
- `ZipRestore` chỉ đánh dấu journal đã persist sau khi reservation transaction thành công. Nếu một restore khác thắng race, thao tác thua cuộc fail-closed và cleanup chỉ nhắm thư mục staging UUID riêng của nó; không xóa journal/staging của phiên thắng.
- Giữ nguyên remote reservation có kiểm tra Outbox, operation gate, snapshot gate và Room transaction chỉ áp dụng snapshot ở trạng thái `SNAPSHOT_DOWNLOADED`.
- `verify_step199_atomic_restore_reservation.py`: 13/13 PASS; verifier Step194 sau cập nhật: 17/17 PASS; Step198: 28/28 PASS; regression Step190–197: 121/121 PASS. Tổng mục tiêu không đếm trùng: 162/162 PASS. Python compileall PASS.
- Giới hạn: static/source-contract; chưa runtime race hai tiến trình, fault injection, Kotlin compile/APK/device test. Không dùng Gradle, không gọi Supabase/Storage, không đổi dữ liệu/Room schema, không push GitHub.
- Review: `STEP199_REVIEW.md`.


## Step200 — Explicit owner-scoped Cloud backup entrypoint (04/11/2026)
- Tiếp tục từ đúng ZIP Step199 SHA-256 `85687f2d0c41e3e0678397d74dc07c002f7145c735979067876e95fc946f7e7b`; giữ nguyên Step198 process-death recovery/Room Flow và Step199 atomic reservation.
- Thêm `WeeklyBackupWorkScheduler.enqueueNow(ownerId)` để đưa một `WeeklyBackupWorker` chạy một lần vào WorkManager, yêu cầu có mạng, retry exponential và unique work theo owner (`KEEP`) để tránh enqueue trùng thay thế tiến trình đang chạy.
- Màn Công cụ có nút **Sao lưu Cloud ngay**; khóa khi restore đang active, kiểm tra lại Auth UID khớp owner trước khi enqueue. UI chỉ thông báo đã vào hàng đợi, không tuyên bố backup hoàn tất; người dùng cần tải lại catalog và xác nhận trạng thái VERIFIED.
- Logout/cancel owner hủy cả one-time và periodic work. Scheduler tuần định kỳ vẫn tách riêng, chưa có call site và không được tự kích hoạt.
- Làm rõ pipeline uploader: đăng ký metadata → resumable TUS `upsert=false` → authenticated readback → exact size/SHA-256 → RPC VERIFIED → commit journal Room.
- `verify_step200_manual_cloud_backup_entrypoint.py`: 18/18 PASS; Step199: 13/13 PASS; Step198: 28/28 PASS; regression Step190–197: 121/121 PASS. Tổng mục tiêu không đếm trùng: 180/180 PASS. Python compileall PASS.
- Giới hạn: source-contract verification; chưa Kotlin compile/APK/device test, chưa chạy TUS/Storage/RPC/SHA-256 round-trip thật trên Android. Không dùng Gradle. Không thay đổi Supabase schema/policy/data/metadata/Storage object hoặc dữ liệu nghiệp vụ; không push GitHub.
- Việc còn dở: WorkManager live progress/result chưa hiển thị trong UI; cần xác minh upload thật trên Android khi có APK/test phù hợp. Lịch tuần tự động vẫn chưa bật.
- Review: `STEP200_REVIEW.md`.


## Step201 — End-to-end owner-scoped Cloud Restore source path (04/11/2026)
- Tiếp tục từ đúng ZIP Step200 SHA-256 `20f83745aef8b7a6588eb8a10cb4a5cc8978a2df2a65ce89eb724ae43574992e`; giữ nguyên Step198 recovery worker và Step199 atomic restore reservation.
- Rà soát toàn tuyến catalog Cloud → authenticated download → kiểm tra size/SHA-256/manifest → inbox riêng theo owner → xác nhận thay thế kho → staging ZIP → upload/xác minh ảnh → staging PART/MOVEMENT → commit Cloud → đọc/xác minh snapshot → Room apply nguyên tử → cleanup.
- Xác nhận coordinator chỉ nhận ZIP canonical trong inbox đúng owner, chặn pending Outbox, giữ operation gate và journal resume. Photo uploader dùng path owner/restore/hash và xác minh object trước khi đưa path vào payload.
- Xác nhận sender stage cả PART/MOVEMENT, committer đối chiếu counts, snapshot reader xác minh chunks trước `SNAPSHOT_DOWNLOADED`, applier chỉ áp dụng snapshot ở trạng thái đó và cleanup chỉ sau `COMPLETE`.
- Cập nhật README để bỏ mô tả cũ mâu thuẫn về việc coordinator chưa nối UI; bổ sung thông tin Step198 WorkManager recovery. Không đổi giao thức restore hoặc Room semantics.
- `verify_step201_cloud_restore_end_to_end.py`: 29/29 PASS; Step200 18/18; Step199 13/13; Step198 28/28; regression Step190–197 121/121. Tổng kiểm tra mục tiêu: 209/209 PASS. Python compileall PASS.
- Giới hạn: source-contract; chưa live Cloud download, Storage photo upload, restore RPC commit, snapshot readback hoặc SHA-256 round-trip; chưa Kotlin compile/APK/device test. Không thay đổi dữ liệu Supabase/Storage/Room nghiệp vụ; không Gradle, không push GitHub.
- Review: `STEP201_REVIEW.md`.


## Step202 — Supabase KTV account-management authorization hardening (04/11/2026)
- Tiếp tục từ đúng ZIP Step201 SHA-256 `1ace28d6caee58d27856be4660e4ff3118fb5c0ddfba7cd154a17baa4ddde35e`; giữ nguyên Steps198–201.
- Audit `admin-users` phát hiện function kiểm tra `profiles.role=admin` + `is_active`, nhưng dùng service-role client nên không tự bị RLS bảo vệ và thiếu kiểm tra liên kết Admin bất biến `app_admins`.
- Bổ sung truy vấn `app_admins.user_id = authData.user.id`; lỗi DB fail-closed, thiếu liên kết trả 403 trước khi parse hoặc thực hiện hành động quản trị.
- Giữ nguyên allowlist create/reset_password/lock/unlock, validation payload, target chỉ role KTV, mật khẩu tạm và cờ buộc đổi mật khẩu.
- `verify_step202_supabase_ktv_account_management.py`: 14/14 PASS; Step201 29/29; Step200 18/18; Step199 13/13; Step198 28/28; regression Step190–197 121/121. Tổng mục tiêu: 223/223 PASS. Python compileall PASS.
- Giới hạn: source Edge Function chưa deploy, chưa live API hoặc kiểm thử tài khoản KTV thực tế. Không thay đổi Supabase profile/Auth data, schema, policy, migration, Storage hay dữ liệu kho; không Gradle, không push GitHub.
- Review: `STEP202_REVIEW.md`.


## Step203 — Admin KTV-management UI safety and interaction hardening (04/11/2026)
- Tiếp tục từ đúng ZIP Step202 SHA-256 `e8974e21497b1824fce3ae18892a17b4e401faea1442648d5393aed804d50a8d`; giữ nguyên Steps198–202.
- Hoàn thiện validation UI cho username chuẩn hóa ASCII `^[a-z0-9_-]{3,32}$` và display name đã trim, dài 1–80 ký tự; backend vẫn là lớp xác thực cuối cùng.
- Thêm mutation-busy guard dùng chung cho create/reset/lock/unlock; chặn thao tác lặp, refresh và sign-out khi đang xử lý. Dialog xác nhận chỉ đóng khi server trả thành công; lỗi giữ dialog để thử lại; cancellation được propagate.
- Tải lại danh sách KTV sau mutation thành công và hiển thị lỗi tải danh sách. Làm rõ kho Admin vẫn là kho riêng theo owner đăng nhập, không gộp linh kiện giữa các KTV.
- Giữ nguyên AdminScreen chỉ hiển thị ở nhánh Admin; kho của Admin tiếp tục sử dụng `profile.id` như owner riêng.
- Hiệu chỉnh 5 verifier cũ theo source hiện tại: Step80 16/16, Step90 10/10, Step105 16/16, Step106 13/13, Step113 14/14 (69/69). Đây chỉ là cập nhật assertion, không đổi runtime logic.
- `verify_step203_admin_management_ui.py`: 15/15 PASS. Regression Steps198–202 và Step190–197: 223/223 PASS. Tổng mục tiêu gồm 5 verifier bảo trì: 307/307 PASS. Python compileall PASS.
- Giới hạn: source/UI contract; chưa Kotlin compile, Compose runtime, APK/device test. Step202 Edge Function chưa deploy; chưa live Supabase/Storage/backup/restore test. Không dùng Gradle, không deploy hoặc sửa dữ liệu Supabase, không push GitHub.
- Review: `STEP203_REVIEW.md`.


## Step204–Step205 — Supabase remote/source reconciliation (04/11/2026)
- Read-only inspected the live project `ihhxxxqojelbxisreqns`: ACTIVE_HEALTHY, PostgreSQL 17.11.0.003. `list_migrations` confirms Step204, Step204_01, Step205 and Step205_01 are already applied remotely.
- Corrected an earlier provenance statement: Step139 restore objects exist in the live catalog, but the nine Step139 version strings do not appear in the current remote migration-history listing. Treat schema presence and migration-history provenance as separate facts.
- Inspected `password_reset_guard`, `handle_forced_password_change`, `on_auth_user_password_changed`, `is_active_profile`, `is_app_admin`, `complete_password_change`, `admin_audit_events`, `list_admin_audit_events`, RLS, policies, constraints, indexes and table/function privileges.
- Found Step203 ZIP source omitted Step204/205 artifacts. Mirrored deployed `admin-users` Edge Function version 6 source into `supabase/functions/admin-users/index.ts`; this version adds forced-password-state validation, Admin-link validation, durable STARTED/SUCCEEDED/FAILED audit writes, and the password-reset suppression guard. Supabase reports ACTIVE version 6, `verify_jwt=false`; the handler validates bearer tokens using `auth.getUser`.
- Reconstructed four Step204/205 SQL snapshots from live catalog definitions under `supabase/remote_migration_snapshots/`. These are explicitly not byte-for-byte original SQL, are not active migrations, and must not be deployed/replayed.
- Remaining gap: AdminScreen/AuthRepository do not yet expose the `list_admin_audit_events` RPC, so the backend audit log has no in-app viewing screen. No live mutation/password flow was tested.
- This was a read-only Supabase audit; no SQL/migration was applied, no Edge Function was deployed, and no user, password, business data, Storage object or production setting was changed.
- Gradle permission has now been granted by the user, but no Gradle command/build has been run in this reconciliation phase.
- Review: `STEP204_205_SUPABASE_RECONCILIATION.md`.

## Gradle preflight — authorized, build blocked by environment (04/11/2026)
- User explicitly authorized Gradle inspection/execution after safe preparation.
- Audited wrapper 8.9, AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, build-tools 35.0.0 CI contract, and optional `local.properties` behavior.
- Found `gradlew` shipped without executable bit; corrected to mode 0755 in the reconciled working source.
- Wrapper JAR integrity check passed. `./gradlew --no-daemon --version` attempted the configured Gradle 8.9 download and failed after four attempts with `UnknownHostException: services.gradle.org`; no Gradle distribution cache, Android SDK, `sdkmanager`, or `adb` exists in this environment.
- No Gradle build, Kotlin compile, APK assembly, signing, install or device test was completed. The environment is not yet prepared; see `GRADLE_PREPARATION.md`.

## Supabase SECURITY DEFINER catalog audit — in progress (04/11/2026)
- Read-only live catalog query corrected the count: **27** `SECURITY DEFINER` functions in `public` (not 20/25); `anon` EXECUTE 0/27, `PUBLIC` EXECUTE 0/27, `authenticated` EXECUTE 20/27. All 27 have fixed `search_path` settings.
- Identified 6 trigger functions and one event-trigger function among the 7 not callable by anon/authenticated. Trigger attachments confirmed for sync commit order, restore chunk/session quotas, single restore commit, Auth profile creation and forced password-change handling. `rls_auto_enable()` is an event-trigger function.
- Reviewed key Admin, Auth, owner-scoped sync, backup and restore RPC gates; no grant or database mutation was made.
- Full branch-by-branch/adversarial review of all 20 authenticated RPCs remains in progress; do not mark this audit complete yet. See `SECURITY_DEFINER_AUDIT_20261104.md`.

## Step206 — Admin audit history UI (04/11/2026)
- Added `AdminAuditEvent` serialization model and `AuthRepository.getAdminAuditEvents(limit)` using the already-deployed Step205 `list_admin_audit_events` RPC. Client requests are bounded to 1..200; server-side Admin authorization remains authoritative.
- Added an on-demand collapsible audit panel to `AdminScreen` with refresh, loading/error/empty states, actor/target, action/status, created/finished timestamps and error code. Existing KTV mutation busy guard and Admin-own-warehouse separation remain intact.
- Corrected `migrations_pending_reconciliation/README.md`: Step139 restore objects exist in the live catalog, but the nine Step139 migration version strings are absent from current migration history. The pending SQL is not to be replayed.
- `verify_step206_admin_audit_ui.py`: 12/12 PASS.
- Limitations: static source-contract only; Kotlin/Compose compile and runtime, authenticated Admin RPC call, and device rendering remain unverified because Gradle distribution and Android SDK are unavailable in the current environment. No Supabase mutation/deploy and no GitHub push.
- Review: `STEP206_REVIEW.md`.


## Step206 — Admin audit history UI (04/11/2026)
- Added `AdminAuditEvent` serialization model and `AuthRepository.getAdminAuditEvents(limit)` using the already-deployed Step205 `list_admin_audit_events` RPC. Client requests are bounded to 1..200; server-side Admin authorization remains authoritative.
- Added an on-demand collapsible audit panel to `AdminScreen` with refresh, loading/error/empty states, actor/target, action/status, created/finished timestamps and error code. Existing KTV mutation busy guard and Admin-own-warehouse separation remain intact.
- Corrected `migrations_pending_reconciliation/README.md`: Step139 restore objects exist in the live catalog, but the nine Step139 migration version strings are absent from current migration history. The pending SQL is not to be replayed.
- `verify_step206_admin_audit_ui.py`: 12/12 PASS.
- Limitations: static source-contract only; Kotlin/Compose compile and runtime, authenticated Admin RPC call, and device rendering remain unverified because Gradle distribution and Android SDK are unavailable in the current environment. No Supabase mutation/deploy and no GitHub push.
- Review: `STEP206_REVIEW.md`.


## Step207 — SECURITY DEFINER static review status and migration evidence reconciliation (04/11/2026)
- Đã xác minh lại SHA-256 của ZIP Step206 trước khi tiếp tục.
- Chốt trạng thái chính xác: đã hoàn tất đọc định nghĩa catalog từng RPC cho toàn bộ 20 hàm `SECURITY DEFINER` có EXECUTE bởi `authenticated`; đã phân loại 6 trigger functions và 1 event-trigger function. Đây là static review, **không** đồng nghĩa đã hoàn tất kiểm thử bảo mật runtime.
- Cập nhật `SECURITY_DEFINER_AUDIT_20261104.md`: authenticated adversarial tests, malicious-input/fault-injection và concurrency tests vẫn còn thiếu.
- Đối chiếu 25 SQL file đang lưu trong `migrations_pending_reconciliation`: đây là tập nguồn lịch sử hỗn hợp, có draft restore đã bị thay thế và hardening steps về sau; không đủ chứng cứ để gán chính xác 1-1 vào 9 Step139 ledger versions bị thiếu hoặc tái tạo transaction boundaries. Không tạo mapping suy đoán.
- Ghi rõ quan sát `list_migrations` mới nhất (04/11/2026) không có 9 version Step139, dù các restore objects vẫn hiện diện trong live catalog. Quan sát lịch sử Step141 từng ghi 16 entries được giữ lại như lịch sử; kết quả mới hơn là bằng chứng hiện tại và supersedes cho trạng thái hiện tại.
- Không đổi Supabase, schema, grants, RLS, dữ liệu, Auth, Storage, Edge Function; không push GitHub, không build APK hoặc chạy Gradle.
- Review: `STEP207_REVIEW.md`.

## Step208 — Cloud backup verification trust model (04/11/2026)
- Đã lần theo luồng backup Android và SQL Step187: app chính thức upload object riêng tư theo owner, tải đọc lại qua Auth, kiểm tra đúng kích thước + SHA-256 rồi mới gọi `complete_inventory_backup`.
- RPC backend kiểm tra active profile, metadata row đúng owner, digest do client gửi trùng digest đã đăng ký, object đúng owner tồn tại và metadata size khớp; backend **không tự tải và hash bytes**.
- Bảng lưu `verification_method='CLIENT_DOWNLOAD_SHA256'`. Vì vậy `VERIFIED` phản ánh client readback verification, không phải server-side cryptographic attestation. Custom client có thể tự gọi RPC với digest đã đăng ký mà không thực sự hash object; owner isolation vẫn được giữ.
- Restore client vẫn tự tải, kiểm tra size/SHA-256, ZIP structure, manifest owner và payload trước khi thay kho. Không được bỏ các kiểm tra này chỉ vì status `VERIFIED`.
- Đề xuất giữ giao thức hiện tại, làm rõ nhãn UI “đã xác minh bởi ứng dụng”; chỉ thiết kế server-side streaming verifier nếu người dùng yêu cầu mức bảo đảm server-attested. Không giảm giới hạn 256 MiB, không triển khai Edge Function hoặc đổi backend ở bước này.
- Review: `STEP208_REVIEW.md`; phân tích chi tiết: `BACKUP_VERIFICATION_TRUST_MODEL.md`.
- Giới hạn: chỉ static source review; chưa hostile-client/Storage/large-object/Edge resource/restore runtime test. Không đổi Supabase hoặc GitHub; không build APK.

---

## Kế hoạch xử lý tồn đọng hợp nhất — sau Step208 (04/11/2026)
- Tạm dừng tính năng mới và dừng cách chia mỗi thay đổi nhỏ thành một Step riêng. `BACKLOG_CONSOLIDATED.md` là bảng tồn đọng và điều kiện đóng việc duy nhất cho giai đoạn hoàn thiện.
- Ưu tiên P0: (1) build APK từ đúng nguồn qua GitHub Actions; (2) cài và kiểm thử thực tế trên điện thoại; (3) kiểm thử Backup/Restore end-to-end và bảo toàn dữ liệu owner.
- Ưu tiên P1: kiểm thử đồng bộ nhiều KTV trên nhiều thiết bị và kiểm thử bảo mật runtime. Source có Outbox sender, pull worker, conflict flow và restore coordinator; không được nhầm source-level implementation với runtime verification.
- Ưu tiên P2: Step139 migration provenance và tối ưu Advisor/index sau khi các luồng chính ổn định.
- Đã sửa các mô tả lỗi thời trong README/SYNC_DESIGN: đồng bộ hiện có trong source (push/pull, cursor, conflict, tombstone, Admin feed); vẫn chưa được kiểm thử runtime trên APK hiện tại.
- Xác minh lại ZIP Step208 SHA-256 trước khi cập nhật. `python -m compileall -q tools`: PASS. Regression Step190–208: 18 verifier scripts, 302/302 assertions PASS.
- Các verifier chỉ kiểm tra source-contract; chưa Kotlin/Gradle compile, chưa APK, chưa thiết bị, chưa chạy đồng bộ/backup/restore runtime. Không thay đổi Supabase, dữ liệu, Storage, Auth, migration history hoặc GitHub.
- Tại thời điểm hợp nhất, chưa tạo mốc kỹ thuật mới cho việc biên tập tài liệu. Step209 được mở sau đó cho thay đổi runtime source đáng kể về session expiry và automatic backup cadence.


## Step209 — 30-day session age guard and automatic backup cadence (05/11/2026)
- Tiếp tục từ source Step208 đã khôi phục trong Library; không yêu cầu người dùng gửi lại ZIP.
- Thêm `SessionExpiryPolicy`: lưu và commit đồng bộ `authenticated_at` riêng theo canonical owner ID; phiên hết hạn khi đủ 30 ngày (30 × 24 giờ), không gia hạn khi Supabase refresh access token. Timestamp thiếu hoặc nằm trong tương lai fail-closed để buộc đăng nhập lại một lần sau khi nâng cấp.
- Ghi timestamp ngay sau khi đăng nhập Supabase thành công; kiểm tra hết hạn cả lúc khôi phục phiên khi mở app và khi app trở lại foreground. Khi hết hạn, hủy sync/restore/backup jobs của đúng owner, kết thúc phiên và xóa timestamp; không xóa Room, ảnh, backup hoặc dữ liệu nghiệp vụ trên máy.
- Đổi chu kỳ periodic backup từ 7 thành 30 ngày; dùng `ExistingPeriodicWorkPolicy.UPDATE` để cập nhật lịch cũ thay vì `KEEP` bảo lưu lịch 7 ngày. Tài liệu Android xác nhận UPDATE thay spec và giữ enqueue time hiện tại.
- Tự kích hoạt periodic backup sau khi hồ sơ owner được xác minh và qua gate bắt buộc đổi mật khẩu; WorkManager vẫn yêu cầu mạng CONNECTED, worker vẫn kiểm tra active profile, session owner và journal. Không kích hoạt cho hồ sơ chưa xác minh/inactive hoặc đang buộc đổi mật khẩu. Lần backup định kỳ đầu tiên được WorkManager lên lịch theo chu kỳ 30 ngày; backup thủ công vẫn độc lập.
- Cập nhật verifier cũ theo hành vi mới và bổ sung `verify_step209_session_expiry_and_backup_cadence.py`: 12/12 PASS. Regression Steps190–209: 19 scripts, 314/314 assertions PASS. Verifier mục tiêu Step145 12/12, Step164 23/23, Step165 12/12, Step171 11/11, Step175 18/18, Step179 12/12 PASS. `python -m compileall -q tools`: PASS.
- Giới hạn bảo mật: mốc 30 ngày ở app là client-side guard dựa trên đồng hồ thiết bị, không thể thay thế enforcement phía Auth server và có thể bị ảnh hưởng nếu người dùng chỉnh giờ/xóa dữ liệu ứng dụng. Supabase Sessions docs nêu Time-boxed sessions chỉ khả dụng trên Pro plan trở lên. Project connector chỉ xác nhận project ACTIVE_HEALTHY, không cung cấp API đọc Auth session-timeout setting; chưa xác minh được plan/setting nên **chưa tuyên bố server-side 30-day enforcement**. Cần bật `Time-box user sessions = 30 days` trong Supabase Auth Settings nếu project/plan hỗ trợ.
- Chưa Kotlin compile/APK/device runtime test; không chạy Gradle theo quy trình hiện tại, không thay đổi Supabase Auth settings/dữ liệu, không deploy, không push GitHub.
- Review: `STEP209_REVIEW.md`.


## Step210 — Safe routing for unknown QR codes (05/11/2026)
- Khôi phục source từ `KhoLinhKienAndroid-step209-session-expiry-backup-20261105.zip` trong Library; không yêu cầu người dùng tải/gửi lại mã nguồn.
- Xác định nguyên nhân lỗi quét: `MainActivity` trước đây tra `dao.byCode(ownerId, scanned)`; nếu không tìm thấy trong Room thì mặc định mở `MachineHistoryDialog`. QR linh kiện chưa có trong cache/kho cục bộ vì vậy bị xử lý nhầm thành QR thiết bị.
- Sửa luồng: mã có trong kho tiếp tục mở `PartDialog` như cũ; mã chưa tìm thấy hiển thị lựa chọn rõ ràng `Thêm linh kiện` (mở `AddPartDialog` và điền sẵn mã QR), `Lịch sử thiết bị` (giữ nguyên `MachineHistoryDialog`) hoặc `Hủy`. Không tự động phân loại mã lạ thành thiết bị và không tự động tạo linh kiện.
- Thêm `verify_step210_unknown_qr_routing.py`: 7/7 kiểm tra source-contract PASS.
- Giới hạn: chưa Kotlin compile/APK build/device test; chưa xác minh tình trạng đồng bộ khiến mã có thể vắng trong Room cache. Không thay đổi dữ liệu Supabase, Auth, kho, lịch sử hoặc cấu hình server.


## Step214 — Guard authenticated startup and preserve database failures (06/11/2026)
- Đối chiếu APK `KhoLinhKien-v1.16.apk` với source Step210: APK có các lớp `AuthRepository`, `LoginScreen`, `SupabaseClientProvider`, `SessionExpiryPolicy` và logic QR chưa biết; source Step210 là baseline phù hợp hơn nhánh `main`/nhánh `work/step213` trên GitHub (những nhánh này vẫn chứa cây mã nguồn rút gọn cũ).
- Bugreport 16:37:41 ghi nhận 7 lần `APP CRASH(EXCEPTION)` của `vn.kholinhkien` trong 06/11 (15:26:51, 15:27:05, 15:34:24, 15:34:25, 15:34:42, 15:50:16, 16:29:40). `ApplicationExitInfo.trace=null`; logcat/dropbox crash payload không được đính kèm đầy đủ nên chưa thể gán exception cho một dòng Kotlin cụ thể. Không nhầm các lần người dùng vuốt đóng app hoặc HONOR low-memory kill với crash exception.
- Đường code được ưu tiên bảo vệ: sau khi đăng nhập thành công, `InventoryApp` mới mở Room tại `AppDb.get(context).dao()`; nếu migration/schema open thất bại, lỗi phát sinh trong composition có thể đóng tiến trình và trả về launcher. Đây là giả thuyết có căn cứ theo luồng thực thi, chưa phải nguyên nhân đã được chứng minh.
- Thay đổi: mở Room và chạy migration/schema validation trên `Dispatchers.IO` trước khi compose các Flow; bắt lỗi và hiển thị màn hình chẩn đoán có nút thử lại/đăng xuất an toàn; không xóa DB, ảnh, backup hoặc dữ liệu. Cô lập lỗi khởi tạo WorkManager và thao tác phân vùng cache owner để chúng không làm sập màn hình sau đăng nhập. Ghi stack trace lỗi khởi tạo vào `files/diagnostics/startup-failures.log`, không ghi mật khẩu/token/owner ID.
- Giới hạn: cần build và kiểm thử trên chính thiết bị để xác nhận; bản sửa hiện là source-level hardening, chưa chứng minh đã khắc phục nguyên nhân crash gốc vì bugreport thiếu stack trace. Không thay đổi Supabase, dữ liệu server hoặc signing config.

- Follow-up audit: device package metadata confirms installed `versionCode=17`, `versionName=1.16`; corrected next release metadata to `18` / `1.17` so the update can install without uninstalling. Added version monotonicity assertions to Step214 verifier. This correction does not prove the original crash root cause.

## Step219 — Normalize Supabase restore RPC JSON response shapes (07/11/2026)
- Tiếp tục từ nhánh đầy đủ `test/step214-v1.17`; tạo nhánh riêng `fix/step219-restore-rpc-json-shape`, không sửa nhánh nền.
- Điều tra lỗi thiết bị `Element class kotlinx.serialization.json.JsonLiteral is not a JsonObject` xuất hiện ở trạng thái `STAGED` và `COMMIT_UNKNOWN`. Source có các RPC response parser gọi `.jsonObject` trực tiếp sau `Json.parseToJsonElement(raw.toString())`, tại commit restore và đọc/xác minh snapshot; đây là các điểm có thể ném chính xác lỗi kiểu này khi SDK trả JSON string primitive bọc nội dung JSON.
- Thêm `RestoreRpcJson.kt`: chuẩn hóa phản hồi RPC từ `JsonElement`, chuỗi JSON hoặc JSON string primitive chứa object/array; chỉ unwrap một lớp có chủ đích; từ chối scalar/shape sai với lỗi nêu tên RPC và kiểu thực tế.
- Chuyển `RestoreCloudCommitter` và `RestoreCloudSnapshotReader` sang parser dùng chung; payload snapshot được kiểm tra `JsonArray` bằng safe cast. Chuyển `WeeklyBackupCloudCatalog` sang array parser dùng chung và kiểm tra từng row là `JsonObject` trước khi parse.
- Không thay đổi thứ tự commit, restoreId, idempotency, journal status, staging buckets, dữ liệu Room đang hoạt động, Supabase schema/RLS/Storage/Auth hoặc dữ liệu server. `COMMIT_UNKNOWN` vẫn giữ nguyên để retry đúng restoreId; không tạo phiên mới.
- Thêm `tools/verify_step219_restore_rpc_json_shape.py`: 11/11 PASS. Backup/restore regression sweep: 53/53 PASS. `python -m compileall -q tools`: PASS.
- Gradle compile đã thử: wrapper tải Gradle 8.9 nhưng môi trường không phân giải được `services.gradle.org` (`UnknownHostException`); chưa Kotlin compile, APK build hoặc test HONOR. Chưa gọi RPC Supabase live; source-level fix chưa được coi là runtime verified.


## Step220 — Owner-scoped warehouse wipe and backup-after-restore (07/11/2026)
- Added WarehouseWipeSection to the Admin management screen and the KTV Tools screen. The operation targets only the authenticated account's own warehouse; Admin privileges do not widen the deletion scope to KTV warehouses.
- The UI requires explicit destructive confirmation and re-entry of the current account password. The Auth sign-in result is checked against the original owner UUID before any wipe request.
- Added local Room owner-scoped transactional cleanup for parts, movements, outbox, cursors, tombstones, conflict decisions, restore staging/journals and local weekly-backup journals. It removes owner-pending photo files and deletes a legacy absolute photo only when Room proves no other owner references that exact path.
- User-saved ZIP archives and the private Cloud-restore inbox are deliberately retained. Account/profile/auth records and Admin audit history are retained.
- Added owner-bound purge_my_inventory_data() and finish_my_inventory_wipe() RPCs plus a server-side owner mutation fence. The fence is held while Storage cleanup runs so another device cannot recreate the same owner's inventory mid-wipe. The fence is released only after both Storage folders are empty.
- Added/deployed Supabase Edge Function purge-own-warehouse (ACTIVE v2, verify_jwt=true). It validates the bearer token and active profile, invokes the owner-bound RPC with the caller JWT, removes only the caller UUID folder in part-images and inventory-backups via the Storage API, then releases the mutation fence. The function is retryable if Storage cleanup is interrupted.
- Applied migrations 20261107100000_owner_scoped_warehouse_wipe and 20261107101500_warehouse_wipe_mutation_fence to project ihhxxxqojelbxisreqns. Read-only verification confirms both RPCs, the wipe-lock table and eight mutation-guard triggers exist. No user warehouse, Storage object, account or backup was deleted during implementation.
- Added Room migration 14→15 and a durable per-restore enqueue flag. After a successful local ZIP restore, the app enqueues one fresh Cloud backup; repeat resume/cleanup taps do not enqueue another for the same restore.
- GitHub Actions build for the latest source is still running; earlier run 82 failed only because the new Compose file was missing the Supabase auth extension import. That import was added in a later commit; do not treat the earlier failed run as the latest result.
- Not yet verified: final APK build/artifact, installation on HONOR device, live password-gated wipe using a disposable test account, Storage deletion across both buckets, and local ZIP restore followed by VERIFIED Cloud upload. Do not test the destructive action on a production warehouse until a disposable account/warehouse is selected.

- Release compatibility: bumped versionCode/versionName from 30/1.29 to 31/1.30 so the new APK can update over an already-installed 1.29 build without uninstalling. The version-31 signed build is now the required final artifact; earlier 1.29 artifacts are not the release to install.

## Build retry — 2026-11-07
- Re-trigger release workflow after correcting `localPhotoCleanupComplete` scope in `WarehouseWipeSection.kt`.
- Verify GitHub Actions result and `KhoLinhKien-release-apk` artifact before distributing APK 1.30.
