# Step49–Step72 — Staging/device test matrix (chưa chạy)

| Nhóm | Kịch bản bắt buộc | Kết quả mong đợi |
|---|---|---|
| Version migration | Cài bản Room 8 có dữ liệu; nâng lần lượt 9/10/11 | Không mất parts/outbox; serverVersion và claimStartedAt mặc định 0 |
| CAS upsert | Hai thiết bị đọc cùng server_version; thiết bị A cập nhật trước | Thiết bị B nhận PART_VERSION_CONFLICT; không ghi đè |
| Tombstone | Thiết bị A xóa; thiết bị B gửi upsert cũ | PART_DELETED; deleted_at không bị xóa |
| Delete CAS | Thiết bị B xóa bằng version cũ | PART_VERSION_CONFLICT; row server không bị xóa |
| Stock CAS | Hai thiết bị thay đổi tồn dựa trên cùng version | Chỉ một operation được nhận; operation stale bị BLOCKED |
| FIFO | Tạo PART rồi opening STOCK_CHANGE rồi sửa PART | RPC nhận theo đúng thứ tự; expected_server_version được rebase tuần tự |
| Lost response | Server commit RPC, cắt mạng trước khi client ack | Retry cùng operationId/payload; không tạo movement/stock mutation lần hai |
| Lease | IN_FLIGHT lease hết hạn | Chuyển FAILED giữ operationId/payload; retry idempotent |
| Legacy lease | IN_FLIGHT claimStartedAt=0 | Không tự reset; hiển thị cần đối soát |
| Blocked pause | Operation đầu queue BLOCKED | Không operation phía sau nào được gửi; pull không ghi đè part local; restore bị chặn |
| Snapshot privacy | Đọc snapshot bằng owner đúng/sai | Owner đúng đọc row; owner sai nhận null/không tiết lộ row |
| KEEP_LOCAL isolated | PART_UPSERT BLOCKED, server row tồn tại, không có operation sau | Tạo đúng một replacement operation với version mới; blocker RESOLVED |
| KEEP_LOCAL duplicate | Đóng app sau commit Room rồi mở lại | Resolver tìm replacementOperationId cũ; không sinh operation mới |
| Unsupported resolution | KEEP_SERVER, delete, stock, tombstone, dependent chain | Không thay đổi kho/Outbox; queue vẫn khóa |
| Photo retry | Upload thành công nhưng RPC/ack lỗi | Cùng operation path; ảnh local không bị xóa khi còn Outbox tham chiếu |
