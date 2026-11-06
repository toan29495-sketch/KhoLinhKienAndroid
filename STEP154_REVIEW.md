# Step154 — Không làm lùi trạng thái commit đã xác nhận (03/11/2026)

## Phỏng đoán rủi ro
- Khi server commit thành công và Android đã ghi `COMMIT_CONFIRMED` cùng snapshot cursor vào Room, người dùng có thể rời màn hình rồi bấm tiếp tục.
- Trước Step154, `RestoreCloudCoordinator` gọi `RestoreCloudCommitter` cho cả `COMMIT_CONFIRMED`; committer luôn ghi lại `COMMIT_UNKNOWN` và gọi RPC idempotent lần nữa.
- Cơ chế server ledger ngăn thay dữ liệu lần hai, nhưng nếu mạng lỗi trong lần RPC thừa này thì journal đã xác nhận bị lùi về trạng thái chưa rõ, gây hiển thị sai và thêm phụ thuộc mạng không cần thiết.

## Thay đổi
- `RestoreCloudCommitter.commit()` giờ short-circuit khi journal đã là `COMMIT_CONFIRMED`.
- Trước khi trả kết quả, yêu cầu `snapshotCursor > 0`; trả lại đúng `restoreId`, manifest, cursor và số lượng đã được journal xác nhận.
- Không gọi RPC, không ghi lại journal và không chuyển trạng thái sang `COMMIT_UNKNOWN` ở nhánh đã xác nhận.
- `CLOUD_STAGED` vẫn bắt đầu commit bình thường. `COMMIT_UNKNOWN` vẫn gửi lại đúng `restoreId` qua RPC idempotent để server ledger trả `already_committed` nếu request trước đã thành công.
- Không thay đổi SQL/RPC, schema Room, snapshot reader/applier, dữ liệu kho hoặc quy tắc owner fence.

## Kiểm chứng
- `verify_step154_confirmed_commit_short_circuit.py`: 13/13 PASS.
- Regression Step140–153: tất cả verifier PASS (bao gồm SQLite smoke và static contracts hiện có).
- `python3 -m compileall -q tools`: PASS.
- Kiểm tra source xác nhận chỉ còn một RPC `commit_inventory_restore` trong committer và nhánh `COMMIT_CONFIRMED` trả trước vị trí RPC.

## Giới hạn
- Đây là source/static verification và regression tests hiện có; chưa chạy coroutine/Room trên Android, chưa fault-inject mất mạng sau server commit thật.
- Chưa compile Android, chưa build/cài APK, chưa test điện thoại, chưa push GitHub. Không dùng Gradle.
- Không thay đổi Supabase hoặc dữ liệu máy chủ. SQL ledger được kiểm tra ở source migration candidate/reconciliation; không thực thi migration.
