from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text(encoding='utf-8')
checks = {
    'no_disk_photo_cache_exists_to_clear': 'part_photo_cache' not in source,
    'no_cache_clear_routine_remains': 'clearAccountPhotoCache' not in source and 'clearPartPhotoCache' not in source,
    'signout_still_cancels_owner_workers_before_auth_signout': source.index('SyncWorkScheduler.cancelOwner(context, ownerId)') < source.index('authRepository.signOut()', source.index('val signOut: () -> Unit')),
    'signout_does_not_delete_pending_local_source_photos': 'pending_part_photos' not in source[source.index('val signOut: () -> Unit'):source.index('if (checkingSession)')],
    'signout_does_not_delete_cloud_objects': 'storage.from("part-images").delete' not in source,
}
for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP89_RESULT={sum(checks.values())}/{len(checks)}')
raise SystemExit(0 if all(checks.values()) else 1)
