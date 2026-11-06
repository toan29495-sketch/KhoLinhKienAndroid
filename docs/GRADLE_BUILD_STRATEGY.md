# Gradle / GitHub Actions build strategy

## Why local build did not start
The previous attempt failed before Gradle itself launched: the environment could not resolve
`services.gradle.org` (`UnknownHostException`). This is a network/DNS failure while downloading
the Gradle 8.9 distribution, not a Kotlin compiler diagnostic.

## Changes in this revision
- GitHub Actions now uses the repository's `gradlew` and the version pinned in
  `gradle/wrapper/gradle-wrapper.properties`; it no longer installs a separately selected
  Gradle version that can drift from the wrapper.
- `gradle/actions/setup-gradle@v4` provides Gradle User Home caching for distributions and
  dependency artifacts.
- Wrapper validation runs before build.
- Wrapper download timeout increased to 60 seconds and retries to 3.
- A validation workflow runs on `work/auth-foundation`, pull requests to `main`, and manual
  dispatch. It builds a **debug** APK and does not require release keystore or Supabase secrets.
- Release workflow remains separate and still requires the existing signing/Supabase secrets.

## How to run
1. Push this branch to GitHub (do not merge to `main`).
2. Open Actions -> Android build validation -> Run workflow, selecting `work/auth-foundation`,
   or let the branch push trigger it.
3. Inspect the first failing step and full log. If wrapper download succeeds and compilation
   fails, the log will now contain the actual Kotlin/Gradle error.
4. Download the debug APK artifact only after the workflow completes successfully.

## Verification status
- Workflow and wrapper configuration edited locally.
- Local YAML/configuration review only; this environment cannot resolve `services.gradle.org`,
  so it cannot download Gradle or run a real compile here.
- GitHub Actions has not yet run this revision. No successful build is claimed.
- No release APK generated; no Supabase migration run; no changes pushed to GitHub by this edit.
