# Step119 – Bounded temporary cloud-photo cache during backup

Date: 2026-11-03  
Baseline: Step118 checkpoint `KhoLinhKienAndroid-step118-backup-local-photo-owner-isolation-20261103.zip`.

## Finding
Backup preflight downloaded every referenced cloud photo into one temporary cache directory before opening the destination ZIP. Although each photo was individually limited to 20 MiB and the final ZIP had a 300 MiB uncompressed limit, the temporary download phase had no cumulative cache bound or available-space reserve.

## Changes
- Before each cloud download, require available space in the temporary cache directory for one maximum-size 20 MiB photo plus a 32 MiB reserve.
- Immediately after each download, require a regular non-empty file no larger than 20 MiB.
- Track cumulative temporary cloud-photo bytes and reject totals above the existing 300 MiB aggregate limit.
- Keep temporary workspace cleanup in `finally`.
- Local photos continue to be read in place; they are not moved, deleted, or counted as cloud cache bytes.
- ZIP v2 format, owner validation, checksums, per-entry limits, and archive aggregate accounting remain unchanged.

## Verification
- `tools/verify_step119_backup_cloud_photo_cache_bounds.py`: 12/12 PASS.
- Focused regressions: Step118 12/12; Step107 14/14; Step111 14/14; Step109 13/13; Step110 20/20; Step112–117 focused checks; Step49–72 sync/SQLite regression.

## Limitations
- Static/source verification only. No Android runtime/cache-pressure test, Android compilation, APK build/install, or physical-device test.
- No live Supabase/PostgreSQL staging, SQL or Edge Function deployment, or GitHub push.
- A storage transfer can still transiently exceed the expected size before the post-download check if the remote object is unexpectedly oversized; the preflight reserve mitigates but cannot fully eliminate this limitation without a bounded streaming download API.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source.
