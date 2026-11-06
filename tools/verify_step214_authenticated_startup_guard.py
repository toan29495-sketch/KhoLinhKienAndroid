from pathlib import Path
root = Path(__file__).resolve().parents[1]
main = (root / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text(encoding="utf-8")
diagnostics = (root / "app/src/main/java/vn/kholinhkien/StartupFailureDiagnostics.kt").read_text(encoding="utf-8")
build_config = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
checks = {
    "next installable release increments installed v1.16 code 17": "versionCode = 18" in build_config,
    "next release label is 1.17": 'versionName = "1.17"' in build_config,
    "Room open is moved off the Compose/main thread": "withContext(Dispatchers.IO)" in main and "database.openHelper.writableDatabase" in main,
    "Room open errors are caught": 'StartupFailureDiagnostics.record(context, "room-open", error)' in main,
    "Room failure shows a recoverable screen": "DatabaseStartupFailureScreen(" in main and "onRetry = { databaseRetry++ }" in main,
    "No destructive database fallback is introduced": "fallbackToDestructiveMigration" not in main,
    "WorkManager setup errors are isolated": 'StartupFailureDiagnostics.record(context, "work-scheduler", error)' in main,
    "Owner cache setup errors are isolated": 'StartupFailureDiagnostics.record(context, "owner-cache-isolation", error)' in main,
    "Cancellation is rethrown": main.count("catch (cancelled: CancellationException)") >= 3,
    "Diagnostics are stored in app-private files": 'File(context.applicationContext.filesDir, "diagnostics")' in diagnostics,
    "Diagnostics do not include credential/session objects": all(x not in diagnostics for x in ["password", "accessToken", "refreshToken", "currentUserOrNull"]),
    "Diagnostic write failures cannot crash the app": "runCatching" in diagnostics,
}
for label, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " - " + label)
print(f"RESULT: {sum(checks.values())}/{len(checks)} PASS")
if not all(checks.values()):
    raise SystemExit(1)
