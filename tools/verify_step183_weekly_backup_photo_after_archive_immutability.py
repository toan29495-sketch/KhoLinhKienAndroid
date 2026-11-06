#!/usr/bin/env python3
"""Step183: source photos may change after ZIP creation; staged archive identity must not."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BACKUP = (ROOT / "app/src/main/java/vn/kholinhkien/BackupManager.kt").read_text()
UPLOADER = (ROOT / "app/src/main/java/vn/kholinhkien/SupabaseWeeklyBackupUploader.kt").read_text()
RECOVERY = (ROOT / "app/src/main/java/vn/kholinhkien/WeeklyBackupRecovery.kt").read_text()
REVIEW = ROOT / "STEP183_REVIEW.md"
checks = []

def ck(label, ok):
    checks.append((label, bool(ok)))

# ZIP generation embeds photo bytes and their per-entry checksum; upload does not
# revisit the original photo source or cache directory.
create_zip = BACKUP[BACKUP.index("private suspend fun createZip"):]
ck("photo bytes are embedded in ZIP entries", 'zip.putNextEntry(ZipEntry(entryName))' in create_zip and 'zip.write(buffer, 0, count)' in create_zip)
ck("photo SHA-256 is recorded in manifest checksums", 'checksums.put(entryName, digest.digest().toHex())' in create_zip)
ck("temporary cloud-photo cache is removed after ZIP creation", 'temporaryDirectory.deleteRecursively()' in create_zip)
ck("uploader does not resolve or download original photo objects", 'photo_path' not in UPLOADER and 'part-images' not in UPLOADER and 'createOrContinueUpload(expectedPath, prepared.archive)' in UPLOADER)

# Durable journal size/digest, not mutable source-file observations, define registration.
ck("registration receives the durable journal archive size", 'beginRegistration(prepared, backupId, journal.archiveSizeBytes)' in UPLOADER)
ck("RPC registration sends expected journal size", 'put("p_archive_size_bytes", expectedArchiveSizeBytes)' in UPLOADER)
ck("journal size and digest are compared before upload", 'journal.archiveSizeBytes != prepared.archive.length()' in UPLOADER and '!journal.sha256.equals(prepared.sha256' in UPLOADER)

# Critical revalidation is after registration and immediately before SDK hands File to TUS.
pending = UPLOADER[UPLOADER.index('if (registration.status == "PENDING")'):UPLOADER.index('// If a previous attempt completed the object')]
ck("archive is revalidated after registration and before TUS", pending.index('validatePreparedArchive(prepared, journal.archiveSizeBytes)') < pending.index('createOrContinueUpload(expectedPath, prepared.archive)'))
ck("revalidation requires exact durable size", 'archive.length() == expectedSizeBytes' in UPLOADER)
ck("revalidation recomputes SHA-256 of staged ZIP", 'val actualDigest = sha256File(archive, prepared.ownerId)' in UPLOADER and 'actualDigest.equals(prepared.sha256' in UPLOADER)
ck("identity and cancellation are checked after revalidation", pending.index('validatePreparedArchive(prepared, journal.archiveSizeBytes)') < pending.index('uploadCoroutineContext.ensureActive()') < pending.index('requireActiveOwner(prepared.ownerId)') < pending.index('createOrContinueUpload'))
ck("TUS upsert remains disabled", 'upsert = false' in UPLOADER)
ck("remote object is verified against immutable prepared digest", 'expectedSha256 = prepared.sha256' in UPLOADER and 'actualDigest.equals(expectedSha256' in UPLOADER)
ck("recovery refuses changed READY/UPLOADING archive", 'digest.equals(row.sha256, ignoreCase = true)' in RECOVERY and 'dao.markWeeklyBackupFailed(ownerId, row.backupId' in RECOVERY)
ck("mutation failure is terminal and does not silently rebuild same backupId", 'PermanentWeeklyBackupException' in pending and 'ZIP backup đã thay đổi trước khi upload' in pending)

failed = [label for label, ok in checks if not ok]
for label, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'}: {label}")
print(f"Step183: {len(checks)-len(failed)}/{len(checks)} PASS")
if failed:
    raise SystemExit(1)
