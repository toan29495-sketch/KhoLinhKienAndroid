# Step208 build branch

This branch is isolated from `main`. To build the current Step208 source:

1. Upload the supplied `Step208-source.zip` file to the repository root on branch `work/step208-completion-build`.
2. Commit the upload. The `Step208 completion build` workflow triggers only for this archive path.
3. The workflow validates the archive root, restores the complete source tree into the runner, runs the source regression checks, compiles Kotlin, assembles a debug APK, and uploads the APK plus SHA-256 checksum.
4. The workflow requires repository secrets `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY`. It never uses a service-role key.

Do not merge this branch into `main` until the Step208 APK build and device test have passed and the diff has been reviewed.
