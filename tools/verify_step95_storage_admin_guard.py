from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STORAGE = ROOT / 'supabase/migrations_pending_reconciliation/202611030007_lock_down_part_image_storage_scope.sql'
ADMIN = ROOT / 'supabase/functions/admin-users/index.ts'
UPLOAD = ROOT / 'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt'
RESTORE = ROOT / 'app/src/main/java/vn/kholinhkien/RestorePhotoUploadCoordinator.kt'

def main():
    s = STORAGE.read_text().lower()
    a = ADMIN.read_text()
    u = UPLOAD.read_text()
    r = RESTORE.read_text()
    checks = {
        'storage guard is restrictive': 'as restrictive' in s,
        'storage guard covers all operations': 'for all' in s,
        'storage guard enforces authenticated role': 'to authenticated' in s,
        'storage guard scopes first path segment to auth uid': "coalesce((storage.foldername(name))[1], '') = (select auth.uid())::text" in s,
        'storage guard requires active profile': 'public.is_active_profile()' in s,
        'storage guard applies to bucket only': "bucket_id <> 'part-images'" in s,
        'migration is transactional': 'begin;' in s and s.strip().endswith('commit;'),
        'normal upload path begins with owner id': 'val remotePath = "$ownerId/$partSyncId/$operationId.$extension"' in u,
        'restore upload path begins with owner id': '"$ownerId/restore-$restoreId/$digest.$extension"' in r,
        'admin function verifies bearer user': 'callerClient.auth.getUser(tokenMatch[1])' in a,
        'admin function requires active admin': 'callerProfile.role !== "admin" || callerProfile.is_active !== true' in a,
        'admin function rejects non-object body': 'typeof payload !== "object" || Array.isArray(payload)' in a,
        'admin function allowlists actions': '["create", "reset_password", "lock", "unlock"]' in a,
        'admin function rejects unexpected fields': 'Object.keys(input).some((key) => !allowedKeys.has(key))' in a,
        'create always assigns ktv role': 'role: "ktv"' in a,
    }
    for label, ok in checks.items():
        print(('PASS' if ok else 'FAIL') + ' ' + label)
    print(f'{sum(checks.values())}/{len(checks)} checks passed')
    if not all(checks.values()):
        raise SystemExit(1)

if __name__ == '__main__':
    main()
