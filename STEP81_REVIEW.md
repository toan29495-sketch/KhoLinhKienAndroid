# Step81 – Movement history consistency validation

Date: 2026-11-03
Base: Step80 tombstone-ledger checkpoint.

## Finding

The restore SQL commit path validates movement type and before/after arithmetic, but the Android ZIP mapper and the local Room snapshot applier previously accepted any nonblank movement type and only checked that quantities were nonnegative. This allowed malformed history to pass local preparation and fail later at the cloud commit, or be accepted by a local snapshot application if it reached that path.

## Change

- `RestorePayloadMapper.movement()` now allows only `NHẬP`, `XUẤT`, `KIỂM KÊ`, and `ĐẦU KỲ`.
- `NHẬP`, `XUẤT`, and `ĐẦU KỲ` require positive amount; `KIỂM KÊ` may be zero.
- `NHẬP`: after = before + amount, compared using Long to avoid Int overflow.
- `XUẤT`: before >= amount and after = before - amount, compared using Long.
- `KIỂM KÊ`: after = amount.
- `ĐẦU KỲ`: before = 0 and after = amount.
- The Room cloud snapshot validation applies the same rules before clearing/replacing live owner data.
- No history rows are rewritten and no stock values are recomputed from history; the change rejects invalid restore input instead of guessing or silently repairing it.

## Verification

- `tools/verify_step81_movement_consistency.py`: 20/20 PASS (static assertions + valid/invalid arithmetic cases).
- `tools/verify_step80_tombstone_ledger.py`: 16/16 PASS.
- `tools/verify_step79_stale_pull_guard.py`: 12/12 PASS.
- `tools/verify_step78_delete_ack.py`: 7/7 PASS.
- `tools/verify_step77_sequential_rebase.py`: 7/7 PASS.
- `tools/verify_step76_rpc_ack_guard.py`: 7/7 PASS.
- `tools/verify_step75_stock_version_null.py`: 7/7 PASS.
- `tools/verify_step74_null_validation.py`: 5/5 PASS.
- `tools/verify_step73_idempotency.py`: 5/5 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 PASS including SQLite schema/atomic replacement smoke test.

## Limits

These are source/static and local Python/SQLite model checks. No Android Room instrumentation/runtime, PostgreSQL/Supabase staging, production deployment, APK build, or device test was performed. The SQL commit function's corresponding constraints were inspected but not executed against PostgreSQL in this step.

## Source lineage

This is an incremental change on the reconstructed Step49–72 source lineage. It is not a byte-for-byte recovery of the missing original Step49–72 source.
