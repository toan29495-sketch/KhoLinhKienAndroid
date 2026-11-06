# Ma trận kiểm thử đồng bộ KhoLinhKienAndroid

Tài liệu này là tiêu chí bắt buộc trước khi bật đồng bộ cho tài khoản thật. Chưa có kết quả test nào được đánh dấu đạt nếu chưa chạy trên PostgreSQL/Supabase và Android thực tế.

## 1. Giao dịch tồn kho

| Ca kiểm thử | Thao tác | Kết quả bắt buộc |
|---|---|---|
| S01 | KTV A nhập 5 vào kho của A | Tồn tăng đúng 5; đúng 1 movement |
| S02 | KTV A xuất 2 khi tồn đủ | Tồn giảm đúng 2; đúng 1 movement |
| S03 | KTV A xuất nhiều hơn tồn | RPC từ chối; tồn và lịch sử không đổi |
| S04 | Gửi lại cùng operation UUID sau timeout | Trả kết quả cũ; không tăng/giảm lần hai |
| S05 | Hai KTV cùng gửi thao tác vào cùng kho (nếu được cấp cùng owner) | Khóa dòng tuần tự; không mất cập nhật |
| S06 | Hai KTV khác owner dùng cùng mã linh kiện | Dữ liệu tách biệt theo owner |
| S07 | Kiểm kê về 0 | Tồn thành 0; movement lưu đúng số lượng trước/sau |
| S08 | Xuất SINGLE với amount khác 1 | Từ chối |

## 2. Phân quyền

| Ca kiểm thử | Thao tác | Kết quả bắt buộc |
|---|---|---|
| P01 | KTV đọc parts của KTV khác | Không trả dữ liệu |
| P02 | KTV gọi RPC với entity UUID thuộc owner khác | Từ chối |
| P03 | KTV cố gửi owner_id khác auth.uid() | Từ chối |
| P04 | Tài khoản is_active=false gọi RPC | Từ chối |
| P05 | Access token cũ của tài khoản bị khóa gọi PostgREST | RLS restrictive chặn |
| P06 | KTV sửa/xóa movement đã ghi | Không được phép |
| P07 | Admin xem lịch sử toàn hệ thống | Chỉ đọc theo policy đã thống nhất |
| P08 | Admin thao tác tồn kho KTV | Không được tự động cấp quyền |

## 3. Retry và xung đột

- Cắt mạng trước khi gửi: outbox còn PENDING.
- Cắt mạng sau khi server commit nhưng trước response: retry cùng UUID, chỉ có một movement.
- Gửi payload khác với UUID đã dùng: server từ chối.
- Chỉnh sửa metadata với server_version cũ: báo conflict, không ghi đè.
- Xóa linh kiện khi còn movement: giữ lịch sử; không cascade xóa movement.
- Restore snapshot: chỉ tác động owner hiện tại; xử lý/đóng outbox cũ có chủ đích; không phát lại movement lịch sử.

## 4. Storage

- Bucket private; file path bắt đầu bằng đúng owner UUID.
- KTV A không đọc, ghi, sửa hoặc xóa ảnh của KTV B.
- Ảnh tải lên thành công trước khi đồng bộ part tham chiếu ảnh.
- Upload timeout/retry không tạo nhiều file mồ côi không kiểm soát.
- Tài khoản bị khóa không thể tiếp tục truy cập Storage.

## 5. Điều kiện phát hành

Chỉ đánh dấu đồng bộ hoàn tất khi toàn bộ ca trên được chạy và có log/kết quả. Hiện tại các ca đều ở trạng thái **CHƯA KIỂM THỬ**.

## Change-feed / pull RPC (migration draft 2026-11-02)

| Case | Expected result | Status |
|---|---|---|
| A local PART_UPSERT is accepted | One PART snapshot event is appended in the same DB transaction | Not run |
| A local PART_DELETE is accepted | A PART tombstone snapshot (`deleted_at` set) is appended | Not run |
| A STOCK_CHANGE is accepted | PART quantity snapshot and immutable MOVEMENT event are appended together | Not run |
| Retry same operation UUID after response timeout | Existing ledger result is returned; no duplicate event is appended | Not run |
| Pull with cursor N | Only authenticated owner's events with event_id > N, ascending, max 500 | Not run |
| Pull under inactive account | RPC rejects with PROFILE_INACTIVE | Not run |
| Two different owners pull same cursor | Each receives only its own events | Not run |
| Empty page | next_cursor remains the supplied cursor | Not run |
