# Build / release checklist — KhoLinhKienAndroid

## Recovery and reconstructed sync work
- [x] Recover source baseline from preserved Step26 archive.
- [x] Reapply verified Step47 operation-scoped image path.
- [x] Reimplement Step49–Step72 sync foundations on the recovered source; see `STEP49_72_RECONSTRUCTION_REVIEW.md`.
- [x] Add source-level verification script (`tools/verify_sync_step49_72.py`).
- [x] Create SQLite smoke tests for additive schema evolution and atomic replacement behavior.
- [x] Verify Outbox SELECT query forms with SQLite EXPLAIN.
- [x] Compile the pure Kotlin conflict planner standalone.
- [x] Create a new immutable ZIP checkpoint and SHA-256 for this reconstructed source.

## Known safe limits
- [x] Keep queue paused for unresolved BLOCKED or legacy unknown IN_FLIGHT rows.
- [x] Only isolated KEEP_LOCAL + PART_UPSERT can be staged as a replacement.
- [ ] KEEP_SERVER safe reconciliation workflow.
- [ ] MANUAL_REVIEW operator workflow.
- [ ] Safe resolution of dependent chains, PART_DELETE and STOCK_CHANGE conflicts.
- [ ] Lease renewal/heartbeat and staging verification for long uploads/RPC.

## Required validation before release
- [ ] Run Room migration tests from schema 8 through 11 with a copy of real legacy data.
- [ ] Run PostgreSQL/Supabase staging migrations and verify function grants/RLS/RPC response serialization.
- [ ] Run two-device CAS, tombstone, FIFO, idempotency, lost-response and photo-upload fault-injection tests.
- [ ] Review unresolved legacy IN_FLIGHT rows with claimStartedAt=0.
- [ ] User reviews and approves the source ZIP.
- [ ] Only after source approval, prepare APK build and release checks.

## Release restrictions
- No production SQL deployment without explicit approval.
- No GitHub push without explicit approval.
- No APK build/install until source work is complete and user approves the source checkpoint.

## Step73 — RPC idempotency hardening
- [x] Compare exact stored payload before returning a cached processed result.
- [x] Add source-level regression assertions for operation-id payload mismatch.
- [ ] Run PostgreSQL/Supabase staging test: exact retry returns same result; same operationId with changed payload raises OPERATION_PAYLOAD_CONFLICT.
- [ ] Run concurrent duplicate RPC and lost-response fault-injection tests.

## Step74 source audit
- [x] Reject NULL RPC operation discriminator explicitly.
- [x] Reject NULL stock movement discriminator explicitly.
- [ ] Execute migration/RPC tests against isolated PostgreSQL/Supabase staging before any deployment.

## Step75 — Stock CAS token validation
- [x] Reject missing, NULL, blank, malformed, out-of-range, zero and negative `expected_server_version` values.
- [x] Compare the locked server version using `IS DISTINCT FROM`.
- [x] Add source-level regression assertions and rerun the reconstructed sync regression suite.
- [ ] Execute malformed-token and stale-version cases against isolated PostgreSQL/Supabase staging before deployment.

## Step76–77 — RPC acknowledgement and sequential rebase
- [x] Validate RPC `status`, `entity_id`, and positive `server_version` before acknowledging Outbox.
- [x] Rebase queued operations for the same part using a distinct sequential expected version in FIFO order.
- [x] Keep `Part.serverVersion` at the last server-confirmed value, not a projected future value.
- [x] Fail closed on version arithmetic overflow or missing owner-scoped Part row.
- [x] Add source-level regression checks and FIFO/overflow model.
- [ ] Verify sequential multi-operation behavior with Room runtime and Supabase staging before release.

## Step78 — Safe acknowledgement for locally removed parts
- [x] Route PART_DELETE through a dedicated Room transaction instead of trying to update a deleted local Part row.
- [x] Require exact owner/entity/type/IN_FLIGHT identity before acknowledging delete.
- [x] Fail closed if the local Part unexpectedly exists or another unresolved mutation remains for that entity.
- [x] Add static regression assertions and a fail-closed state model.
- [ ] Verify delete commit + lost response + idempotent replay against Supabase staging and Room runtime.

## Step79 — Monotonic pull snapshot guard
- [x] Validate owner/entity and positive server_version for pulled PART events.
- [x] Preserve restore and unresolved-Outbox barriers.
- [x] Ignore duplicate/stale PART events when a newer/equal local row exists.
- [x] Add static + SQLite model regression checks.
- [ ] Consider a durable local tombstone/version ledger if pull events can ever be processed out of cursor order; current physical deletion means the local version guard cannot compare after row removal.
- [ ] Verify with Room runtime, multiple workers, and Supabase staging before release.

