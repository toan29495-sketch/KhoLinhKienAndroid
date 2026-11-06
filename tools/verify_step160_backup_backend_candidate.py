#!/usr/bin/env python3
"""Static safety/contract verifier for the unapplied Step160 SQL candidate."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SQL = (ROOT / "supabase/candidates/step160_weekly_backup_backend_candidate.sql").read_text()
DOC = (ROOT / "docs/STEP160_BACKUP_BACKEND_CONTRACT.md").read_text()
checks = []

def check(label, condition):
    checks.append((label, bool(condition)))

check("candidate explicitly marked not applied", "CANDIDATE ONLY" in SQL and "not applied" in DOC.lower())
check("transaction wrapped", SQL.strip().startswith("-- STEP160") and "begin;" in SQL.lower() and SQL.rstrip().lower().endswith("commit;"))
check("dedicated private bucket", re.search(r"'inventory-backups'\s*,\s*'inventory-backups'\s*,\s*false", SQL) is not None)
check("bucket capped at 256 MiB", "268435456" in SQL)
check("ZIP MIME allow-list", "array['application/zip']::text[]" in SQL)
check("existing bucket mismatch aborts", "INVENTORY_BACKUP_BUCKET_CONFIG_MISMATCH" in SQL)
check("metadata table owner FK", re.search(r"owner_id uuid not null references auth\.users\(id\)", SQL) is not None)
check("metadata RLS enabled", "alter table public.inventory_backup_objects enable row level security" in SQL.lower())
check("metadata direct grants revoked", re.search(r"revoke all on table public\.inventory_backup_objects from public, anon, authenticated", SQL, re.I) is not None)
check("owner-only insert policy", "create policy inventory_backup_owner_insert" in SQL and "(storage.foldername(name))[1] = (select auth.uid())::text" in SQL)
check("upload requires registered pending metadata", "public.can_upload_inventory_backup_object(name)" in SQL and "b.status = 'PENDING'" in SQL)
check("pending begin retry refreshes 24h authorization", "upload_authorized_until = now() + interval '24 hours'" in SQL and "b.upload_authorized_until >= now()" in SQL)
check("upload registration helper exists before policy", SQL.index("create or replace function public.can_upload_inventory_backup_object") < SQL.index("create policy inventory_backup_owner_insert"))
check("active profile required for upload", "public.is_active_profile()" in SQL)
check("UUID zip object filename enforced", "storage.filename(name) ~" in SQL and "\\.zip$" in SQL)
check("owner-only list and authenticated download", "storage.allow_any_operation(array[" in SQL and "object.list" in SQL and "storage.object.get_authenticated" in SQL)
check("no client update policy", not re.search(r"create\s+policy[^;]+for\s+update", SQL, re.I | re.S))
check("no client delete policy", not re.search(r"create\s+policy[^;]+for\s+delete", SQL, re.I | re.S))
check("owner/path derived from auth uid", "v_owner uuid := (select auth.uid())" in SQL and "v_path := v_owner::text || '/' || p_backup_id::text || '.zip'" in SQL)
check("begin retry is idempotent but rejects metadata mismatch", "on conflict (backup_id) do nothing" in SQL and "BACKUP_ID_METADATA_CONFLICT" in SQL)
check("begin validates size and digest", "p_archive_size_bytes > 268435456" in SQL and "p_sha256 !~ '^[a-f0-9]{64}$'" in SQL)
check("complete locks owner's metadata row", "where b.backup_id = p_backup_id and b.owner_id = v_owner" in SQL and "for update" in SQL)
check("complete checks storage owner and size", "o.owner_id = v_owner::text" in SQL and "v_object_size <> v_backup.archive_size_bytes" in SQL)
check("client checksum method explicitly labeled", "CLIENT_DOWNLOAD_SHA256" in SQL and "PostgreSQL does not recompute" in DOC)
check("own list is owner scoped", "where b.owner_id = (select auth.uid())" in SQL)
check("admin list requires active Admin link", "not public.is_app_admin()" in SQL and "not public.is_active_profile()" in SQL)
admin_fn = SQL.split("create or replace function public.list_inventory_backup_status_admin()", 1)[1]
admin_fn = admin_fn.split("revoke all on function", 1)[0]
check("admin metadata omits storage path", "storage_path" not in admin_fn and "sha256" not in admin_fn)
check("RPC execute revoked then granted only authenticated", SQL.count("revoke all on function public.") == 5 and SQL.count("to authenticated;") == 5)
check("no service role secret or grant", "service_role" not in SQL.lower() and "service_role" not in DOC.lower())
check("retention deletion intentionally deferred", "retention deletion is deliberately not implemented" in DOC.lower())
check("no migration apply/deploy claim", "not applied to supabase" in DOC.lower() and "no migration application" in DOC.lower())

for label, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'}: {label}")
print(f"RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS")
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
