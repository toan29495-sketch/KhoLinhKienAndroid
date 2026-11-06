from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text(encoding='utf-8')
checks = {
    'cloud_photo_preview_uses_authenticated_memory_download': '.downloadAuthenticated(photoPath)' in source,
    'no_part_photo_cache_directory': 'part_photo_cache' not in source,
    'no_cache_ttl_or_size_policy_remains': 'PART_PHOTO_CACHE_MAX_AGE_MS' not in source and 'PART_PHOTO_CACHE_MAX_BYTES' not in source,
    'no_cache_mutex_or_pruning_remains': 'partPhotoCacheMutex' not in source and 'prunePartPhotoCache' not in source,
    'cloud_image_size_bounded_to_20_mib': 'it.size in 1..MAX_PART_PHOTO_BYTES' in source,
    'local_image_size_bounded_before_and_during_read': 'file.length() in 1..MAX_PART_PHOTO_BYTES.toLong()' in source and 'require(total <= MAX_PART_PHOTO_BYTES)' in source,
    'temporary_download_buffer_zeroed': 'imageBytes.fill(0)' in source,
    'cloud_preview_never_writes_image_file': 'downloadAuthenticatedTo' not in source and 'File(context.cacheDir' not in source,
}
for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP88_RESULT={sum(checks.values())}/{len(checks)}')
raise SystemExit(0 if all(checks.values()) else 1)
