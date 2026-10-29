# Kho Linh Kiện Android

## GitHub Actions: tự biên dịch APK từ mã nguồn trong repository

Workflow `.github/workflows/android.yml` dùng `actions/checkout` để lấy **mã nguồn đã upload trực tiếp lên repository này**, rồi chạy `gradle assembleDebug`. Bạn không cần máy tính cài Android Studio để build; chỉ cần tạo repository và bật GitHub Actions.

### Cách đưa dự án lên GitHub bằng trình duyệt điện thoại

1. Tải ZIP mã nguồn và giải nén bằng ứng dụng quản lý tệp trên điện thoại.
2. Vào https://github.com và đăng nhập.
3. Nhấn `+` → `New repository`.
4. Đặt tên `KhoLinhKienAndroid`, chọn `Private` nếu muốn giữ mã nguồn riêng, rồi tạo repository.
5. Mở thư mục `KhoLinhKienAndroid` đã giải nén. Trong repository, upload **toàn bộ nội dung bên trong thư mục này**, không upload nguyên thư mục lồng bên ngoài. Phải có `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, thư mục `app/` và `.github/workflows/android.yml` ở ngay thư mục gốc của repository.
6. Nếu giao diện GitHub trên điện thoại không hiện `Add file` → `Upload files`, bật `Desktop site` trong menu trình duyệt. Nếu upload thư mục `.github` khó trên điện thoại, tạo file workflow thủ công:
   - Vào repository → `Add file` → `Create new file`.
   - Đặt tên: `.github/workflows/android.yml`
   - Dán nội dung file `android.yml` trong mã nguồn đã tải.
   - Commit file.
7. Mở tab `Actions`, chọn workflow `Build Android APK`, nhấn `Run workflow` nếu workflow chưa tự chạy. Đợi job xanh `Success`.
8. Mở lần chạy mới nhất, kéo xuống `Artifacts`, tải `KhoLinhKien-debug-apk`. Giải nén artifact ZIP để lấy `app-debug.apk`.
9. Chuyển APK về điện thoại và mở để cài. Android có thể yêu cầu cho phép cài ứng dụng từ nguồn này. Chỉ cài APK do workflow repository của chính bạn tạo ra.

### Nếu build thất bại
- Mở `Actions` → lần chạy thất bại → chọn job `build` → đọc bước màu đỏ và gửi ảnh hoặc sao chép log lỗi để xử lý.
- Workflow đang dùng Gradle 8.9 và Java 17, phù hợp với Android Gradle Plugin 8.7.3 trong dự án.
- GitHub Actions build APK debug để cài thử, không phải APK phát hành đã ký.

## Tính năng trong mã nguồn
- Kho offline cục bộ bằng Room.
- Tìm kiếm mã/tên linh kiện, QR camera.
- Tạo QR PNG theo mã, nhập mã hàng loạt và OCR ảnh chuỗi 10 ký tự.
- Chụp/chọn ảnh linh kiện.
- Nhập, xuất, kiểm kê, cảnh báo tồn và lịch sử giao dịch.
- Xuất XLSX hai trang: Tồn kho và Lịch sử.
- Sao lưu/khôi phục JSON có xác nhận; sao lưu ảnh nhỏ hơn 8 MB kèm theo.

## Lưu ý quan trọng
- Đây là mã nguồn cần build và kiểm thử thực tế. Workflow chỉ báo build thành công/thất bại; nó không thay thế kiểm thử camera, QR, OCR, sao lưu/khôi phục trên điện thoại.
- OCR có thể nhầm `O/0`, `I/1`, `B/8`; hãy rà soát mã trước khi tạo.
- Sao lưu JSON trước khi khôi phục vì thao tác khôi phục thay thế dữ liệu trên máy.
