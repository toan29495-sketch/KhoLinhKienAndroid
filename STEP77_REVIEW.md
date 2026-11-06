# Step77 — Gán expected_server_version tuần tự cho Outbox cùng linh kiện (2026-11-03)

## Phát hiện
`recordServerVersionAndRebase()` trước đây ghi cùng một `expected_server_version` vừa được máy chủ xác nhận vào tất cả giao dịch PENDING/FAILED của cùng linh kiện. Mỗi `STOCK_CHANGE`, `PART_UPSERT` hoặc `PART_DELETE` thành công đều tăng `server_version` một lần. Vì vậy, khi có từ hai giao dịch đang chờ, giao dịch thứ hai sẽ gửi token cũ và bị `PART_VERSION_CONFLICT` sau khi giao dịch thứ nhất đã tăng phiên bản.

## Thay đổi
- Giữ `Part.serverVersion` bằng phiên bản thực tế vừa được máy chủ xác nhận; không ghi trước phiên bản dự kiến vào entity local.
- Duyệt các giao dịch chưa gửi theo thứ tự FIFO `createdAt, rowid` từ DAO hiện có.
- Gán token lần lượt `version`, `version + 1`, `version + 2`... cho payload từng giao dịch.
- Dùng `Math.addExact` để overflow Long làm transaction thất bại an toàn, không wrap về số âm.
- Yêu cầu cập nhật đúng một Part thuộc owner/entity hiện tại; nếu không, transaction rollback thay vì đánh dấu Outbox SYNCED.
- Tất cả thay đổi payload, Part version và trạng thái SYNCED vẫn nằm trong cùng Room transaction.

## Kiểm tra
- `tools/verify_step77_sequential_rebase.py`: 7/7 static assertions PASS; FIFO version-chain model và overflow fail-closed PASS.
- Step76: 7/7 PASS.
- Step75: 7/7 PASS.
- Step74: 5/5 PASS.
- Step73: 5/5 PASS.
- Step49–72: 24/24 PASS, gồm SQLite schema/atomic replacement smoke test.

## Giới hạn
- Chưa chạy Room instrumentation/runtime để xác nhận transaction trên Android thật.
- Chưa chạy PostgreSQL/Supabase staging để kiểm chứng chuỗi nhiều mutation qua RPC.
- Giả định từng operation trong nhóm này tăng server_version đúng một lần; migration hiện tại làm điều đó cho ba loại operation được xử lý.
- Không build APK, không test thiết bị, không deploy SQL, không push GitHub.
