# Step89 – Clear private cloud-photo cache when ending an account session (2026-11-03)

## Scope
Reduce cross-account data residue on a shared Android device by removing downloaded private cloud-photo cache entries when the active session ends or startup session validation fails. Do not touch source photos, pending uploads, Room data, backups, or cloud Storage objects.

## Finding
Step87/88 stored authenticated cloud photos in a device-wide app cache directory keyed by object-path hash. Cloud paths were owner-prefixed and checked before download, so another owner could not request the previous owner's path through the viewer. However, cache entries remained on disk after logout and after an invalid restored session was discarded. This left unnecessary private image bytes on a shared device.

## Change
- Added `clearPartPhotoCache()` that deletes only regular `.img` files in the dedicated photo-cache directory.
- Added `clearAccountPhotoCache(context)` and serialized it with the same Mutex used by download/prune, preventing deletion during an active cache acquisition.
- Manual logout now clears cloud-photo cache after attempting Supabase sign-out and before removing the active profile state.
- Startup session/profile validation failure also clears the cloud-photo cache after sign-out attempt.
- No local source photo, pending upload, Room row, backup, or Supabase Storage object is deleted.

## Verification
- `tools/verify_step89_logout_photo_cache.py`: 8/8 static checks passed.
- Re-ran Step49–72 source suite (24/24 + SQLite smoke) and Step73–88 regression scripts; all passed.
- ZIP integrity and SHA-256 verified.

## Limits / not verified
- Cache clearing is best-effort filesystem cleanup; Android may independently clear cache at any time.
- A cloud photo already decoded into a Compose `Bitmap` may remain in memory until the preview composable is disposed/garbage-collected; this change clears disk cache, not secure memory or screenshots.
- Supabase sign-out failure does not prevent local UI logout/cache cleanup; session revocation behavior depends on the auth SDK/server.
- No Android runtime/shared-device test, real Supabase session test, Room instrumentation, APK build, SQL deployment, GitHub push, or production operation was performed.
- Source lineage remains the reconstructed Step49–72 branch, not byte-for-byte recovery of the lost original source.