## Step80 — Tombstone ledger
- [x] Add owner-scoped `PartTombstone` Room entity (composite owner/entity key).
- [x] Add additive Room migration 11→12.
- [x] Compare incoming PART events against live and tombstone versions.
- [x] Persist tombstone and remove live Part atomically.
- [x] Clear tombstone only for a strictly newer live event.
- [x] Preserve absent tombstones across cloud snapshot restore; prune only superseded ones.
- [x] Require positive server version for restored snapshot parts.
- [x] Static + SQLite regression suite 16/16; previous suites pass.
- [ ] Room instrumentation/runtime migration test on Android.
- [ ] Supabase/PostgreSQL staging + multi-device/reordering/process-kill fault injection.
- [ ] APK build/device verification (only after source completion and user approval).
- [ ] Production migration/deployment (requires explicit approval).

## Step81 – Movement history consistency
- [x] Align ZIP mapper validation with cloud restore commit movement rules.
- [x] Align Room cloud snapshot validation with cloud restore commit movement rules.
- [x] Reject unsupported movement types, zero amount for NHẬP/XUẤT/ĐẦU KỲ, and impossible before/after quantities.
- [x] Verify valid and invalid movement examples; re-run prior source regression suites.
- [ ] Android Room runtime/instrumentation validation.
- [ ] PostgreSQL/Supabase staging validation of restore commit constraints.
- [ ] APK/device validation after source review and explicit approval.

## Step82 – Movement pull idempotency
- [x] Exact duplicate movement replay is idempotent only when immutable fields match.
- [x] Same syncId with changed payload or another owner fails closed.
- [x] Unique-insert race is re-read and verified before cursor may advance.
- [x] Pull validates owner, UUID, movement arithmetic, and server timestamp.
- [x] Static/model regression suite 14/14 and prior Step73–81 + Step49–72 suites pass.
- [ ] Verify cloud-side movement uniqueness and replay behavior on Supabase staging.
- [ ] Run Room instrumentation and multi-device/concurrency/lost-response tests.

## Step83 – Live stock mutation guard
- [x] Allowlist live stock operations (NHẬP/XUẤT/KIỂM KÊ); opening balance remains creation-only.
- [x] Reject negative current stock and perform arithmetic/range checks in Long before Int conversion.
- [x] Keep Part + Movement + Outbox writes in one Room transaction.
- [x] Step83 regression 16/16; rerun Step73–82 and Step49–72 source suites passed.
- [ ] Android Room runtime/instrumentation validation.
- [ ] PostgreSQL/Supabase staging and multi-device/fault-injection validation.
- [ ] APK/device testing (only after source review and explicit user approval).
- [ ] No SQL deployment or GitHub push without explicit approval.

## Step84 – Part edit identity and stock-history guard
- [x] Pass authenticated owner explicitly into DAO edit operation and enforce owner-scoped row existence.
- [x] Keep QR code, syncId, serverVersion, and quantity immutable in metadata edit path.
- [x] Require stock changes to use movement-recording NHẬP/XUẤT/KIỂM KÊ transactions.
- [x] Validate name, unit, min quantity, and SINGLE/BULK allowlist.
- [x] Block BULK→SINGLE while stock quantity exceeds one.
- [x] Keep Part update and PART_UPSERT Outbox enqueue atomic; verify one-row update.
- [x] Step84 regression 20/20; rerun Step73–83 and Step49–72 source suites passed.
- [ ] Decide and design a dedicated QR-code rename operation only with atomic server uniqueness checks and history linkage; current edit keeps code immutable.
- [ ] Android Room runtime/instrumentation and Supabase staging validation.
- [ ] APK/device testing only after source review and explicit approval.

## Step85 – Photo lifecycle and in-flight edit/delete race
- [x] Prevent an older photo upload acknowledgement from overwriting a newer local photo selection.
- [x] Allow the older upsert to acknowledge after a local delete only when a same-owner/entity PART_DELETE is queued behind it.
- [x] Keep server-version advancement and sequential rebase for later mutations.
- [x] Preserve local-photo reference scan before deleting a local file.
- [x] Confirm no eager cloud Storage deletion exists; defer orphan cleanup until global references can be proven absent.
- [x] Static/model regression suite 13/13.
- [ ] Room runtime/instrumentation and process-kill validation.
- [ ] Supabase Storage/PostgreSQL staging, cross-device photo replacement, and restore validation.
- [ ] APK/device validation only after source review and explicit user approval.

