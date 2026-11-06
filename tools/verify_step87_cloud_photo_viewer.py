from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text(encoding='utf-8')
checks = {
    'viewer_component_exists': 'private fun PartPhotoPreview(photoPath: String, ownerId: String)' in source,
    'authenticated_in_memory_storage_download': '.downloadAuthenticated(photoPath)' in source,
    'owner_scoped_path_validation': 'segments.first() == ownerId' in source and 'segments.none { it.isBlank() || it == "." || it == ".." }' in source,
    'reject_absolute_or_backslash_cloud_path': "!photoPath.startsWith('/') && !photoPath.contains('\\\\')" in source,
    'no_cloud_photo_disk_cache': 'part_photo_cache' not in source and 'downloadAuthenticatedTo' not in source,
    'per_image_memory_limit': 'it.size in 1..MAX_PART_PHOTO_BYTES' in source,
    'bitmap_decode_sampled': 'inJustDecodeBounds = true' in source and 'inSampleSize = sample' in source,
    'preview_shown_in_part_dialog': 'PartPhotoPreview(part.photoPath, ownerId)' in source,
    'owner_id_passed_from_active_profile': 'PartDialog(p, profile.id,' in source,
    'download_runs_off_main_thread': 'withContext(Dispatchers.IO)' in source,
    'temporary_bytes_zeroed': 'imageBytes.fill(0)' in source,
    'cancellation_propagated': 'catch (cancelled: CancellationException)' in source,
}
for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP87_RESULT={sum(checks.values())}/{len(checks)}')
raise SystemExit(0 if all(checks.values()) else 1)
