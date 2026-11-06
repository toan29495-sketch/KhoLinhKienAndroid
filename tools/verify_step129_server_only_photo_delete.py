from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = (ROOT / 'supabase/migrations_pending_reconciliation/202611030014_deny_authenticated_part_image_deletes.sql').read_text()
OWNER_POLICY = (ROOT / 'supabase/migrations_pending_reconciliation/202611020002_part_image_owner_policies.sql').read_text()
OWNER_GUARD = (ROOT / 'supabase/migrations_pending_reconciliation/202611030007_lock_down_part_image_storage_scope.sql').read_text()
SENDER = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text()
RESTORE = (ROOT / 'app/src/main/java/vn/kholinhkien/RestorePhotoUploadCoordinator.kt').read_text()
APP_SOURCES = [p.read_text(errors='ignore') for p in (ROOT / 'app/src/main').rglob('*.kt')]
FUNCTIONS = ROOT / 'supabase/functions'
SQL = ROOT / 'supabase'
CONTRACT = (ROOT / 'docs/PHOTO_CLOUD_GARBAGE_COLLECTION_CONTRACT.md').read_text()

migration = MIGRATION.lower()
checks = {
    'migration is transactional and repeatable': migration.startswith('-- step129') and 'begin;' in migration and migration.strip().endswith('commit;') and 'drop policy if exists part_images_no_authenticated_delete' in migration,
    'legacy authenticated owner-delete policy is explicitly removed': 'drop policy if exists part_images_owner_delete on storage.objects' in migration,
    'replacement delete policy is restrictive': 'create policy part_images_no_authenticated_delete' in migration and 'as restrictive' in migration,
    'policy is limited to DELETE rather than changing read/upload/update': 'for delete' in migration and 'for all' not in migration,
    'policy covers both anonymous and authenticated client roles': 'to anon, authenticated' in migration,
    'predicate denies DELETE only for part-images bucket': "using (bucket_id <> 'part-images')" in migration,
    'other Storage buckets are not blocked by this policy': "bucket_id <> 'part-images'" in migration and 'using (false)' not in migration,
    'migration contains no object deletion statement': 'delete from storage.objects' not in migration and '.remove(' not in migration,
    'existing owner folder guard remains restrictive and active-profile scoped': 'as restrictive' in OWNER_GUARD.lower() and 'public.is_active_profile()' in OWNER_GUARD and 'storage.foldername(name)' in OWNER_GUARD,
    'historical owner policy migration remains unchanged for upgrade ordering': 'create policy part_images_owner_delete' in OWNER_POLICY and 'for delete' in OWNER_POLICY.lower(),
    'no Android source invokes part-image Storage remove': not any('.storage.from("part-images").remove' in source for source in APP_SOURCES),
    'no Android source invokes authenticated cloud-photo deletion': not any('deleteAuthenticated' in source for source in APP_SOURCES),
    'no current Edge Function deletes part-image objects': not any('storage.from("part-images").remove' in p.read_text(errors='ignore') for p in FUNCTIONS.rglob('*') if p.is_file()),
    'no SQL migration directly deletes part-image objects': not any('delete from storage.objects' in p.read_text(errors='ignore').lower() for p in SQL.rglob('*.sql')),
    'contract explicitly defers collector implementation': 'no collector is implemented or deployed' in CONTRACT.lower() and 'does not implement a candidate table' in CONTRACT.lower(),
    'contract requires live-reference recheck before future deletion': 'search all live parts, retained operation payloads/results, retained change-feed payloads, restore staging' in CONTRACT,
    'contract forbids service-role credentials in the APK': 'never embed a service-role key in the apk' in CONTRACT.lower(),
    'contract requires dry-run and quarantine before deletion': 'dry-run/report mode first' in CONTRACT and 'quarantined' in CONTRACT.lower(),
}

for label, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + label)
print(f'STEP129_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)