## Step86 – Backup photo preflight and path normalization
- [x] Defer opening the ZIP destination until every referenced local/cloud photo has passed preflight.
- [x] Ensure preflight failure cleans temporary downloads without opening/truncating the destination.
- [x] Use the same normalized photo-path key when writing ZIP part references.
- [x] Keep cloud owner validation, per-photo size bound, streaming ZIP and manifest checksums.
- [x] Step86 static regression 7/7; rerun Step73–85 and Step49–72 suites successfully.
- [ ] Validate backup and restore with real Supabase Storage on staging and a second Android device.
- [ ] Verify SAF provider failure behavior on device; ZIP writes after preflight are not transactionally atomic.
- [ ] APK/device validation only after source review and explicit user approval.
- [ ] No SQL deployment or GitHub push without explicit approval.

## Step87 – Authenticated cloud photo preview
- [x] Show local pending photo and authenticated private cloud photo in part detail.
- [x] Validate cloud object path belongs to the active owner before download.
- [x] Use authenticated Storage download, path-keyed SHA-256 cache, 20 MiB bound, and sampled bitmap decoding.
- [x] Run file/network/decode work off the main thread and surface errors in the UI.
- [x] Static regression 10/10; rerun Step49–86 suites successfully.
- [ ] Add bounded cache eviction/retention policy.
- [ ] Validate Storage policies and image preview with real authenticated sessions on two Android devices.
- [ ] APK/device validation only after source review and explicit user approval.
- [ ] No SQL deployment or GitHub push without explicit approval.

## Step88 – Bounded cloud-photo cache
- [x] Restrict cleanup to generated `.img` files inside the dedicated photo cache directory.
- [x] Enforce 100 MiB total cache budget and 30-day inactivity retention.
- [x] Evict oldest cache entries first; protect the current entry during pruning.
- [x] Serialize cache acquisition/download/pruning and retain 20 MiB per-image bound.
- [x] Static regression 11/11; rerun Step73–87 and Step49–72 suites successfully.
- [ ] Runtime-test concurrent preview decode versus cache eviction; consider pinned-file/reference-count protection.
- [ ] Validate cache misses, low-storage behavior, and authenticated image downloads on Android with real Supabase Storage.
- [ ] APK/device validation only after source review and explicit user approval.
- [ ] No SQL deployment or GitHub push without explicit approval.

## Step89 – Clear account photo cache on logout
- [x] Delete only generated `.img` files in the dedicated cloud-photo cache directory.
- [x] Serialize cache clearing with photo download/pruning.
- [x] Clear cache on manual logout and invalid restored-session handling.
- [x] Preserve local source photos, pending uploads, Room, backups, and cloud Storage objects.
- [x] Step89 static regression 8/8; rerun Step73–88 and Step49–72 suites successfully.
- [ ] Shared-device runtime test: account A views photo, logs out, account B logs in; verify no A photo remains visible or cached on disk.
- [ ] Verify decoded bitmap lifecycle and Supabase sign-out failure behavior on Android.
- [ ] APK/device validation only after source review and explicit user approval.
- [ ] No SQL deployment or GitHub push without explicit approval.

## Step90 source review – background account-session fencing
- [x] Recheck Supabase owner before each outbox operation and around Storage/RPC boundaries.
- [x] Recheck session after each pull page RPC and before every event application.
- [x] Static regression: Step90 10/10; Step49–72 and Step73–89 regression suites pass.
- [ ] Validate WorkManager behavior during real logout/account switch on Android.
- [ ] Validate two-account push/pull race against Supabase staging.
- [ ] Build/install/test APK only after source review completion and explicit user approval.


## Step91 – Admin global feed authorization
- [x] Reject NULL role explicitly in Admin global movement feed RPC.
- [x] Preserve active-profile check, authenticated-only grant, and redaction of PART payloads.
- [x] Add static + SQL NULL-semantics truth-table regression test.
- [ ] Execute migration and test Admin/KTV/NULL/inactive roles on Supabase staging after explicit approval and credentials.
- [ ] No SQL deployment or GitHub push without explicit approval.

## Step92 – profiles RLS hardening
- [x] Additive restrictive SELECT policy: self or active Admin only.
- [x] Deny authenticated direct INSERT/UPDATE/DELETE to profiles.
- [x] Keep Admin management on service-role Edge Function and password completion on SECURITY DEFINER RPC.
- [x] Static regression Step92 11/11; Step49–72 and Steps73–91 regression suites pass.
- [ ] Inspect actual Supabase base policies, grants, and profiles schema (missing from reconstructed archive) before deployment.
- [ ] Staging-test KTV self-read, KTV other-profile denial, Admin KTV-list read, direct profile write denial, Admin Edge Function writes, and password-change RPC.
- [ ] No SQL deployment or GitHub push without explicit approval.

