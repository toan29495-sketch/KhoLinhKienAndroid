# Step123 – ZIP edge-case and BACKUP/RESTORE contract validation

Date: 2026-11-03  
Baseline: exact Step122 checkpoint `KhoLinhKienAndroid-step122-zip-central-directory-validation-20261103.zip`; SHA-256 verified before extraction and ZIP integrity passed.

## Scope
This is a verification-only checkpoint. The production Kotlin sources were not changed because the targeted edge-case and contract checks did not expose a defect requiring a source change. This avoids unnecessary edits to the already hardened backup publication path.

## Checks added
- Expanded `tools/ZipCentralDirectoryVerifierSmoke.kt` to execute the actual Kotlin parser on a normal app-shaped DEFLATED archive and an archive containing a zero-byte photo entry.
- Added negative fixtures for invalid EOCD signature/comment length, multi-disk marker, Central Directory CRC/method/flags/name length, encrypted entry flag, Local File Header name/CRC mismatch, Data Descriptor CRC/size mismatch, Central Directory size mismatch, and truncated final byte.
- Added `tools/verify_step123_backup_zip_contract.py` to compare the writer, read-back inspector and restore limits and to assert v2 payload, checksum, photo-reference, owner-isolation and structural-parser contracts.

## Verification results
- Actual Kotlin/JVM parser smoke: 15/15 PASS.
- BACKUP/RESTORE contract checks: 20/20 PASS.
- Step122 structural verifier: 15/15 PASS.
- Step121 read-back verifier: 15/15 PASS.
- Step120 interrupted-backup verifier: 13/13 PASS.
- Step119 cloud-photo cache bounds: 12/12 PASS.
- Step118 local-photo owner isolation: 12/12 PASS.
- Step113 restore cancellation/resume: 14/14 PASS.
- Step112 restore temporary cleanup: 10/10 PASS.
- Step111 backup/restore size parity: 14/14 PASS.
- Step109 fixed backup filename: 13/13 PASS.
- Step108 backup filename: 11/11 PASS.
- Step107 local-photo owner isolation: 14/14 PASS.
- Step86 photo preflight: 7/7 PASS.
- Step49–72 sync regression: 24/24 PASS plus SQLite schema/atomic replacement smoke PASS.
- Python tooling `compileall`: PASS; generated `__pycache__` and `.pyc` files removed before packaging.

## Environment and limitations
- The actual Kotlin parser was compiled and executed on the JVM, not merely inspected by string assertions.
- No Android SDK, `adb`, or Android emulator is installed/available in this execution environment, so no emulator run or Android framework compilation was performed.
- No physical-device test, APK build/install, MediaStore provider test, low-storage/process-kill test, live Supabase/PostgreSQL staging, SQL/Edge deployment, or GitHub push was performed.
- No Gradle command was run.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost source.

## Conclusion
The Step122 parser accepts the app-shaped ZIP writer output and rejects the tested malformed structures. Backup writer, read-back inspector and restore share the expected size/count/photo/checksum contract in source. No defect was found in this targeted audit; this does not establish full Android runtime or device correctness. Keep emulator/device verification for the final project-wide pass as requested.
