from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
model = (ROOT / 'BACKUP_VERIFICATION_TRUST_MODEL.md').read_text(encoding='utf-8')
review = (ROOT / 'STEP208_REVIEW.md').read_text(encoding='utf-8')
progress = (ROOT / 'PROGRESS.md').read_text(encoding='utf-8')
uploader = (ROOT / 'app/src/main/java/vn/kholinhkien/SupabaseWeeklyBackupUploader.kt').read_text(encoding='utf-8')
sql = (ROOT / 'supabase/migrations/20261103150733_step187_weekly_backup_backend.sql').read_text(encoding='utf-8')
checks = {
 'official uploader has authenticated readback': 'verifyStoredObject(' in uploader and 'expectedSha256 = prepared.sha256' in uploader,
 'completion RPC follows readback path': 'complete_inventory_backup' in uploader,
 'backend labels client verification method': "'CLIENT_DOWNLOAD_SHA256'" in sql,
 'backend checks Storage metadata size': 'v_object_size <> v_backup.archive_size_bytes' in sql,
 'report explicitly says backend does not hash bytes': 'It does not download or hash object bytes' in model,
 'restore-time checks remain mandatory': 'Never allow an archive to be restored solely because its row says `VERIFIED`' in model,
 'server verifier requires separate approval and testing': 'explicit approval before changing the backend' in model,
 'Step208 recorded in progress': '## Step208 — Cloud backup verification trust model' in progress,
 'no deployment claimed': 'No Supabase schema, data, Storage object' in review,
}
for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'{sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()): raise SystemExit(1)
