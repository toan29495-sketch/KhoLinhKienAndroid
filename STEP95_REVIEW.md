# Step95 – Storage owner-scope and Admin Edge Function hardening (2026-11-03)

## Scope
Review private `part-images` Storage policies and the `admin-users` Edge Function after profile, inventory and sync-ledger hardening.

## Findings and changes
- Existing Storage policies were permissive owner-folder policies. PostgreSQL combines permissive policies with OR semantics, so an unrelated broad permissive policy could widen access unless an independent restrictive guard exists.
- Added `202611030007_lock_down_part_image_storage_scope.sql` with a `RESTRICTIVE FOR ALL TO authenticated` policy. For `part-images`, both `USING` and `WITH CHECK` require an active profile and first path segment exactly equal to `auth.uid()`. Other buckets are unaffected.
- Normal upload paths use `<ownerId>/<partSyncId>/<operationId>.<ext>`; restore paths use `<ownerId>/restore-<restoreId>/<digest>.<ext>`, matching the enforced first-folder owner scope.
- Hardened `admin-users` request parsing: body must be a JSON object, action must be one of create/reset_password/lock/unlock, username must be a string matching the allowlist, create requires a string display_name, and unexpected fields are rejected. This avoids permissive coercion and ambiguous extra input.
- Existing server authorization remains: bearer token validated with `auth.getUser`, profile loaded using service role, only active Admin accepted, and create hardcodes role `ktv`.

## Verification
- `tools/verify_step95_storage_admin_guard.py`: 15/15 static checks PASS.
- Step49–72: 24/24 static checks + SQLite atomic replacement smoke PASS.
- Every Step73–94 verifier rerun and passed.
- ZIP integrity and SHA-256 verified for the Step95 checkpoint.

## Limitations / deployment prerequisites
- No Supabase Storage/PostgreSQL staging run or live two-account upload/download/delete test was performed.
- Base Storage policy history is incomplete in the reconstructed archive. The new restrictive guard narrows access even if broader permissive policies exist, but deployment must still inspect actual policies, bucket settings, grants, and active-profile helper behavior.
- `admin-users` Edge Function was source-reviewed and statically checked only; it was not deployed or invoked against staging. Service-role secret storage and Edge Function deployment configuration remain unverified.
- No SQL deployment, Edge Function deployment, APK build, Android device test, GitHub push, or production deployment.
- Source lineage remains reconstructed Step49–72 branch, not byte-for-byte restoration of the missing original source.
