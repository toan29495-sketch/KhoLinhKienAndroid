#!/usr/bin/env python3
"""Source-contract checks for the Step204/205 live Supabase reconciliation."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
fn = (ROOT / "supabase/functions/admin-users/index.ts").read_text()
manifest = (ROOT / "supabase/REMOTE_MIGRATION_MANIFEST.md").read_text()
review = (ROOT / "STEP204_205_SUPABASE_RECONCILIATION.md").read_text()
progress = (ROOT / "PROGRESS.md").read_text()
active = list((ROOT / "supabase/migrations").glob("*.sql"))
snap_dir = ROOT / "supabase/remote_migration_snapshots"
snapshots = {p.name: p.read_text() for p in snap_dir.glob("*.sql")}
checks = []

def check(name, condition):
    checks.append((name, bool(condition)))

# Deployed Edge Function v6 source contract.
check("manual bearer token extraction retained", "authorization.match(/^Bearer\\s+(.+)$/i)" in fn)
check("manual Supabase Auth getUser validation", "callerClient.auth.getUser(tokenMatch[1])" in fn)
check("caller must be active Admin", 'callerProfile.role !== "admin"' in fn and "callerProfile.is_active !== true" in fn)
check("caller must have completed forced password change", "callerProfile.must_change_password === true" in fn)
check("immutable app_admins membership enforced", '.from("app_admins")' in fn and "if (!adminLink)" in fn)
check("account actions remain allowlisted", '["create", "reset_password", "lock", "unlock"]' in fn)
check("audit STARTED inserted before privileged mutation", 'status: "STARTED"' in fn and fn.index('status: "STARTED"') < fn.index('auth.admin.createUser'))
check("audit success state written", 'status: "SUCCEEDED"' in fn)
check("audit failure state written", 'status: "FAILED"' in fn)
check("audit failure code bounded", 'error_code: "ACTION_FAILED"' in fn)
check("reset guard upsert before Auth password update", fn.index('.from("password_reset_guard").upsert') < fn.index('auth.admin.updateUserById'))
check("reset guard removed after Auth update", '.from("password_reset_guard").delete()' in fn)
check("temporary password remains 000000", 'password: "000000"' in fn)

# Reconstructed remote Step204/205 SQL source snapshots.
s204 = snapshots.get("20261103161808_step204_enforce_forced_password_change.sql", "")
s204a = snapshots.get("20261103162008_step204_01_reject_temporary_password_completion.sql", "")
s205 = snapshots.get("20261103162229_step205_admin_account_audit_log.sql", "")
s205a = snapshots.get("20261103162458_step205_01_limit_audit_table_mutations.sql", "")
check("all four remote migration snapshots exist", len(snapshots) == 4)
check("Step204 guard table is RLS enabled", "alter table public.password_reset_guard enable row level security" in s204)
check("Step204 guard grants are service-role only", "from public, anon, authenticated" in s204 and "to service_role" in s204)
check("Step204 Auth password trigger exists", "on_auth_user_password_changed" in s204 and "after update of encrypted_password" in s204)
check("Step204 temporary password hash comparison exists", "extensions.crypt('000000', new.encrypted_password)" in s204)
check("Step204 active-profile predicate requires changed password", "p.must_change_password = false" in s204)
check("Step204 Admin predicate requires app_admins link", "from public.app_admins a" in s204 and "p.must_change_password = false" in s204)
check("Step204_01 completion RPC is verification-only", "PASSWORD_CHANGE_NOT_CONFIRMED" in s204a and "set must_change_password" not in s204a.lower())
check("Step205 audit action constraint exists", "'create', 'reset_password', 'lock', 'unlock'" in s205)
check("Step205 audit status constraint exists", "'STARTED', 'SUCCEEDED', 'FAILED'" in s205)
check("Step205 finish-state constraint exists", "admin_audit_finish_state_check" in s205 and "finished_at is null" in s205)
check("Step205 Admin audit RPC checks active Admin", "public.is_active_profile()" in s205 and "public.is_app_admin()" in s205)
check("Step205 audit RPC caps page size at 200", "p_limit > 200" in s205)
check("Step205_01 denies direct client table access", "from public, anon, authenticated, service_role" in s205a)
check("Step205_01 omits service-role DELETE", "grant select, insert, update on table public.admin_audit_events to service_role" in s205a and "grant delete" not in s205a.lower())
check("snapshots explicitly warn not to execute", all("Do not execute against production" in s for s in snapshots.values()))
check("snapshots remain outside active migrations", all("step204" not in p.name.lower() and "step205" not in p.name.lower() for p in active))

# Provenance and known UI gap.
check("manifest lists all four remote Step204/205 versions", all(x in manifest for x in ["20261103161808", "20261103162008", "20261103162229", "20261103162458"]))
check("Step139 migration-history discrepancy is recorded", "none of the nine Step139 version strings" in manifest)
check("backend audit UI gap is recorded", "not viewable in the app yet" in review)
check("read-only/no-deploy scope is recorded", "No deploy or database mutation" in review and "No migration, SQL, Edge Function deployment" in review)
check("progress contains reconciliation record", "Step204–Step205 — Supabase remote/source reconciliation" in progress)

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'}: {name}")
print(f"RESULT: {len(checks)-len(failed)}/{len(checks)} PASS")
if failed:
    raise SystemExit(1)