## Step93 – Parts/movements direct owner RLS
- [x] Explicitly enable RLS on `parts` and `movements`.
- [x] Add restrictive owner predicates for authenticated direct SELECT/INSERT/UPDATE/DELETE paths (`USING` + `WITH CHECK`).
- [x] Preserve Admin global movement feed through separately authorized SECURITY DEFINER RPC; direct Admin inventory remains owner-only.
- [x] Add Step93 static verifier and rerun Step49–72 plus Step73–93 regressions.
- [ ] Inspect actual Supabase base grants/permissive policies and expected owner_id schema before migration deployment.
- [ ] Verify SECURITY DEFINER ownership/privileges and live two-KTV + Admin direct PostgREST access on staging.
- [ ] No production SQL deployment, APK build, device test, or GitHub push without explicit approval.

## Step94 source checkpoint
- [x] Additive migration to revoke direct client access to `sync_operations` ledger.
- [x] Static verification 12/12 and regression Step49–72 plus Step73–94.
- [x] Immutable source ZIP and SHA-256 created and checked.
- [ ] Before staging: inspect base ledger table owner/grants/policies and SECURITY DEFINER function-owner privileges.
- [ ] Run migration and two-account direct PostgREST tests on staging.
- [ ] No APK/device validation yet; no SQL deployment or GitHub push.

## Step95 – Storage and Admin Edge Function
- [x] Add restrictive `part-images` owner-folder + active-profile guard for all authenticated Storage operations.
- [x] Verify normal upload and restore object paths begin with owner UUID.
- [x] Reject malformed Admin Function bodies, unsupported actions, wrong input types, and unexpected fields.
- [x] Preserve active-Admin bearer verification and force newly created accounts to `ktv`.
- [x] Static verifier Step95 15/15; Step49–72 + Steps73–94 regression suites pass.
- [ ] Inspect live Storage bucket/policies and run two-account read/write/delete isolation tests on staging.
- [ ] Deploy/test `admin-users` Edge Function on staging; verify secrets and create/reset/lock/unlock behavior.
- [ ] No SQL/Function deployment, APK build, device test, or GitHub push without explicit approval.

## Step96 – Restore/Admin RPC execute scope
- [x] Explicitly revoke PUBLIC, anon, and authenticated execute grants before granting authenticated-only execute on restore staging/commit/snapshot read/verify RPCs.
- [x] Apply the same explicit grant pinning to the Admin global movement feed RPC.
- [x] Preserve SECURITY DEFINER function bodies and their internal owner/active-profile/Admin authorization checks.
- [x] Step96 static verifier 21/21; Step49–72 24/24 + SQLite smoke; all Step73–96 verifiers pass.
- [ ] Inspect exact function signatures, owners, grants and SECURITY DEFINER privileges in Supabase staging before deployment.
- [ ] Run anon/KTV/Admin restore RPC and Admin global-feed authorization tests on staging.
- [ ] No SQL deployment, APK build, device test, Edge Function deployment, or GitHub push without explicit approval.

## Step97 – Restore staging resource limits
- [x] Add owner-bound cleanup for STAGING sessions idle over 14 days; expire as ABORTED and delete only their staging chunks.
- [x] Limit each owner to one active STAGING session.
- [x] Limit combined manifest chunk count and actual staged chunk count to 8,192.
- [x] Limit cumulative active staging payload to 256 MiB per owner.
- [x] Invoke cleanup before Android begins/resumes cloud staging and recheck authenticated owner afterward.
- [x] Step97 verifier 25/25; Step49–72 24/24 + SQLite smoke; all Step73–97 regression verifiers pass.
- [ ] Test trigger concurrency, cleanup CTE, quotas, and 14-day expiry on Supabase staging with two concurrent sessions.
- [ ] Decide safe UX for reimporting a local restore journal after its server-side session expires.
- [ ] Design committed snapshot retention only after cross-device acknowledgement semantics exist; do not delete committed chunks now.
- [ ] No SQL deployment, APK build, device test, Edge Function deployment, or GitHub push without explicit approval.

## Step98 verification / deployment gates
- [x] Restore concurrency source audit and legacy multiple-STAGING commit guard added.
- [x] Step98 verifier 16/16.
- [x] Full source regression Step49–72 and Step73–98 passed.
- [x] Immutable Step98 ZIP and SHA-256 verified.
- [ ] Inspect actual Supabase catalog and identify/reconcile pre-existing multiple STAGING sessions per owner before deployment.
- [ ] Execute migration and concurrent two-device restore/fault-injection tests on staging.
- [ ] Explicit user approval required before SQL/Edge Function deployment, APK build, or GitHub push.

