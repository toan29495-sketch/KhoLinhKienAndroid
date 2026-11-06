# Step181 Review — weekly backup snapshot consistency

## Scope
Audit that the weekly archive is assembled from one coherent, owner-scoped Room snapshot, and that a snapshot is refused while local writes remain unsynchronized or a restore is active.

## Findings
- `InventoryDao.captureAutomaticBackupSnapshot(ownerId)` is a Room `@Transaction`. It checks for an active restore and unresolved outbox rows (`PENDING`, `FAILED`, `IN_FLIGHT`, `BLOCKED`) before reading, then reads owner-scoped parts, movements and the sync cursor, and repeats both safety checks before returning.
- The parts, movements and cursor are therefore read from the same SQLite transaction snapshot. The ZIP builder receives only the returned snapshot values; it does not re-query live tables while writing the archive. Manifest counts and cursor also come from that same captured object.
- Local stock changes, pulled part updates and pulled movement inserts are transaction-scoped. Pull/restore paths enforce the active-restore fence, while local unsent changes are represented by the outbox and block automatic snapshot capture.
- If snapshot capture returns null, preparation aborts before creating archive bytes; the PREPARING journal remains recoverable under its existing backupId.
- A SQLite WAL two-connection smoke test confirmed a reader transaction sees a stable view while a concurrent writer commits; the new transaction sees the committed value after the reader ends.

## Verification
- `verify_step181_weekly_backup_snapshot_consistency.py`: 17/17 PASS.
- Regression Steps158–181: 24 verifier scripts, 398/398 checks PASS.
- Python `compileall`: PASS.
- ZIP integrity and SHA-256 checked after packaging.

## Limits / non-actions
- Source-contract checks and a Python SQLite transaction smoke test only; not a Room instrumentation test or Android concurrency test.
- No Kotlin/Android compilation, APK build/install, device test, WorkManager runtime test, or live Supabase/TUS/RLS/staging test.
- No Room schema change (v14 remains), no SQL/backend candidate, server, account, inventory, history, or Storage mutation. No schedule activation and no GitHub push.
- Gradle intentionally not used.
