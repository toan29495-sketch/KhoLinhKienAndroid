# Step121 – Read-back verification before backup publication

Date: 2026-11-03  
Baseline: Step120 checkpoint `KhoLinhKienAndroid-step120-backup-interruption-atomic-publish-20261103.zip`; SHA-256 verified before extraction and ZIP integrity passed.

## Finding
Step120 ensured interrupted backup files were not exposed under the final filename, but a completed ZIP write was published based on successful stream completion alone. The generated archive was not reopened and checked against its manifest before publication. A storage/provider write fault or corrupted entry could therefore yield a visible backup that would only fail during a later restore.

## Changes
- Added `BackupArchiveInspector.verify(InputStream, expectedOwnerId)` to read the closed archive back before publication.
- The read-back pass enforces the restore-compatible entry-count, path, duplicate-name, per-entry and aggregate uncompressed-size limits; requires the manifest and both JSONL payloads; checks ZIP v2 and exact owner; checks record counts; and verifies that the manifest SHA-256 map exactly matches the archive entries and the bytes read back.
- Android 10+ MediaStore backup remains `IS_PENDING=1` until read-back verification succeeds. If verification fails, the existing error path deletes the pending row.
- Android 8/9 partial file is read back and verified before it is renamed to the final filename. If verification fails, the partial file is deleted.
- `ZipRestore` keeps its independent full extraction and checksum validation; read-back verification does not replace restore validation.
- ZIP v2 format and cloud-photo cache behavior remain unchanged.

## Verification
- `tools/verify_step121_backup_readback_validation.py`: 15/15 PASS.
- Step120: 13/13; Step119: 12/12; Step118: 12/12; Step113: 14/14; Step112: 10/10; Step111: 14/14; Step109: 13/13; Step108: 11/11; Step107: 14/14; Step86: 7/7; Step49–72: 24/24 plus SQLite schema/atomic replacement smoke PASS.
- Python tooling `compileall`: PASS.
- Source-only checks. No Android compiler, MediaStore provider, file-system fault injection, process-kill, low-storage or device test was run. No APK build/install, live Supabase/PostgreSQL staging, SQL/Edge deployment, or GitHub push. No Gradle used.

## Known limitation
- The read-back verifier uses `ZipInputStream`, so it verifies all local-file entries, decompressed bytes, CRC behavior exposed by the stream, record counts, owner and SHA-256 payload checks. It does not independently parse the ZIP central-directory/EOCD records. A future step can assess central-directory validation without staging a second archive or requiring unsafe extra cache space.

## Source lineage
Continued from the exact Step120 ZIP. Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source.
