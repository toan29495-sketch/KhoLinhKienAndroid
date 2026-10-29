# Kho Linh Kiện Android — mã nguồn phiên bản hoàn thiện chức năng chính

Ứng dụng Android native, ưu tiên offline, lưu cơ sở dữ liệu Room cục bộ trên điện thoại.

## Chức năng có trong mã nguồn
- Danh sách tồn kho, tìm kiếm theo tên/mã/ghi chú, cảnh báo tồn thấp.
- Quét QR bằng camera; QR có sẵn phải chứa chính xác mã linh kiện.
- Thêm linh kiện, chụp ảnh bằng camera hoặc chọn ảnh có sẵn.
- Tạo ảnh QR PNG cho từng mã; nhập mã hàng loạt và tự sinh ảnh QR.
- OCR ảnh danh sách mã: trích xuất chuỗi 10 ký tự chữ/số để người dùng rà soát trước khi tạo.
- Nhập kho, xuất dùng, kiểm kê số lượng thực tế (cho phép tồn bằng 0); giao dịch được ghi vào lịch sử.
- Sửa tên, đơn vị, ghi chú và ngưỡng tồn.
- Xuất tệp Excel `.xlsx` có hai trang `Tồn kho` và `Lịch sử`.
- Sao lưu/khôi phục JSON, có kèm ảnh linh kiện nếu mỗi ảnh dưới 8 MB; khôi phục yêu cầu xác nhận và thay thế dữ liệu hiện tại.

## Mở và chạy
1. Giải nén ZIP trên máy tính.
2. Cài Android Studio phiên bản ổn định hiện hành, JDK 17 và Android SDK 35.
3. Mở thư mục `KhoLinhKienAndroid` bằng Android Studio, chờ Gradle tải thư viện.
4. Nếu Android Studio báo thiếu Gradle Wrapper, tạo/import Gradle project bằng Android Studio hoặc thêm Gradle Wrapper tương thích với Gradle 8.9.
5. Kết nối điện thoại Android, bật Developer options/USB debugging nếu chạy qua USB, rồi nhấn Run.
6. Cấp quyền camera khi quét QR. Không cần tài khoản hoặc Internet để sử dụng sau khi thư viện đã được tải và cài app.

## Quy tắc và lưu ý
- Mã tạo hàng loạt chỉ nhận chuỗi đúng 10 ký tự chữ/số. QR quét sẵn có thể mang mã khác định dạng và vẫn được tra cứu theo đúng nội dung quét.
- OCR có thể nhầm `O/0`, `I/1`, `B/8`; phải rà soát trước khi xác nhận tạo.
- QR sinh ra chứa nguyên văn mã linh kiện; in tem QR nên kiểm tra quét thử trước khi dán.
- Tồn kho ban đầu được ghi thành giao dịch `ĐẦU KỲ`; nhập/xuất/kiểm kê ghi lịch sử và không cho xuất vượt tồn.
- Khôi phục JSON sẽ thay thế dữ liệu hiện có; hãy sao lưu trước.
- Ảnh trên 8 MB không được nhúng vào JSON sao lưu để tránh tệp quá lớn; ảnh đó cần sao lưu riêng.
- Đây là mã nguồn đã hoàn thiện chức năng chính nhưng **chưa được biên dịch hoặc kiểm thử trên thiết bị thật trong môi trường tạo tệp này**. Cần chạy Gradle build và thử camera/QR/OCR/Excel/khôi phục trước khi dùng làm sổ kho chính. Chưa cung cấp APK đã ký.


## Phiên bản 1.2 – chế độ QR linh kiện
- Mặc định linh kiện lẻ: 1 mã QR cho 1 món; khi xuất 1 món, mã được ẩn khỏi danh sách tồn kho nhưng giao dịch vẫn được giữ trong lịch sử.
- Có thể chọn chế độ nhiều món dùng chung một mã QR; xuất kho trừ theo số lượng và mã vẫn hiển thị khi tồn về 0.
- Có nút xóa mã QR riêng kèm xác nhận; chỉ xóa khỏi danh mục, không xóa lịch sử giao dịch.
- Cơ sở dữ liệu nâng từ v1 lên v2 bằng migration, thêm cột chế độ QR và giữ dữ liệu đã có.
