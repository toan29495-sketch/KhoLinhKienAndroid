# Step193 — Snapshot retry and account-switch fences (03/11/2026)

## Source base
- Continued from the exact Step192 source archive: `KhoLinhKienAndroid-step192-restore-resume-owner-guard-20261103.zip`.
- Step192 archive SHA-256: `4fb5372e1643c31e046649672e686e8dc90dcfeb486a1ab3c9fc0ecc742c740d`.
- No older ZIP or alternate source tree was used.

## Audit findings and changes
- Audited the ambiguous server-commit path. `COMMIT_UNKNOWN` persists before the RPC and retries the exact same `restoreId`; `COMMIT_CONFIRMED` short-circuits network commit retries. This behavior was retained.
- Audited cloud staging retries. `CLOUD_STAGING` is retained on cancellation/failure, and the same restore/chunk keys are retried. This behavior was retained.
- Hardened `RestoreCloudSnapshotReader`: rechecks the active Auth UID after reading the journal, before transitioning to `SNAPSHOT_DOWNLOADING`, immediately after each snapshot-read and server-verification RPC, before inserting each verified chunk, and before marking `SNAPSHOT_DOWNLOADED`.
- Hardened `RestoreCloudSnapshotApplier`: rechecks the exact active Auth UID after loading and validating the journal and immediately before entering the atomic Room snapshot replacement.
- Confirmed the coordinator and remote-feed worker use the shared snapshot gate around snapshot read/apply; the Room transaction validates the complete staged counts and row structure before clearing live owner rows.
- A failed/interrupted snapshot download remains `SNAPSHOT_DOWNLOADING`; retry clears only partial snapshot staging and fetches the immutable committed snapshot again. Live inventory remains untouched until the complete snapshot reaches `SNAPSHOT_DOWNLOADED` and the Room transaction validates it.

## Verification
- `tools/verify_step193_snapshot_retry_fences.py`: 16/16 PASS.
- Step192 verifier: 10/10 PASS.
- Step191 verifier: 16/16 PASS.
- Step190 verifier: 15/15 PASS.
- `python -m compileall -q tools`: PASS.
- ZIP integrity: verified after archive creation with `unzip -t` (see final artifact).

## Not verified / safety boundary
- Static source-contract checks only. No Android runtime process-kill, network-fault, low-storage, simultaneous worker/coordinator, or account-switch fault-injection test was run.
- No actual Supabase restore commit, Storage transfer, snapshot RPC, or destructive Room restore was executed.
- No Kotlin compilation, Android build, APK build/install/device test. Gradle intentionally skipped.
- No Supabase migration, remote data, local business data, Storage object, restore journal, outbox, or GitHub push was performed.