## Step99 – Live inventory / restore mutual exclusion
- [x] Add shared owner advisory lock to `apply_inventory_operation()` using the same owner/key-0 namespace as restore's exclusive lock.
- [x] Preserve same-part row lock, optimistic server-version CAS, operation idempotency, and atomic movement/event writes.
- [x] Verify that ordinary operations can share the lock while restore waits exclusively; later operations cannot enter ahead of a waiting restore in the concurrency model.
- [x] Step99 verifier 13/13; Step49–72 24/24 + SQLite smoke; all Step73–99 verifiers pass.
- [ ] Inspect all live Supabase inventory write paths and SECURITY DEFINER function owners/grants before deployment.
- [ ] Run real PostgreSQL staging tests with two devices: concurrent different-part mutations, same-part CAS conflict, restore-vs-mutation, cancellation/rollback, and owner isolation.
- [ ] No SQL deployment, APK build, device test, Edge Function deployment, or GitHub push without explicit approval.

## Step100 – QR/code uniqueness across devices
- [x] Add owner-scoped unique partial index for active part codes.
- [x] Fail closed if pre-existing duplicate active codes exist; no automatic data merge.
- [x] Preserve cross-owner code reuse and release code on soft delete.
- [x] Verify local Room uniqueness and transactional create/opening movement remain intact.
- [x] Step100 verifier 15/15; full Step49–72 and Step73–100 source regression passed.
- [ ] Inspect live `public.parts` schema/indexes and reconcile duplicate active owner/code rows before migration.
- [ ] Test concurrent same-owner duplicate creation, cross-owner reuse, delete/recreate, restore and legacy duplicates on Supabase staging.
- [ ] Add explicit Android UX mapping for server unique-index conflict.
- [ ] No SQL deployment, APK build, device test, Edge Function deployment, or GitHub push without explicit approval.

## Step101 – Delete / stock-change concurrency
- [x] Verify PART_DELETE uses server-version compare-and-set on active row.
- [x] Verify STOCK_CHANGE row-locks active part and atomically updates stock + inserts movement.
- [x] Verify event-feed snapshots and idempotency result are in the same RPC transaction.
- [x] Model both race orders; only one stale operation can commit; no orphan movement.
- [x] Document BLOCKED-delete reconciliation UX limitation; no unsafe automatic resolution.
- [x] Step101 verifier and full source regression passed.
- [ ] Run real PostgreSQL/Supabase two-session race tests and two-device runtime tests before deployment.
- [ ] Design explicit, history-preserving user resolution for blocked PART_DELETE vs STOCK_CHANGE conflicts.
- [ ] No SQL deployment, APK build, device test, Edge Function deployment, or GitHub push without explicit approval.

## Step103 – KEEP_SERVER reconciliation
- [x] Add fresh authenticated server snapshot reader path for isolated PART_UPSERT resolution.
- [x] Reject tombstones, owner/entity mismatch, missing/invalid version, dependent/attempted operations, active restore and local code collisions.
- [x] Apply target snapshot + resolve original blocked operation + record SERVER_APPLIED atomically in Room.
- [x] Add explicit confirmation UI; failure leaves queue blocked and data unchanged.
- [x] Step103 verifier 13/13; Step49–72 24/24 + SQLite smoke; all Step73–103 verifiers pass.
- [ ] Verify deployed snapshot RPC JSON field/timestamp contract and run Android runtime test.
- [ ] Run PostgreSQL/Supabase staging conflict-resolution tests including owner isolation, tombstone, dependency and code-collision cases.
- [ ] No SQL deployment, APK build, device test, Edge Function deployment, or GitHub push without explicit approval.

## Step104 – Outbox dependency-chain conflict guard
- [x] Reject KEEP_LOCAL/KEEP_SERVER preparation whenever any later owner-queue operation exists, regardless of entity or operation type.
- [x] Keep MANUAL_REVIEW available without mutating or deleting Outbox rows or movement history.
- [x] Retain executor-level dependency checks and immutable operation payload/ID behavior.
- [x] Step104 verifier 12/12; Step49–72 24/24 + SQLite smoke; all Step73–104 source verifiers pass.
- [ ] Design and test a future explicit, transaction-aware conflict-chain resolution flow; until then, dependent chains remain manual-only.
- [ ] Run Android runtime and PostgreSQL/Supabase staging tests before production use.
- [ ] No SQL deployment, APK build, device test, Edge Function deployment, or GitHub push without explicit approval.

