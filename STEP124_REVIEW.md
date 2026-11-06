# Step124 – Restore snapshot-applier cancellation propagation (2026-11-03)

## Baseline and scope
- Continued from the exact Step123 archive `KhoLinhKienAndroid-step123-zip-contract-validation-20261103.zip`.
- Verified baseline SHA-256: `007f186f44910b0077ffb583a52d2fbf04429cc04b2f2931627a494401443ec6`.
- `unzip -tq` passed before extraction. Work was performed in a fresh directory.
- Scope: end-to-end restore cancellation review, focusing on the boundary after the atomic Room snapshot apply and before best-effort staging cleanup.

## Finding and change
- `RestoreCloudSnapshotApplier.apply()` committed the owner-scoped Room snapshot and then wrapped the suspend `RestoreStagingLifecycle.cleanupCompleted()` call in `runCatching`.
- Because `runCatching` catches `Throwable`, it also swallowed coroutine `CancellationException`. A cancelled coroutine could therefore return an ordinary `localCleanupComplete=false` result instead of propagating cancellation.
- Added an explicit `catch (CancellationException) { throw cancelled }` before the ordinary `Exception` handler.
- Ordinary cleanup errors still return `false`; the already-committed inventory remains committed and the retained `COMPLETE` journal permits a later cleanup retry.
- No live inventory transaction, cloud protocol, ZIP format, owner checks, staging layout, or sync behavior was changed.

## Regression verifier maintenance
- Added `tools/verify_step124_restore_applier_cancellation.py` (9/9 PASS) to check cancellation propagation, transaction-before-cleanup ordering, retry marker, cursor guard and UI result flow.
- Updated `tools/verify_step110_backup_metadata_preview.py` to match the current `Long`-suffixed 1 MiB constant and to assert the actual UI promise plus independent full-restore checksum validation rather than a stale source comment. It now passes 20/20.
- Step113 cancellation verifier remains unchanged and passes 14/14.

## Verification results
- Step124 focused restore-applier cancellation: 9/9 PASS.
- Step123 backup/restore ZIP contract: 20/20 PASS.
- Step122 Central Directory: 15/15 PASS.
- Step121 read-back validation: 15/15 PASS.
- Step120 interrupted backup publication: 13/13 PASS.
- Step119 cloud-photo cache bounds: 12/12 PASS.
- Step118 local-photo owner isolation: 12/12 PASS.
- Step117 restore temp directory shape: 11/11 PASS.
- Step116 legacy temp isolation: 11/11 PASS.
- Step115 ZIP temp account isolation: 10/10 PASS.
- Step114 restore account isolation: 11/11 PASS.
- Step113 restore cancellation/resume: 14/14 PASS.
- Step112 restore temp cleanup: 10/10 PASS.
- Step111 backup/restore size parity: 14/14 PASS.
- Step110 backup metadata preview: 20/20 PASS after updating its stale assertions.
- Step109 fixed backup filename: 13/13 PASS.
- Step108 backup filename: 11/11 PASS.
- Step107 owner-local-photo isolation: 14/14 PASS.
- Step86 backup photo preflight: 7/7 PASS.
- Step49–72 sync regression: 24/24 plus SQLite schema/atomic replacement smoke PASS.
- Python tooling `compileall` passed; generated `__pycache__` and `.pyc` files were removed.

## Limits / not performed
- The Step124 change is Android/Supabase-dependent source; this environment has no Android SDK, adb or emulator. No Android framework compilation, emulator run, process-kill/lifecycle fault injection, APK build/install, or physical-phone test was performed.
- No live Supabase/PostgreSQL staging, SQL/Edge deployment, or GitHub push was performed.
- No Gradle command or Gradle installation was used.
- The checks above are source-level/static regressions. They do not prove runtime cancellation behavior on Android or server idempotency under a live connection.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source.
