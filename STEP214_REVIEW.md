# Step214 Review — Authenticated startup crash guard

Date: 2026-11-06

## Evidence and baseline
- Compared the user-provided `KhoLinhKien-v1.16.apk` with the recovered Step210 source archive.
- APK DEX contains `AuthRepository`, `LoginScreen`, `SupabaseClientProvider`, `SessionExpiryPolicy`, and `MachineHistoryDialog` / `AddPartDialog` classes and the unknown-QR flow strings. This supports Step210 as a relevant source baseline for the installed APK; it is not a cryptographic source-to-binary reproducible-build proof.
- GitHub `main` and `work/step213-qr-routing-camera-guard` currently expose a reduced starter source tree (46 KB `MainActivity.kt`, `versionName=1.2`) and must not replace the recovered full source archive.
- Bugreport `2026-11-06-16-37-41` lists seven `APP CRASH(EXCEPTION)` exits for `vn.kholinhkien`. `ApplicationExitInfo.trace=null`; no usable Java stack trace or crash payload is present in the report. User-requested force-stops and one HONOR low-memory kill are separate events.

## Change
- Room database opening and migration/schema validation now run on `Dispatchers.IO` before any owner-scoped Flow is composed.
- Room startup failure is caught and presented in a recoverable screen with retry and safe sign-out. No destructive migration, database deletion, image deletion, backup deletion, or Supabase mutation is introduced.
- WorkManager scheduling and owner-cache initialization exceptions are isolated and recorded rather than escaping their startup coroutine.
- A private diagnostic log stores stack traces for these startup failures without writing credentials, tokens, or owner IDs.

## Follow-up audit correction
- Device bugreport package manager identifies the installed app as `versionCode=17`, `versionName=1.16`. The first Step214 archive incorrectly retained starter-project metadata `3` / `1.2`, which would make an in-place update impossible. Corrected release metadata to `versionCode=18`, `versionName=1.17` and added explicit verifier assertions.
- This is a packaging/installability correction; it does not establish the crash root cause.

## Verification
- `verify_step214_authenticated_startup_guard.py`: 12/12 PASS
- `verify_step209_session_expiry_and_backup_cadence.py`: 12/12 PASS
- `verify_step210_unknown_qr_routing.py`: 7/7 PASS
- `python -m compileall -q tools`: PASS

## Limitations
- The bugreport does not contain the crash stack trace, so Room migration/open failure is a high-priority hypothesis based on the exact post-login execution path, not a proven root cause.
- No Gradle distribution or Android SDK is installed in this execution environment; Kotlin compilation, APK build, installation, and device runtime verification remain NOT RUN.
- This is source-level crash containment and diagnostics, not a claim that the original crash is conclusively fixed.
- No Supabase data/Auth/Storage, signing configuration, or GitHub branches were changed.