## Step105 – Outbox crash/retry acknowledgement atomicity
- [x] Verify server operation ledger serializes retries, rejects changed payload under the same operation ID, and returns cached result after commit.
- [x] Verify stock mutation, movement row, sync events and idempotency result share one server transaction.
- [x] Validate exact owner/entity/type/IN_FLIGHT row before local acknowledgement.
- [x] Make local server-version rebase and SYNCED transition all-or-nothing; throw to roll back if acknowledgement update fails.
- [x] Preserve original operation ID and payload across retry/recovery; Step105 verifier 16/16.
- [x] Step49–72 24/24 + SQLite smoke; all Step73–105 source verifiers passed.
- [ ] Perform Android process-kill/network-loss fault injection around RPC response and Room commit.
- [ ] Run equivalent PostgreSQL/Supabase staging retry test and verify one movement row per operation ID.
- [ ] No SQL deployment, APK build, device test, Edge Function deployment, or GitHub push without explicit approval.

## Step106 – Logout/account-switch fence
- [x] Cancel owner-scoped push/pull WorkManager one-shot and periodic work before sign-out.
- [x] Verify shared Supabase session is cleared before clearing active profile; keep profile and reschedule sync if sign-out fails.
- [x] Propagate coroutine cancellation through sender and workers; preserve IN_FLIGHT operation ID/payload for lease recovery.
- [x] Keep account photo-cache clearing after successful session end.
- [x] Step106 verifier 13/13; Step49–72 24/24 + SQLite smoke; all Step73–106 source verifiers pass.
- [ ] Validate logout/account-switch while requests are in flight on Android devices with network fault injection.
- [ ] Verify Supabase idempotent retry after server commit + client cancellation in staging.
- [ ] No SQL deployment, APK build, device test, Edge Function deployment, or GitHub push without explicit approval.

## Step107 – Same-device KTV local-photo isolation
- [x] Store new camera/gallery pending photos under owner-specific private directories with UUID filenames.
- [x] Require owner ID in photo-copy helper and validate the owner directory.
- [x] Restrict photo upload to the active owner's canonical pending-photo directory; retain narrow legacy-path compatibility for existing Outbox rows.
- [x] Reject local photo preview paths outside app-private storage and check owner for newly scoped pending photos.
- [x] Step107 verifier 14/14; Step49–72 24/24 + SQLite smoke; all Step73–107 verifiers pass.
- [ ] Run Android runtime test with KTV A logout / KTV B login, including photo picker, camera capture, preview, pending upload, and process restart.
- [ ] Verify legacy pending-photo references drain safely and design a reference-aware migration for historical restored `part_photos` files.
- [ ] No SQL deployment, APK build, device test, Edge Function deployment, or GitHub push without explicit approval.

## Step108 – KTV + date/time backup filename
- [x] Suggested ZIP filename uses `SL` + authenticated KTV username + local `ddMMHHmm` + `.zip`.
- [x] Username is normalized and filename-safe; blank token has safe fallback.
- [x] Document picker retains user ability to rename the file.
- [x] Backup payload/manifest/restore compatibility left unchanged.
- [x] Step108 verifier 11/11; Step49–72 24/24 + SQLite smoke; all Step73–108 verifiers pass.
- [ ] Verify displayed suggested name and manual rename in Android document picker on device.
- [ ] No APK build/install, SQL deployment, Supabase staging, Edge Function deployment, or GitHub push without explicit approval.

## Step109 – Fixed account-bound backup filename
- [x] Remove rename-capable ZIP document picker; save with app-generated filename tied to signed-in KTV username.
- [x] Save to Downloads/KiemSoatLinhKien via MediaStore on Android 10+; app-specific Documents fallback on Android 8/9.
- [x] Keep backup name `SL<USERNAME><ddMMHHmm>.zip`; do not overwrite a same-name backup.
- [x] Publish MediaStore file only after ZIP write completes; delete incomplete output on failure.
- [x] Keep ZIP payload, photo preflight, checksums and restore compatibility unchanged.
- [x] Add Step109 verifier; full Step73–109 regression passed; Step49–72 24/24 + SQLite smoke passed.
- [ ] Verify actual save location and filename on Android device (not yet tested).

## Step110 – Backup metadata and safe restore preview
- [x] Add KTV username, backup timestamp, and immutable filename to ZIP v2 manifest without changing version or checksums contract.
- [x] Inspect bounded manifest on IO dispatcher before restore confirmation.
- [x] Display backup KTV, time, filename, part count, and movement count.
- [x] Reject cross-account backup before enabling confirmation; keep independent owner and full checksum/payload validation in ZipRestore.
- [x] Preserve compatibility for older v2 backups without optional metadata.
- [x] Step110 verifier 20/20; Step49–72 24/24 + SQLite smoke; all Step73–110 verifiers pass.
- [ ] Verify backup metadata preview and cross-KTV rejection on Android device with actual ZIP files.
- [ ] Test old v2 ZIP and corrupted/duplicate-entry ZIP through the actual Android picker and restore flow.
- [ ] No APK build/install, Supabase staging/deployment, SQL deployment, Edge Function deployment, or GitHub push without explicit approval.

