#!/usr/bin/env python3
"""Step189 source-contract checks for authenticated Cloud backup download and verification."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
DL = (ROOT / "app/src/main/java/vn/kholinhkien/WeeklyBackupCloudDownloader.kt").read_text()
MAIN = (ROOT / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text()
CAT = (ROOT / "app/src/main/java/vn/kholinhkien/WeeklyBackupCloudCatalog.kt").read_text()
UP = (ROOT / "app/src/main/java/vn/kholinhkien/SupabaseWeeklyBackupUploader.kt").read_text()
checks = [
    ("download uses authenticated Storage API", "downloadAuthenticatedTo(entry.storagePath, partial)" in DL),
    ("download is restricted to verified restore candidates", "require(entry.isRestoreCandidate)" in DL),
    ("owner identity checked before and after download", DL.count("requireOwner(ownerId)") >= 5 and "currentUserOrNull()?.id == ownerId" in DL),
    ("storage path must exactly match owner and backup UUID", 'entry.storagePath == "$ownerId/${entry.backupId}.zip"' in DL),
    ("cache free-space preflight includes archive plus safety margin", "available >= entry.archiveSizeBytes + MIN_FREE_BYTES" in DL),
    ("partial downloads use private cache and are removed in finally", "context.cacheDir" in DL and "finally {\n            if (partial.exists()) partial.delete()" in DL),
    ("downloaded byte length is checked against catalog metadata", "partial.length() == entry.archiveSizeBytes" in DL),
    ("downloaded SHA-256 is checked against catalog metadata", "digest.equals(entry.sha256, ignoreCase = true)" in DL),
    ("archive structure and internal checksums are verified", "BackupArchiveInspector.verify(input, ownerId)" in DL),
    ("manifest owner, part/movement counts, kind and cursor match catalog", all(x in DL for x in ["manifest.optString(\"ownerId\") == ownerId", "manifest.optInt(\"partCount\", -1) == entry.partCount", "manifest.optInt(\"movementCount\", -1) == entry.movementCount", "manifest.optString(\"backupKind\") == entry.backupKind", "manifest.optLong(\"snapshotCursor\", -1L) == entry.snapshotCursor"])),
    ("final cache file is published only after all verification", DL.index("verifyManifest(partial") < DL.index("partial.renameTo(finalFile)")),
    ("account switch is fenced at final publish", DL.index("partial.renameTo(finalFile)") < DL.index("requireOwner(ownerId)\n            return VerifiedFile")),
    ("cancellation propagates and partial file cleanup remains", "catch (cancelled: CancellationException)" in DL and "throw cancelled" in DL),
    ("UI exposes explicit download action only for verified entries", "if (entry.isRestoreCandidate)" in MAIN and "Tải và xác minh backup này" in MAIN),
    ("UI runs download off main thread", "WeeklyBackupCloudDownloader(context).downloadAndVerify(ownerId, entry)" in MAIN and "withContext(Dispatchers.IO)" in MAIN),
    ("UI distinguishes download failure and verified-not-restored state", "cloudDownloadError.isNotBlank()" in MAIN and "Chưa khôi phục" in MAIN),
    ("no restore coordinator is invoked by download button", "RestoreCloudCoordinator" not in MAIN[MAIN.index('fun ToolsScreen('):MAIN.index('fun MachineHistoryDialog(')]),
    ("existing uploader readback path remains intact", "downloadAuthenticatedTo(storagePath, target)" in UP and "verifyStoredObject" in UP),
]
failed=[]
for name, ok in checks:
    print(("PASS" if ok else "FAIL") + " - " + name)
    if not ok: failed.append(name)
print(f"STEP189: {len(checks)-len(failed)}/{len(checks)} PASS")
if failed: raise SystemExit(1)
