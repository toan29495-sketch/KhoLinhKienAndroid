# Step194 — Low-storage, large ZIP and interrupted staging safety

Date: 03/11/2026

## Source base
- Exact source base: Step193 working tree / archive SHA-256 `f24ec4d840e4de1e11eb8b829daad69dc246a5f477fd20f3e9c4eb896ada9ac8`.
- No older ZIP was substituted. Step194 is an incremental source change.

## Changes
- ZIP extraction keeps the existing hard limits (10,002 entries, 300 MiB total uncompressed, per-entry limits) and now uses overflow-checked byte counters.
- Rechecks the active owner during each extraction read and checks the free-space reserve immediately before each write. Directory creation failures are surfaced instead of silently continuing.
- Durable photo copies preflight the full photo-copy size plus reserve, recheck free space for each chunk, flush and `fsync` the output before the restore journal is allowed to reference the staged photo directory.
- Tracks whether the durable `STAGING` journal was actually persisted. If cancellation/ENOSPC occurs before that point, only the exact newly created UUID directory is removed because there is no recovery journal. If the journal was persisted, durable staged rows/files are preserved for deterministic re-import; attempts to write `FAILED` are best-effort and never replace the original exception.
- The `STAGED` state remains gated behind exact part/movement row counts. ZIP staging does not clear or replace live inventory/history.

## Verification
- `verify_step194_low_storage_staging_safety.py`: 17/17 PASS.
- Regression: Step190 15/15, Step191 16/16, Step192 10/10, Step193 16/16 — Step190–194 total 74/74 checks across 5 scripts.
- Additional compatibility regressions: Step112 10/10, Step115 10/10, Step116 11/11, Step117 11/11, Step150 21/21.
- `python -m compileall -q tools`: PASS.

## Limits / not performed
- These are source-contract/static checks, not runtime fault-injection tests. No actual ENOSPC, low-storage device, large (90 MiB) ZIP, process kill/restart, filesystem crash, or concurrent restore test was executed.
- No Kotlin compilation, APK build, install, or phone test. Gradle was not run, per instruction.
- No Supabase migration/RPC/Storage operation, Room restore, inventory/history mutation, backup metadata mutation, or GitHub push.
- No claim that end-to-end backup/restore is ready. Android runtime verification remains outstanding.
