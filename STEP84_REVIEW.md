# Step84 – Khóa định danh và tính toàn vẹn khi sửa thông tin linh kiện

Date: 2026-11-03  
Base: Step83 live-stock guard checkpoint.

## Finding

`InventoryDao.update(part)` previously accepted a complete `Part` object and wrote it with `@Update` before enqueueing a `PART_UPSERT`. The edit UI did not expose quantity or QR code editing, but the DAO API itself did not enforce that boundary. A future caller or malformed state could therefore:

- change `quantity` without a corresponding Movement, breaking stock/history reconciliation;
- change `ownerId`, `syncId`, `code`, or `serverVersion` through the generic update path;
- write an unsupported tracking mode or negative minimum quantity;
- switch a BULK item with quantity greater than one to SINGLE, after which SINGLE outbound semantics only permit one item and hide the row after its first issue.

## Changes

- Change DAO API to `update(ownerId, part)` so the authenticated owner is an explicit argument; the UI passes `profile.id`.
- Require the Part's owner to match the authenticated owner and require the row to exist under that owner.
- Keep `syncId`, QR `code`, and `serverVersion` immutable through the edit path.
- Require `quantity` to remain unchanged in metadata edits. Stock changes must use NHẬP/XUẤT/KIỂM KÊ so a Movement and Outbox entry are recorded atomically.
- Validate nonblank name/unit, nonnegative minimum quantity, and allowlist tracking mode to SINGLE/BULK.
- Block BULK→SINGLE while quantity > 1; user must reconcile/stocktake or split the stock before switching to single-item semantics.
- Require exactly one row to be updated and enqueue PART_UPSERT with the previous photo path inside the same Room transaction.
- No schema migration. The edit dialog continues to keep QR code immutable; a dedicated code-change workflow is not introduced because it would need collision checks and server-side atomic rename semantics.

## Verification

- `tools/verify_step84_part_edit_identity.py`: 20/20 PASS, including owner mismatch, immutable identity, direct stock mutation, invalid metadata/mode, and unsafe BULK→SINGLE transition models.
- Re-ran Step83 16/16; Step82 14/14; Step81 20/20; Step80 16/16; Step79 12/12; Step78 7/7; Step77 7/7; Step76 7/7; Step75 7/7; Step74 5/5; Step73 5/5; reconstructed Step49–72 24/24 including SQLite smoke test. All passed.

## Limits

Checks are static source assertions and Python state models plus the existing SQLite smoke test. No Android Room runtime/instrumentation, PostgreSQL/Supabase staging, multi-device/concurrency/fault-injection, APK build, device test, production deployment, or GitHub push was performed. This remains an incremental change on the reconstructed Step49–72 source lineage, not byte-for-byte recovery of the missing original source.