## Step111 – Backup/restore size and entry-limit parity
- [x] Match backup limits to restore: 10,002 entries, 300 MiB total uncompressed, 100 MiB per JSONL, 20 MiB per photo, 1 MiB manifest.
- [x] Enforce aggregate and per-entry sizes against actual bytes written, including photo stream growth.
- [x] Reject >9,999 photo entries before ZIP output; retain all-photo preflight and incomplete-output cleanup.
- [x] Keep ZIP v2 metadata/checksums/owner contract and old archive compatibility unchanged.
- [x] Step111 verifier 14/14; Step49–72 24/24 + SQLite smoke; all Step73–111 verifiers pass.
- [ ] Test a near-limit archive, >limit archive, many-photo archive, low-storage failure, and cleanup on Android device.
- [ ] No APK build/install, Supabase staging/deployment, SQL deployment, Edge Function deployment, or GitHub push without explicit approval.

## Step112 – Restore temporary-space safety
- [x] Check a minimum 32 MiB free cache-space threshold before starting ZIP extraction.
- [x] Reclaim only abandoned `zip_restore_*` cache directories older than 24 hours and canonically contained in the app cache root.
- [x] Keep immediate best-effort cleanup in `finally`; do not sweep durable restore staging or user data.
- [x] Step112 verifier 10/10; Step49–72 24/24 + SQLite smoke; all Step73–112 verifiers pass.
- [ ] Test low-storage and process-kill cleanup on Android hardware/emulator.
- [ ] No APK build/install, Supabase staging/deployment, SQL deployment, Edge Function deployment, or GitHub push without explicit approval.

## Step113 – Restore cancellation and resumability
- [x] Propagate coroutine cancellation from ZIP staging without marking the durable journal as ordinary FAILED.
- [x] Preserve CLOUD_STAGING on chunk-send cancellation; retain immutable payload and idempotent chunk retry.
- [x] Preserve COMMIT_UNKNOWN when commit RPC cancellation leaves server outcome ambiguous.
- [x] Preserve SNAPSHOT_DOWNLOADING on cancellation; retry clears only partial snapshot buckets.
- [x] Do not swallow cancellation in completed-journal cleanup; retain COMPLETE journal for cleanup retry.
- [x] Step113 verifier 14/14; Step49–72 24/24 + SQLite smoke; all 41 Step verifiers passed.
- [ ] Run Android process-kill, logout, network-loss and resume fault-injection across ZIP staging, chunk upload, commit, snapshot download and post-commit cleanup.
- [ ] Verify idempotent chunk and commit retries against Supabase staging.
- [ ] No APK build/install, SQL deployment, Edge Function deployment, or GitHub push without explicit approval.

## Step114 – Restore cross-account isolation
- [x] Scope restore journal reads to both owner ID and restore ID in DAO queries and all restore phases.
- [x] Keep Room snapshot apply journal validation owner-scoped before destructive local changes.
- [x] Require canonical staging directory to be the exact direct child for its restore ID.
- [x] Preserve profile-scoped journal UI, resume owner check, owner-scoped staged-row operations, and owner-prefixed cloud photo path.
- [x] Step114 verifier 11/11; Step49–72 24/24 + SQLite smoke; all 42 Step verifiers passed.
- [ ] Test KTV A logout / KTV B login / KTV A re-login with interrupted staging on Android hardware or emulator; verify B cannot view, resume, abort, or delete A's restore.
- [ ] No APK build/install, Supabase staging/deployment, SQL deployment, Edge Function deployment, or GitHub push without explicit approval.

## Step162 — Durable weekly backup journal
- [x] Add Room v14 `weekly_backup_journal` and non-destructive 13→14 migration.
- [x] Persist stable caller-supplied backupId and owner-scoped lifecycle before ZIP creation.
- [x] Stage exact ZIP path/bytes in app-private `filesDir`; retries cannot silently regenerate READY/UPLOADING archives.
- [x] Bind uploader to journal and only clean local archive after server VERIFIED + local journal VERIFIED.
- [x] Step162 verifier 23/23; Step140–162 source/SQLite regression verifiers pass; Python compileall pass.
- [ ] Test Room 13→14 migration on Android runtime and process death at PREPARING/READY/UPLOADING/VERIFIED boundaries.
- [ ] Add safe startup reconciliation for PREPARING and VERIFIED orphan ZIP/journal states before WorkManager integration.
- [ ] Add trusted server-side retention lifecycle; do not add client-side arbitrary Storage delete.
- [ ] No Gradle, APK build/install, live SQL deployment, live TUS/RLS test, or GitHub push claimed/performed in this step.

