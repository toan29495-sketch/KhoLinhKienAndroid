#!/usr/bin/env python3
"""Step185 source-contract checks for uninstall/reinstall backup safety."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/vn/kholinhkien"
worker = (APP / "WeeklyBackupWorker.kt").read_text()
manager = (APP / "BackupManager.kt").read_text()
recovery = (APP / "WeeklyBackupRecovery.kt").read_text()
uploader = (APP / "SupabaseWeeklyBackupUploader.kt").read_text()
lifecycle = (APP / "WeeklyBackupLifecycle.kt").read_text()
candidate = (ROOT / "supabase/candidates/step160_weekly_backup_backend_candidate.sql").read_text()

checks = [
    ("worker uses local active journal as resume source", "dao.activeWeeklyBackup(ownerId)" in worker),
    ("missing journal receives a fresh UUID rather than guessed remote identity", "active, UUID.randomUUID().toString()" in worker),
    ("local prepare requires durable owner-scoped journal", "dao.beginWeeklyBackup(" in manager and "ownerId = ownerId" in manager),
    ("READY/UPLOADING requires original local archive bytes", 'journal.state == "READY" || journal.state == "UPLOADING"' in manager and "archive.isFile" in manager),
    ("recovery is explicitly local-only and never touches Cloud", "never contacts Supabase" in recovery and "never deletes remote objects" in recovery),
    ("uploader requires local journal before remote upload", "dao.weeklyBackup(prepared.ownerId, backupId)" in uploader and "Không tìm thấy journal bền vững" in uploader),
    ("uploader verifies downloaded remote bytes before VERIFIED", "verifyStoredObject" in uploader and "markWeeklyBackupVerified" in uploader),
    ("new backup identity only when no local active journal", "if (active == null) return proposedBackupId" in lifecycle),
    ("backend candidate has owner-scoped remote listing RPC", "function public.list_my_inventory_backups()" in candidate and "where b.owner_id = (select auth.uid())" in candidate),
    ("remote catalog is explicitly wired into Tools UI, not automatic Worker", "list_my_inventory_backups" in (APP / "WeeklyBackupCloudCatalog.kt").read_text() and "WeeklyBackupCloudCatalog().listForOwner(ownerId)" in (APP / "MainActivity.kt").read_text() and "WeeklyBackupCloudCatalog" not in (APP / "WeeklyBackupWorker.kt").read_text()),
    ("scheduler API is not silently activated by app startup/login", "WeeklyBackupWorkScheduler.schedule(" not in "".join(p.read_text(errors="ignore") for p in (ROOT / "app/src/main/java").rglob("*.kt") if p.name != "WeeklyBackupWorker.kt")),
    ("no client-side remote backup enumeration hidden elsewhere", "list_inventory_backup_status_admin" not in "".join(p.read_text(errors="ignore") for p in (ROOT / "app/src/main/java").rglob("*.kt"))),
    ("candidate listing exposes status and checksum metadata for future verification", all(x in candidate for x in ["status text", "storage_path text", "archive_size_bytes bigint", "sha256 text", "verified_at timestamptz"])),
    ("remote PENDING is not accepted as final success", 'registration.status != "PENDING" && registration.status != "VERIFIED"' in uploader),
    ("local journal VERIFIED follows remote verification and is not inferred from object existence", uploader.index("verifyStoredObject") < uploader.index("markWeeklyBackupVerified")),
]
failed = []
for name, ok in checks:
    print(("PASS" if ok else "FAIL") + " - " + name)
    if not ok:
        failed.append(name)
print(f"STEP185: {len(checks)-len(failed)}/{len(checks)} PASS")
if failed:
    raise SystemExit(1)
