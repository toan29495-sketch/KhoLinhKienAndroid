#!/usr/bin/env python3
"""Step186 source-contract checks for reinstall-safe owner-scoped Cloud catalog."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/vn/kholinhkien"
source = (APP / "WeeklyBackupCloudCatalog.kt").read_text()
candidate = (ROOT / "supabase/candidates/step160_weekly_backup_backend_candidate.sql").read_text()
worker = (APP / "WeeklyBackupWorker.kt").read_text()
checks = [
    ("catalog calls only owner-scoped list RPC", 'rpc("list_my_inventory_backups")' in source and 'list_inventory_backup_status_admin' not in source),
    ("requires canonical owner UUID", 'requireCanonicalUuid(ownerId, "ownerId")' in source),
    ("checks authenticated owner before RPC", source.index('currentUserOrNull()?.id == ownerId') < source.index('rpc("list_my_inventory_backups")')),
    ("checks authenticated owner again after RPC", source.rindex('currentUserOrNull()?.id == ownerId') > source.index('rpc("list_my_inventory_backups")')),
    ("parses whole response before exposing rows", 'Json.parseToJsonElement(raw.toString()).jsonArray.map' in source),
    ("rejects duplicate backup IDs", 'backupId' in source and 'toSet().size == rows.size' in source),
    ("validates canonical backup UUID", 'UUID.fromString(id).toString() == id' in source),
    ("validates exact owner/backup object path", 'path == "$ownerId/$id.zip"' in source),
    ("validates known backup kinds", 'WEEKLY_AUTO' in source and 'MONTHLY_AUTO' in source and 'PRE_UPDATE' in source),
    ("retains distinct PENDING/VERIFIED/FAILED/DELETED states", all(x in source for x in ['PENDING, VERIFIED, FAILED, DELETED'])),
    ("validates archive size cap", '256L * 1024L * 1024L' in source),
    ("validates SHA-256 syntax", 'Regex("[a-f0-9]{64}")' in source),
    ("validates nonnegative snapshot cursor", 'cursor >= 0L' in source),
    ("validates counts against backend limits", '500_000' in source and '1_000_000' in source),
    ("created_at must be nonblank", 'createdAt.isNotBlank()' in source),
    ("VERIFIED requires verified_at", 'status != Status.VERIFIED || !verifiedAt.isNullOrBlank()' in source),
    ("DELETED requires deleted_at", 'status != Status.DELETED || !deletedAt.isNullOrBlank()' in source),
    ("restore eligibility is VERIFIED and not deleted", 'status == Status.VERIFIED && verifiedAt != null && deletedAt == null' in source),
    ("catalog is read-only; no storage download or mutation", all(x not in source for x in ['storage.from(', 'downloadAuthenticatedTo', 'delete(', 'upload('])),
    ("catalog does not auto-integrate into worker", 'WeeklyBackupCloudCatalog' not in worker),
    ("backend RPC remains candidate and owner-scoped", 'function public.list_my_inventory_backups()' in candidate and 'where b.owner_id = (select auth.uid())' in candidate),
    ("does not infer remote listing availability from local worker", 'list_my_inventory_backups' not in worker),
]
failed=[]
for name, ok in checks:
    print(("PASS" if ok else "FAIL") + " - " + name)
    if not ok: failed.append(name)
print(f"STEP186: {len(checks)-len(failed)}/{len(checks)} PASS")
if failed: raise SystemExit(1)
