# Step112 – Low-storage restore preflight and abandoned ZIP-temp cleanup (2026-11-03)

## Goal
Reduce avoidable restore failures on phones with little free storage and ensure interrupted ZIP extraction does not leave cache files accumulating indefinitely.

## Findings
- `ZipRestore` extracts an archive into a uniquely named `cacheDir/zip_restore_<UUID>` directory and already attempts recursive deletion in `finally`.
- A process kill bypasses `finally`, leaving that temporary directory behind. The next restore previously did not reclaim abandoned extraction folders.
- Restore also attempted to create the temporary workspace without first checking whether the cache volume had a minimally usable amount of free space.
- Durable `filesDir/restore_staging/<restoreId>` is a separate resumable workspace and must not be swept as disposable cache.

## Changes
- Before each new ZIP restore, inspect only direct children of the app cache directory with the dedicated `zip_restore_` prefix.
- Delete only canonical direct children of that cache root whose last-modified age is at least 24 hours. New/current directories and unrelated cache files are untouched.
- Check `StatFs(cacheDir).availableBytes` before creating a new temporary extraction folder; require at least 32 MiB free and provide a Vietnamese recovery message if not.
- Retain per-entry/aggregate ZIP limits, checksums, owner validation, durable staging, and the existing `finally` cleanup. Immediate cleanup remains best-effort; stale cleanup on a later attempt is the fallback if deletion fails or the process was killed.
- The cleanup does not touch durable restore staging, Room rows, live inventory, movement history, Outbox, or cloud Storage.

## Verification
- `tools/verify_step112_restore_temp_cleanup.py`: 10/10 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 + SQLite schema/atomic replacement smoke PASS.
- Every `tools/verify_step*.py` verifier through Step112 passed.
- Step112 ZIP integrity and SHA-256 verified.

## Limitations / not performed
- The 32 MiB check is only a minimum-start preflight; it cannot guarantee enough space for every archive. Actual ZIP extraction and durable staging remain bounded by existing byte limits and can still fail on ENOSPC; `finally` attempts cleanup.
- No Android device test with low-storage simulation, process kill, filesystem permission failure, or actual large archive.
- No Android compilation, APK build/install, PostgreSQL/Supabase staging, SQL deployment, Edge Function deployment, or GitHub push.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source.
