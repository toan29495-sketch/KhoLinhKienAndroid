# Step83 – Live stock mutation type and arithmetic guard

Date: 2026-11-03
Base: Step82 movement-idempotency checkpoint.

## Finding

`InventoryDao.changeStock()` previously accepted any non-`KIỂM KÊ` type as a positive-amount movement. Its arithmetic `when` used `else -> amount`, effectively treating unknown movement types as stocktake while persisting the unsupported type. This allowed an invalid local stock mutation and Outbox record that would later conflict with the stricter restore/pull/server allowlists. The function also performed Int arithmetic before validating the resulting range.

## Changes

- Live stock mutation now explicitly allows only `NHẬP`, `XUẤT`, and `KIỂM KÊ`.
- `ĐẦU KỲ` remains exclusively generated atomically by `insertPartWithOpeningMovement()` when a part is created; it cannot be submitted as an ordinary live movement.
- Rejects a pre-existing negative local quantity before mutation.
- Computes inbound/outbound result using `Long`, checks `0..Int.MAX_VALUE`, and narrows to `Int` only after validation.
- Keeps part update, movement insert, and Outbox enqueue inside the existing `@Transaction`.
- No schema change and no change to owner scoping or sync payload structure.

## Verification

- `tools/verify_step83_live_stock_guard.py`: 16/16 PASS, including valid inbound/outbound/zero-stocktake and rejection of unknown type, opening-balance injection, negative current stock, insufficient stock, overflow, and negative stocktake.
- Re-ran Step82 14/14; Step81 20/20; Step80 16/16 including SQLite migration/state tests; Step79 12/12; Step78 7/7; Step77 7/7; Step76 7/7; Step75 7/7; Step74 5/5; Step73 5/5; reconstructed Step49–72 24/24 including SQLite schema/atomic replacement smoke test. All passed.

## Limits

These are source/static checks and local Python/SQLite model checks. No Android Room runtime/instrumentation, PostgreSQL/Supabase staging, multi-device/concurrency/fault-injection test, APK build, device test, production deployment, or GitHub push was performed. This is an incremental change on the reconstructed Step49–72 source lineage, not a byte-for-byte recovery of the missing original source.
