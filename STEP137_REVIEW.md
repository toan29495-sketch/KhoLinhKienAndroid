# Step137 – Fresh-install / empty-business-data test baseline

Date: 2026-11-03

## Baseline and scope

- Continued from the exact Step136 ZIP after verifying SHA-256 (`d51ea94a7cb7be08822910cba6ffbda29696881bdc0859e65a6d6a8065c8d23a`) and ZIP integrity.
- The user confirmed the old app has already been deleted. The new test cycle starts with a fresh app-local Room database, no old local backup import, and all part QR codes will be registered/scanned again.
- No production Kotlin, Room schema/version, SQL migration, Supabase RPC, Edge Function, Storage policy, sync payload, or RESTORE changes in this audit-only step.

## Findings

- A fresh install creates `kho-linh-kien.db` with empty `parts`, `movements`, `sync_outbox`, and `sync_cursors` tables. There is no Room prepackaged DB import.
- The first owner pull starts at cursor `0`; Admin additionally has a separate global movement feed/cursor. Therefore the intended blank test state is preserved if the corresponding Supabase owner feed and Admin global feed contain no old events, as the user expects for this test cycle.
- The live Supabase event feeds were not queried. If old events remain in the hosted backend, the current cursor-zero pull will import them. No server-side data or feed was deleted/modified. This is a deployment/environment prerequisite, not something a local fresh install can prove.
- New parts are inserted owner-scoped and their `PART_UPSERT` outbox entry is written in the same Room transaction. An opening-stock movement is created only when initial quantity is greater than zero.
- QR scanner behavior is intentionally split: a QR already registered in the current KTV's warehouse opens that part; an unknown QR is treated as a machine QR and opens machine history, not auto-created as a part. New parts must first be registered through Add Part or batch import; then scanning their QR opens the part.
- Batch import skips duplicate codes and creates records for the signed-in owner. No old backup restore is part of the test plan.
- Admin's own inventory feed and the redacted global history feed remain separate. No legacy local rows exist on a fresh install; the legacy claim path only applies if such local rows are actually present.

## Verification

- Step137 fresh-install verifier: 18/18 PASS, including a SQLite-equivalent blank database and first-new-QR/outbox smoke.
- Python compileall: PASS.
- Regression scripts Step100–Step137: PASS; Step129 was rerun separately after the first aggregate run reached its command timeout. Step82 16/16; Step90 10/10; Step91 10/10; Step96 21/21; Step99 13/13; Step49–72 24/24 plus SQLite schema/atomic replacement smoke PASS.
- Kotlin/JVM `MovementOwnerResolver`: 8/8 PASS.

## Verification boundaries

- SQLite-equivalent tests and source-contract checks only. No actual Android Room/Compose compilation, camera/ML Kit QR scan, WorkManager execution, emulator/device test, APK build/install, or GitHub push.
- No live Supabase/PostgreSQL query; hosted feed emptiness, auth roles, network sync, and cross-device behavior remain unverified.
- No Gradle used, per explicit project instruction.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte recovery of the original source archive.
