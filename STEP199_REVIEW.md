# Step199 Review — Atomic restore reservation and concurrent-restore race fence

Date: 04/11/2026

## Baseline and provenance
- Exact baseline: `KhoLinhKienAndroid-step198-process-death-recovery-20261104.zip`
- Baseline SHA-256: `281a725430bf817398d3000e711db94f324576242a44b2c06cbd65787ff1ca71`
- Step198 implementation is retained, including process-death recovery Worker, scheduler, Room journal Flow, and owner-scoped cancellation.

## Work completed
- Added `InventoryDao.beginLocalRestoreIfSafe()` as a Room `@Transaction`. It accepts only `LOCAL_ZIP` + `STAGING`, validates owner/restore IDs, rejects an existing journal ID, rejects any other active restore for that owner, and inserts using `OnConflictStrategy.ABORT`.
- `ZipRestore` now reserves the journal atomically before writing staged rows and marks `journalPersisted` only after the reservation succeeds.
- A concurrent losing attempt fails closed and its cleanup is limited to its own UUID staging directory; it does not remove or overwrite the winning restore journal/staging data.
- Kept Step198 recovery behavior intact. The recovery worker still resumes only confirmed, validated local sessions via the coordinator; incomplete ZIP extraction remains user-driven.
- Kept remote restore reservation's outbox-aware Room transaction, shared operation/snapshot gates, and the transactional Room apply precondition `SNAPSHOT_DOWNLOADED`.

## Verification
- `verify_step199_atomic_restore_reservation.py`: 13/13 PASS.
- Updated Step194 low-storage staging verifier: 17/17 PASS.
- Step198 process-death recovery verifier: 28/28 PASS.
- Step190–Step197 regression: 121/121 PASS.
- Combined targeted checks: 179? No. Correct total: 13 + 28 + 121 = 162/162 PASS. (Step194's 17 checks are already included in the Step190–197 regression total.)
- `python -m compileall -q tools`: PASS.

## Limitations
- Static/source-contract verification only; no runtime two-process race test, Kotlin compile, APK, Android device test, or process-death fault injection.
- No Gradle, live Supabase/Storage/RPC, data mutation, Room schema migration, or GitHub push.