## Step164 — Weekly backup worker contract
- [x] Owner/session/profile fence before any backup work.
- [x] Recover owner journal and reuse stable active backupId across WorkManager retries.
- [x] Use durable ZIP preparation and verified uploader; propagate cancellation; retry transient failures.
- [x] Add unique 7-day per-owner scheduler API with connected-network constraint and cancellation.
- [x] Keep scheduler disabled/not wired into login until backend contract and staging verification are approved.
- [x] Step164 verifier 22/22; Step140–164 regression 416/416; Python compileall PASS.
- [ ] Runtime test WorkManager constraints, retry, process death, account switch, logout, inactive profile and profile password-change state on Android device.
- [ ] Apply only approved backup backend SQL/bucket/RLS/RPC in staging after explicit confirmation; test TUS upload/download verification and owner isolation.
- [ ] No Gradle, APK build/install, live SQL deployment, TUS/RLS test, or GitHub push claimed/performed.

## Step165 — Weekly backup identity/lifecycle review
- [x] Add shared top-level canonical owner UUID and active-session identity helpers for recovery/worker.
- [x] Cancel only the signed-in owner's weekly unique work on explicit logout paths.
- [x] Preserve the Admin account's own warehouse backup when role changes Admin→KTV.
- [x] Keep periodic scheduling disabled until backend contract and staging verification are approved.
- [x] Step165 verifier 12/12; Step140–165 regression 428/428; Step141 SQLite smoke and Python compileall PASS.
- [ ] Kotlin/Android compile, WorkManager runtime tests, APK build/install and phone validation remain unperformed.
- [ ] Backend candidate, TUS/RLS staging tests and schedule activation remain pending explicit approval.
- [ ] No Gradle, live SQL, Storage mutation or GitHub push claimed/performed.

## Step166 — Weekly backup retry/error classification
- [x] Distinguish session/account identity changes from transient network/backend errors.
- [x] Mark terminal archive/metadata/checksum mismatches FAILED instead of retrying the same corrupt archive indefinitely.
- [x] Propagate Cloud download/network errors so WorkManager can retry; verify exact size/SHA only after successful download.
- [x] Preserve cancellation, owner fence, durable backupId/path and TUS resume fingerprint.
- [x] Step166 verifier 19/19; Step140–166 regression 448 PASS output lines / 0 FAIL; Step141 SQLite smoke and Python compileall PASS.
- [ ] Kotlin/Android compile, WorkManager runtime tests, APK build/install and phone validation remain unperformed.
- [ ] Backend candidate, TUS/RLS staging tests and periodic schedule activation remain pending.
- [ ] No Gradle, live SQL/Storage mutation or GitHub push claimed/performed.

## Step167 — Weekly backup stale-journal lifecycle diagnostics
- [x] Add pure age/state classification for PREPARING, READY/UPLOADING, VERIFIED, FAILED and ABANDONED.
- [x] Keep stale READY/UPLOADING resumable after long offline periods; no age-based abandonment or archive replacement.
- [x] Keep FAILED/ABANDONED audit rows; no automatic local or Cloud deletion.
- [x] Report stale/recoverable and terminal-retained counts from owner-scoped recovery.
- [x] Step167 verifier 17/17; Step140–166 regression retained 448 PASS / 0 FAIL; Step141 + Step162 SQLite smoke PASS; Python compileall PASS.
- [ ] Kotlin/Android compile, WorkManager runtime test, APK build/install and phone validation remain unperformed.
- [ ] Backend candidate approval/application, TUS/RLS staging test and weekly schedule activation remain pending.
- [ ] No Gradle, live SQL/Storage mutation or GitHub push claimed/performed.

## Step168 — Weekly backup low-storage protection
- [x] Guard weekly ZIP output against low free space and cap archive size at 256 MiB.
- [x] Preserve 16 MiB reserve during writes; low-storage errors remain retryable.
- [x] Preserve PREPARING journal identity and clean partial ZIP on failure; never touch READY/UPLOADING archive bytes.
- [x] Preflight cloud-photo cache and verification temp storage; clean verification temp after transfer.
- [x] Step168 verifier 18/18; Step140–167 regression 465 PASS / 0 FAIL; Step141 + Step162 SQLite smoke and Python compileall PASS.
- [ ] Kotlin/Android compile, WorkManager runtime test, APK build/install and phone validation remain unperformed.
- [ ] Backend candidate approval/application, TUS/RLS staging test and weekly schedule activation remain pending.
- [ ] No Gradle, live SQL/Storage mutation or GitHub push claimed/performed.
