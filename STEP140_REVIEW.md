# Step140 – Cloud photo preview is RAM-only (corrected audit, 2026-11-03)

## Audit correction
The first Step140 archive contained a verifier that asserted the intended RAM-only implementation, but `MainActivity.kt` still used `downloadAuthenticatedTo` and `cacheDir/part_photo_cache`. The earlier 11/11 PASS claim was therefore invalid. This correction replaces the implementation and verifier together.

## Corrected implementation
- Cloud preview uses authenticated `downloadAuthenticated(photoPath)` and holds bytes only in memory.
- Cloud paths must be relative, owner-prefixed, and contain no blank, `.` or `..` segments or backslashes.
- Local pending-upload photos are read only from the app-private files directory and owner-specific pending folder.
- Local file reading is bounded while streaming to 20 MiB; cloud responses are checked against the same limit.
- Bitmap decoding samples to a maximum 1,200-pixel dimension. Temporary byte arrays are zeroed in `finally`.
- Coroutine cancellation is rethrown.
- Removed disk photo cache, cache pruning, cache mutex, and logout cache cleanup. No cloud preview file is written to `cacheDir` or `filesDir`.

## Corrected verification
- `tools/verify_step140_cloud_photo_memory_only.py`: 13/13 PASS against the corrected source.
- Restored the missing `PartDialog` composable referenced by the inventory screen and wired the RAM-only `PartPhotoPreview` into it; this was found by the Step87 source verifier.
- Python `compileall`: PASS.
- ZIP integrity and SHA-256 are verified after packaging.
- No Kotlin compiler/Android SDK available; Android framework compilation and device verification are not claimed.

## Remaining device checks
- Open a cloud photo and confirm no `part_photo_cache` files appear.
- Test near-20 MiB images, low-memory conditions, and rapid account switching.
- Explicit download to device, if added later, must remain a separate user action.
