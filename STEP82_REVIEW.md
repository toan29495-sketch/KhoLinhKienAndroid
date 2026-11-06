# Step82 – Idempotent movement pull and owner/payload collision guard

Date: 2026-11-03
Base: Step81 movement-consistency checkpoint.

## Finding

`applyPulledMovement()` used `INSERT OR IGNORE` and returned success without inspecting the affected-row result or the existing row. A replay with the same `syncId` but changed immutable fields, or a globally unique `syncId` collision with a different owner, could therefore be silently ignored while the change-feed cursor advanced. The pull mapper also accepted an owner field that did not match the authenticated owner, and substituted the current device time when a server timestamp was missing or malformed.

## Changes

- Added an owner-scoped transaction guard plus lookup by globally unique movement `syncId`.
- An existing row is treated as an idempotent replay only when all immutable movement fields match exactly (excluding the local auto-generated Room row id).
- A payload mismatch or cross-owner collision fails closed; the worker does not advance past the event.
- The `INSERT OR IGNORE` uniqueness-race path re-reads and verifies the row before returning success.
- Pull mapping now requires a canonical UUID, rejects a mismatched `owner_id`, validates movement type and before/after arithmetic consistently with Step81, and rejects missing/invalid timestamps rather than substituting device time.
- No schema change; the existing unique `movements.syncId` index is retained.

## Verification

- `tools/verify_step82_movement_idempotency.py`: 14/14 PASS (static assertions and idempotency/owner-collision model).
- Step81: 20/20 PASS.
- Step80: 16/16 PASS, including SQLite migration/state checks.
- Step79: 12/12 PASS.
- Step78: 7/7 PASS.
- Step77: 7/7 PASS.
- Step76: 7/7 PASS.
- Step75: 7/7 PASS.
- Step74: 5/5 PASS.
- Step73: 5/5 PASS.
- Reconstructed Step49–72 source suite: 24/24 PASS, including SQLite schema/atomic replacement smoke test.

## Limits

These are source/static checks and local Python/SQLite model checks. No Android Room instrumentation/runtime, PostgreSQL/Supabase staging, multi-device test, concurrency/fault-injection, APK build, device test, production deployment, or GitHub push was performed. The globally unique syncId index is present in the current Room entity/migration path; cloud-side uniqueness/runtime behavior still requires staging verification.

## Source lineage

Incremental change on the reconstructed Step49–72 source lineage. This is not a byte-for-byte recovery of the missing original Step49–72 source.
