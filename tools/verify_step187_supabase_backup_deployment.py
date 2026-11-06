from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
checks = []
def check(name, condition):
    checks.append((name, bool(condition)))

base = (ROOT / "supabase/migrations/20261103150733_step187_weekly_backup_backend.sql").read_text()
fix = (ROOT / "supabase/migrations/20261103150823_step187_01_fix_backup_registration_ambiguity.sql").read_text()
deny = (ROOT / "supabase/migrations/20261103151110_step187_02_deny_direct_backup_metadata_access.sql").read_text()
uploader = (ROOT / "app/src/main/java/vn/kholinhkien/SupabaseWeeklyBackupUploader.kt").read_text()
catalog = (ROOT / "app/src/main/java/vn/kholinhkien/WeeklyBackupCloudCatalog.kt").read_text()
manifest = (ROOT / "supabase/REMOTE_MIGRATION_MANIFEST.md").read_text()
review = (ROOT / "STEP187_REVIEW.md").read_text()
progress = (ROOT / "PROGRESS.md").read_text()

check("private backup bucket", "'inventory-backups'" in base and "268435456" in base and "application/zip" in base and "public is distinct from false" in base)
check("bucket mismatch fails closed", "INVENTORY_BACKUP_BUCKET_CONFIG_MISMATCH" in base)
check("backup metadata table", "create table public.inventory_backup_objects" in base)
check("metadata table RLS enabled", "alter table public.inventory_backup_objects enable row level security" in base)
check("metadata table client grants revoked", "revoke all on table public.inventory_backup_objects from public, anon, authenticated" in base)
check("owner derived from auth.uid", "v_owner uuid := (select auth.uid())" in base)
check("active profile required", base.count("public.is_active_profile()") >= 4)
check("Storage insert owner policy", "inventory_backup_owner_insert" in base and "public.can_upload_inventory_backup_object(name)" in base)
check("Storage select owner policy", "inventory_backup_owner_select" in base and "storage.object.get_authenticated" in base)
check("no backup object update/delete policy", "create policy inventory_backup_owner_update" not in base and "create policy inventory_backup_owner_delete" not in base)
check("begin RPC deployed", "create or replace function public.begin_inventory_backup" in base)
check("complete RPC deployed", "create or replace function public.complete_inventory_backup" in base)
check("owner catalog RPC deployed", "create or replace function public.list_my_inventory_backups" in base)
check("admin status RPC requires admin", "not public.is_app_admin()" in base)
check("anon execute revoked", "from public, anon, authenticated" in base)
check("authenticated execute granted", "to authenticated" in base)
check("runtime ambiguity fixed by constraint", "on conflict on constraint inventory_backup_objects_pkey" in fix.lower())
check("direct metadata access denied by restrictive policy", "as restrictive" in deny.lower() and "using (false)" in deny.lower() and "with check (false)" in deny.lower())
check("uploader uses correct bucket", 'const val BUCKET = "inventory-backups"' in uploader)
check("uploader max archive matches bucket", "268435456L" in uploader)
check("catalog uses owner RPC", 'rpc("list_my_inventory_backups")' in catalog)
check("remote migration manifest records Step187", "20261103150733_step187_weekly_backup_backend" in manifest and "20261103150823_step187_01_fix_backup_registration_ambiguity" in manifest and "20261103151110_step187_02_deny_direct_backup_metadata_access" in manifest)
check("review records real authenticated tests", "ADMIN_REQUIRED" in review and "PENDING" in review)
check("progress records remaining unverified gates", "no real TUS upload/download" in progress)

for name, ok in checks:
    print(("PASS" if ok else "FAIL") + " | " + name)
print(f"RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS")
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
