# Gradle preparation audit

Date: 2026-11-04  
User authorization: user explicitly allowed checking/running Gradle when safely prepared.

## Static configuration review

- Wrapper distribution: Gradle 8.9 (`gradle/wrapper/gradle-wrapper.properties`).
- Android Gradle Plugin: 8.7.3.
- Kotlin Android/Compose/serialization plugins: 2.0.21.
- KSP: 2.0.21-1.0.28.
- Android compile SDK: 35; build-tools expected by CI: 35.0.0; min SDK 26; target SDK 35.
- Project compile target: Java 17. Current container has OpenJDK 21.0.12; the repository CI workflow pins JDK 17.
- `local.properties` is intentionally optional for source compilation; without it BuildConfig Supabase values are empty, so an APK built without secrets would not be ready for live Supabase use.
- `.github/workflows/android-validation.yml` installs Gradle through the wrapper, installs Android SDK 35/build-tools 35.0.0, then runs `:app:compileDebugKotlin :app:assembleDebug`.

## Issues found and action

- `gradlew` was not executable in the Step205 ZIP (`0644`); direct `./gradlew` failed with `Permission denied`. The source file mode has been corrected to executable (`0755`) before packaging this reconciled ZIP.
- Wrapper JAR archive integrity check passed.
- `./gradlew --no-daemon --version` attempted to download Gradle 8.9 but failed after retries with `UnknownHostException: services.gradle.org`. No Gradle distribution is cached in this environment.
- Android SDK variables are unset; no `sdkmanager`, `adb`, or Android SDK installation was found in the container.
- No Gradle build, Kotlin compilation, APK assembly, signing, install or device test was completed.

## Safe next build conditions

1. Provide a build environment with network access to `services.gradle.org`, Google Maven, Maven Central and Android SDK repositories, or pre-provision the Gradle 8.9 distribution, plugin/dependency caches and Android SDK 35/build-tools 35.0.0.
2. Prefer JDK 17 to match the checked-in CI workflow (JDK 21 may also be usable, but matching CI reduces environmental drift).
3. For compile-only validation, no production secrets are required because `local.properties` is optional; do not publish/install that APK as a production-ready app.
4. For a release APK, verify the exact Supabase URL/publishable key and release keystore secrets are supplied securely; never put service-role secrets in the Android app.

No source build task was run after the wrapper preflight failure. No network settings or system packages were changed.
