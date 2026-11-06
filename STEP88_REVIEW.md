# Step88 – Bounded local cache for cloud part photos (2026-11-03)

## Scope
Limit accumulated downloaded cloud-photo cache without touching source photos, app data, backup files, or Storage objects.

## Change
- Added a dedicated cache policy for generated `.img` entries under `cacheDir/part_photo_cache` only.
- Set a global cache budget of 100 MiB and a 30-day inactivity retention window.
- Prune expired/empty cache entries and evict least-recently-used-by-access-time (oldest `lastModified`) entries until the total is within budget.
- Protect the current photo entry during pruning; refresh its access timestamp after a successful cache hit/download.
- Re-download a cache entry older than 30 days; retain the existing 20 MiB per-image bound.
- Serialize cache check/download/prune operations with a coroutine Mutex to avoid duplicate concurrent downloads and conflicting prune operations.
- No source local photo, Room record, backup, or Supabase Storage object is deleted. Only generated cache `.img` files are eligible.

## Verification
- `tools/verify_step88_photo_cache_policy.py`: 11/11 static checks passed.
- Re-ran Step49–72 (24/24 + SQLite smoke), Step73 5/5, Step74 5/5, Step75 7/7, Step76 7/7, Step77 7/7, Step78 7/7, Step79 12/12, Step80 16/16, Step81 20/20, Step82 14/14, Step83 16/16, Step84 20/20, Step85 13/13, Step86 7/7, Step87 10/10; all passed.

## Limits / not verified
- Static checks do not prove Android filesystem/cache behavior under process death, concurrent image decoding, or low-storage conditions.
- The mutex serializes cache acquisition/download/pruning; bitmap decoding occurs after acquisition, so a later cache prune could theoretically remove a file while another preview is decoding it. This is a narrow concurrency limitation to address with a pinned-file/reference-count strategy if runtime testing reproduces it.
- Cache is disposable by Android at any time; the viewer must continue to handle cache misses by downloading again.
- No Android runtime/device test, real Supabase Storage test, APK build, SQL deployment, GitHub push, or production operation was performed.
- Source lineage remains the reconstructed Step49–72 branch, not byte-for-byte recovery of the lost original source.
