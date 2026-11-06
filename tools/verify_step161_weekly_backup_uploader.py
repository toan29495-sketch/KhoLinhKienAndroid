#!/usr/bin/env python3
"""Static source contract checks for the Step161 backup uploader adapter."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SRC = (ROOT / "app/src/main/java/vn/kholinhkien/SupabaseWeeklyBackupUploader.kt").read_text()
BUILD = (ROOT / "app/build.gradle.kts").read_text()
DOC = (ROOT / "docs/STEP161_WEEKLY_BACKUP_UPLOADER.md").read_text()
SQL = (ROOT / "supabase/candidates/step160_weekly_backup_backend_candidate.sql").read_text()
checks=[]

def check(label, ok): checks.append((label, bool(ok)))

check("adapter is not a WorkManager worker", "class SupabaseWeeklyBackupUploader" in SRC and "Worker" not in SRC.split("class SupabaseWeeklyBackupUploader",1)[1].split("{",1)[0])
check("Supabase Kotlin remains pinned at 3.0.1", 'bom:3.0.1' in BUILD and "change the dependency version" in DOC)
check("uses File-based resumable upload", "import io.github.jan.supabase.storage.createOrContinueUpload" in SRC and ".resumable.createOrContinueUpload(expectedPath, prepared.archive)" in SRC)
check("explicit upsert false", "upsert = false" in SRC and "upsert = true" not in SRC)
check("explicit ZIP content type", 'ContentType.parse("application/zip")' in SRC)
check("TUS API options verified in exact pinned tag", "ResumableUtils.kt" in DOC and "UploadOptionBuilder" in DOC)
check("backup ID is supplied and canonical", "backupId: String" in SRC and "UUID.fromString(value)" in SRC)
check("same staged path and bytes required for retries", "same archive path and bytes across" in SRC and "same staged archive file path and bytes across retries" in DOC)
check("owner session checked before/after RPC and storage", SRC.count("requireActiveOwner(prepared.ownerId)") >= 7)
check("begin RPC parameter names match SQL candidate", all(f'put("{p}"' in SRC and p in SQL for p in ["p_backup_id","p_backup_kind","p_snapshot_cursor","p_archive_size_bytes","p_sha256","p_part_count","p_movement_count"]))
check("complete RPC parameter names match SQL candidate", all(f'put("{p}"' in SRC and p in SQL for p in ["p_backup_id","p_verified_sha256"]))
check("download streams to private file", "downloadAuthenticatedTo(storagePath, target)" in SRC and "downloadAuthenticated(" not in SRC)
check("SHA-256 is streamed from File", "FileInputStream(file).buffered()" in SRC and "ByteArray(64 * 1024)" in SRC and "downloadAuthenticated(" not in SRC)
check("actual size and digest checked before complete", SRC.index("verifyStoredObject(", SRC.index("val registration")) < SRC.index('"complete_inventory_backup"'))
check("low-storage preflight exists", "StatFs(context.cacheDir.absolutePath).availableBytes" in SRC and "MIN_FREE_BYTES" in SRC)
check("verification temp file always deleted", "finally {\n            // The verification copy" in SRC and "verifyFile.delete()" in SRC)
check("durable archive is deleted only after local VERIFIED journal commit", SRC.index("dao.markWeeklyBackupVerified(") < SRC.index("prepared.archive.delete()"))
check("cancellation propagates", "catch (cancelled: CancellationException)" in SRC and "throw cancelled" in SRC)
check("no service role key", "service_role" not in SRC.lower() and "SUPABASE_SERVICE_ROLE" not in SRC)
check("no remote Storage delete API", "client.storage.from(BUCKET).delete" not in SRC and "client.storage.from(BUCKET).remove" not in SRC)
check("does not claim live deployment or device test", "not deployed" in DOC and "No Kotlin/Android compile" in DOC)

for label, ok in checks: print(f"{'PASS' if ok else 'FAIL'}: {label}")
print(f"RESULT: {sum(ok for _,ok in checks)}/{len(checks)} PASS")
raise SystemExit(0 if all(ok for _,ok in checks) else 1)
