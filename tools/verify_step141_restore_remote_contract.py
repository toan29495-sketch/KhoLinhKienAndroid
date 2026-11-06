from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SENDER = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudSessionSender.kt').read_text(encoding='utf-8')
COMMIT = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudCommitter.kt').read_text(encoding='utf-8')
READER = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudSnapshotReader.kt').read_text(encoding='utf-8')
PULL = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text(encoding='utf-8')
APPLIER = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudSnapshotApplier.kt').read_text(encoding='utf-8')
MIGRATIONS = (ROOT / 'supabase/REMOTE_MIGRATION_MANIFEST.md').read_text(encoding='utf-8')
ACTIVE_FN = (ROOT / 'supabase/functions/admin-users/index.ts').read_text(encoding='utf-8')
CANDIDATE_FN = (ROOT / 'supabase/functions/admin-users-v4-candidate/index.ts').read_text(encoding='utf-8')
PHOTO = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text(encoding='utf-8')

checks = {
    'restore session cleanup occurs before begin': SENDER.index('cleanup_my_inventory_restore_staging') < SENDER.index('"begin_inventory_restore"'),
    'restore sender binds authenticated owner': 'require(userId == ownerId)' in SENDER,
    'restore sender chunks are capped at 200 rows': 'const val MAX_ROWS_PER_CHUNK = 200' in SENDER,
    'restore sender caps JSON chunk bytes': 'const val MAX_CHUNK_BYTES = 900 * 1024' in SENDER,
    'restore commit persists ambiguous COMMIT_UNKNOWN before RPC': COMMIT.index('status = "COMMIT_UNKNOWN"') < COMMIT.index('"commit_inventory_restore"'),
    'restore commit validates manifest and cursor': 'manifestSha256' in COMMIT and 'cursor != null && cursor > 0L' in COMMIT,
    'snapshot reader verifies returned chunks': 'verify_inventory_restore_snapshot_chunk' in READER and 'verified' in READER,
    'pull worker validates RESTORE_COMMIT control marker': '"RESTORE_COMMIT"' in PULL and 'manifest_sha256' in PULL,
    'snapshot applier remains owner-scoped': 'ownerId' in APPLIER and 'journal.ownerId == ownerId' in APPLIER,
    'restore commit marker applies snapshot only for owner feed and advances separate Admin cursor': 'if (!isAdmin)' in PULL and 'RestoreCloudSnapshotReader(applicationContext, dao).download(ownerId, restoreId)' in PULL and 'Admin-global feed' in PULL and 'advances that separate cursor' in PULL,
    'cloud photo preview uses in-memory authenticated download': '.downloadAuthenticated(photoPath)' in PHOTO,
    'cloud photo preview does not use disk cache': 'part_photo_cache' not in PHOTO and 'downloadAuthenticatedTo' not in PHOTO,
    'remote migration manifest warns against blind replay': 'Do not replay files' in MIGRATIONS and 'not executable SQL' in MIGRATIONS,
    'deployed Admin function remains v3 source pending deploy': 'app_admins' not in ACTIVE_FN,
    'Admin v4 candidate checks Admin link explicitly': '.from("app_admins")' in CANDIDATE_FN and 'if (!adminLink)' in CANDIDATE_FN,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'STEP141_RESULT={len(checks)-len(failed)}/{len(checks)}')
if failed:
    raise SystemExit(1)
