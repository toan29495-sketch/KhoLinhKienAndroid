#!/usr/bin/env python3
"""Step200 source-contract checks for explicit, owner-scoped Cloud backup execution."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKER = (ROOT / "app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt").read_text()
UPLOADER = (ROOT / "app/src/main/java/vn/kholinhkien/SupabaseWeeklyBackupUploader.kt").read_text()
MAIN = (ROOT / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text()
checks = [
    ("manual trigger validates canonical owner", "fun enqueueNow(context: Context, ownerId: String)" in WORKER and "require(isCanonicalOwnerId(ownerId))" in WORKER),
    ("manual trigger uses connected-network constraint", "OneTimeWorkRequestBuilder<WeeklyBackupWorker>()" in WORKER and "setRequiredNetworkType(NetworkType.CONNECTED)" in WORKER),
    ("manual trigger is unique per owner and does not replace active work", 'oneTimeName(ownerId), ExistingWorkPolicy.KEEP' in WORKER),
    ("manual trigger has retry backoff", "setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)" in WORKER),
    ("periodic schedule remains separate from one-time manual trigger", "enqueueUniquePeriodicWork(" in WORKER and "fun enqueueNow(context: Context, ownerId: String)" in WORKER and "fun schedule(context: Context, ownerId: String)" in WORKER),
    ("manual UI is disabled during restore lock", 'enabled = !backupBusy && !restoreLocked' in MAIN),
    ("manual UI rechecks authenticated owner before enqueue", 'currentUserOrNull()?.id == ownerId' in MAIN and 'WeeklyBackupWorkScheduler.enqueueNow(context, ownerId)' in MAIN),
    ("UI clearly reports queued rather than completed", "Đã đưa yêu cầu sao lưu Cloud vào hàng đợi" in MAIN and "kiểm tra trạng thái VERIFIED" in MAIN),
    ("UI explains automatic backup uses a 30-day cycle", "Bản sao lưu tự động được lập lịch 30 ngày một lần" in MAIN),
    ("worker rechecks session before and after waiting for owner lock", WORKER.count("currentUserOrNull()?.id != ownerId") >= 2),
    ("worker calls the complete uploader pipeline", "SupabaseWeeklyBackupUploader(applicationContext, client, dao)" in WORKER and ".uploadAndVerify(prepared, backupId)" in WORKER),
    ("uploader registers remote metadata", '"begin_inventory_backup"' in UPLOADER),
    ("uploader uses resumable TUS with upsert disabled", "createOrContinueUpload(expectedPath, prepared.archive)" in UPLOADER and "upsert = false" in UPLOADER),
    ("uploader performs authenticated readback", "downloadAuthenticatedTo(storagePath, target)" in UPLOADER),
    ("uploader verifies exact size and SHA-256 before completion", "target.length() != expectedSize" in UPLOADER and "actualDigest.equals(expectedSha256" in UPLOADER),
    ("uploader marks remote VERIFIED only after readback", UPLOADER.index("verifyStoredObject(") < UPLOADER.index('"complete_inventory_backup"')),
    ("uploader commits local VERIFIED journal after remote confirmation", "markWeeklyBackupVerified(prepared.ownerId, backupId)" in UPLOADER),
    ("logout cancellation removes both one-time and periodic owner work", "cancelUniqueWork(oneTimeName(ownerId))" in WORKER and "cancelUniqueWork(uniqueName(ownerId))" in WORKER),
]
failed = []
for name, ok in checks:
    print(("PASS" if ok else "FAIL") + " | " + name)
    if not ok:
        failed.append(name)
print(f"STEP200: {len(checks) - len(failed)}/{len(checks)} PASS")
if failed:
    raise SystemExit(1)
