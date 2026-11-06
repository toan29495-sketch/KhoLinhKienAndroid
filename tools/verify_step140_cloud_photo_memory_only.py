from pathlib import Path

source = Path("app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text(encoding="utf-8")
checks = {
    "cloud photo fetched with authenticated in-memory API": '.downloadAuthenticated(photoPath)' in source,
    "no authenticated-to-file photo download": "downloadAuthenticatedTo" not in source,
    "no photo cache directory": "part_photo_cache" not in source,
    "no 30-day photo cache": "PART_PHOTO_CACHE_MAX_AGE_MS" not in source,
    "no photo cache mutex": "partPhotoCacheMutex" not in source,
    "cloud path validates owner prefix": 'segments.first() == ownerId' in source,
    "cloud path rejects traversal": 'segments.none { it.isBlank() || it == "." || it == ".." }' in source,
    "local file constrained to app private storage": 'canonicalSource.path.startsWith(privateRoot.path + File.separator)' in source,
    "local photo read has preflight size bound": 'file.length() in 1..MAX_PART_PHOTO_BYTES.toLong()' in source,
    "local photo streaming read has hard size bound": 'require(total <= MAX_PART_PHOTO_BYTES)' in source,
    "temporary image bytes zeroed after decode": 'imageBytes.fill(0)' in source,
    "cancellation is propagated": 'catch (cancelled: CancellationException)' in source,
    "cloud preview does not write files": 'File(context.cacheDir, "part_photo_cache")' not in source and 'downloadAuthenticatedTo' not in source,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " - " + name)
print(f"{len(checks)-len(failed)}/{len(checks)} PASS")
if failed:
    raise SystemExit(1)
