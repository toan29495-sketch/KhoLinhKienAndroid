# Step113 – Restore cancellation and process-interruption safety (2026-11-03)

## Goal
Ensure coroutine cancellation caused by logout, lifecycle shutdown, or Android process/work cancellation is not misclassified as an ordinary restore failure. Preserve durable checkpoints so the same owner can safely resume after reopening the app.

## Findings
- Several restore phase handlers caught `Exception`, which also includes Kotlin coroutine `CancellationException`.
- ZIP staging could try to mark the journal `FAILED` on cancellation even though a durable `STAGING` journal and some committed Room staging batches may already exist.
- Cloud chunk sender and snapshot reader could write ordinary error metadata after cancellation rather than simply leaving their resumable phase marker intact.
- Committer correctly wrote `COMMIT_UNKNOWN` before invoking the server RPC, but its broad catch could still perform an unnecessary suspend write after cancellation. The server may already have committed even if the client stopped waiting.
- Coordinator's `runCatching` around suspend cleanup could swallow cancellation and report a normal incomplete-cleanup result.

## Changes
- `ZipRestore`: explicitly rethrows `CancellationException` before the ordinary exception handler. The durable `STAGING` journal and any committed staged batches remain available; a later import of the same archive can safely rebuild the incomplete stage through the existing owner/restoreId checks.
- `RestoreCloudSessionSender`: cancellation leaves `CLOUD_STAGING` untouched. Retrying the same restore/chunk keys relies on the existing idempotent server staging contract; immutable payloads are not changed.
- `RestoreCloudCommitter`: cancellation propagates while preserving the already-persisted `COMMIT_UNKNOWN` marker. A later resume reissues the same idempotent commit RPC and resolves whether the server committed.
- `RestoreCloudSnapshotReader`: cancellation leaves `SNAPSHOT_DOWNLOADING`; a later retry clears only the partial snapshot staging buckets and re-downloads the verified snapshot. Source payload and live inventory remain untouched.
- `RestoreCloudCoordinator`: cancellation is no longer swallowed by best-effort completed-journal cleanup. Non-cancellation cleanup errors still return `localCleanupComplete=false`; the retained COMPLETE journal allows another cleanup attempt.

## Verification
- `tools/verify_step113_restore_cancellation_resume.py`: 14/14 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 plus SQLite schema/atomic replacement smoke PASS.
- All 41 `tools/verify_step*.py` verifiers passed.
- Python tooling syntax check (`compileall`) passed.
- Step113 ZIP integrity and SHA-256 verified.

## Limitations / not performed
- These are source-level cancellation and resume checks; no Android lifecycle/process-kill/logout fault-injection was run.
- No Android compilation, APK build/install, device test, PostgreSQL/Supabase staging, SQL deployment, Edge Function deployment, or GitHub push.
- Server idempotency behavior is relied upon from the existing source contract but was not re-tested against live Supabase in this step.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source.
