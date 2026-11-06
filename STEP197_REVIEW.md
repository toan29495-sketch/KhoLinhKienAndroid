# Step197 — Serialize WorkManager sync workers with restore lifecycle (03/11/2026)

## Scope
Continue from the exact Step196 source archive. Prevent normal WorkManager sync from racing a ZIP import/resume/abort or a cloud snapshot apply.

## Changes
- `SyncPullWorker.doWork()` now attempts the shared process-wide `RestoreOperationGate` before any DAO cursor read, network request, feed mapping, row application, or cursor advancement. If the gate is occupied by restore, it returns `Result.retry()` without touching DAO/network state.
- The pull body runs in a dedicated gate-held method. `finally` always releases the mutex, including success, retry, exceptions, and coroutine cancellation. Existing cancellation propagation and `RestoreSnapshotGate` protection remain intact.
- `SyncOutboxWorker` now uses the same gate before checking active restore or calling `SyncOutboxSender`. This closes the complementary race where restore could check an empty outbox while a sender was about to claim/send an operation.
- Busy workers use WorkManager retry/backoff; no cursor or outbox operation is started while restore owns the gate. Existing sender idempotency/claim logic is unchanged.
- Lock order is consistent: restore coordinator/lifecycle owns `RestoreOperationGate` before acquiring `RestoreSnapshotGate`; pull worker owns `RestoreOperationGate` before any snapshot gate acquisition. No reverse acquisition was introduced.

## Verification
- `verify_step197_sync_restore_serialization.py`: 17/17 PASS.
- Regression Step190–197: 121/121 PASS.
- Compatibility Step112, 115–117, 150: 63/63 PASS.
- Combined selected regression: 184/184 PASS.
- `python -m compileall -q tools`: PASS.
- ZIP archive integrity: PASS.

## Limits
These are static source-contract checks. No Kotlin compilation, WorkManager runtime scheduling test, process-death/concurrency fault injection, network/RPC test, Android build/APK/device test. The mutex is process-local and assumes WorkManager runs in the app's normal process; it is not a cross-process lock. No Gradle was run.

## Data and deployment
No Supabase calls/migrations, no Room/user-data mutation, no Storage changes, no restore execution, and no GitHub push.
