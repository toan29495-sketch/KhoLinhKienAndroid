# Trạng thái khôi phục và tái triển khai mã nguồn — 2026-11-03

## Nguồn và working copy
- Archive gốc Step26 được giữ nguyên; source được giải nén vào `KhoLinhKienAndroid_recovered_step47`.
- Thay đổi Step47 về ảnh theo operationId được áp dụng lại.
- Source Step49–Step72 nguyên bản không còn trong runtime; chỉ còn các review/progress mô tả. Không tuyên bố bản hiện tại là khôi phục byte-for-byte từ source Step72 cũ.
- Trên baseline đã khôi phục, các lớp Step49–Step72 đã được tái triển khai trực tiếp và kiểm tra. Chi tiết ở `STEP49_72_RECONSTRUCTION_REVIEW.md`.

## Source đã có trong working copy
- Part.serverVersion và Room migration 8→9.
- Outbox claim lease và Room migration 9→10.
- Conflict decision record và Room migration 10→11.
- CAS/tombstone migration SQL, owner-scoped read-only conflict snapshot RPC.
- Client version chaining/atomic ack, BLOCKED queue, FIFO claim, lease recovery, conflict inspection và giới hạn KEEP_LOCAL resolver.
- UI conflict detail, server snapshot, local/server comparison, dependent count, decision prepare/recheck và xác nhận KEEP_LOCAL trong phạm vi hẹp.

## Kiểm tra có bằng chứng
- `tools/verify_sync_step49_72.py`: 24/24 source-level checks PASS.
- SQLite smoke test: schema evolution giữ dữ liệu cũ và transaction mô phỏng replacement atomic PASS.
- Không có bằng chứng Kotlin/Android compilation, Room runtime migration, PostgreSQL/Supabase staging, Storage, thiết bị thật hoặc hai thiết bị.

## Giới hạn chưa giải quyết
- KEEP_SERVER và MANUAL_REVIEW chưa có workflow thực thi; chỉ lưu quyết định và giữ queue khóa.
- PART_DELETE, STOCK_CHANGE và dependent chain conflict chưa có resolver tự động.
- Legacy IN_FLIGHT với claimStartedAt=0 không tự phục hồi; cần đối soát.
- Lease 30 phút chưa có heartbeat renewal/fencing token; phải staging-test trước multi-process/production.
- Không xóa ảnh cloud cũ vì chưa có per-installation ACK/retention proof.

## Quy trình tiếp theo
1. Mỗi thay đổi phải được kiểm tra trực tiếp, có review + progress + checklist.
2. Mỗi checkpoint quan trọng tạo ZIP riêng và SHA-256; không ghi đè checkpoint trước.
3. Không deploy SQL, push GitHub hoặc tạo APK khi chưa có phê duyệt phù hợp.
4. Trước phát hành phải chạy test matrix trong `docs/STEP49_72_TEST_MATRIX.md` trên Room thật và Supabase staging.
- Bổ sung xác minh: SQLite EXPLAIN các SELECT Outbox PASS; standalone Kotlin compile của planner thuần PASS (không đại diện cho compile Android/Room).
