# Step91 – NULL-safe Admin global feed authorization (2026-11-03)

## Finding
The Admin global movement feed RPC checked `if lower(v_role) <> 'admin' then`. PostgreSQL uses three-valued logic: when `v_role` is NULL, the condition evaluates to NULL. PL/pgSQL only enters an IF branch when the condition is TRUE, so a NULL role would not be rejected by this condition. A profile normally should have a role, but the authorization check must not rely on that schema assumption.

## Change
- Added additive migration `supabase/migrations/202611030003_null_safe_admin_feed_role.sql`.
- Replaced the RPC with the explicit guard `if v_role is null or lower(v_role) <> 'admin' then`.
- Kept `auth.uid()` lookup, active-profile requirement, cursor validation, PART redaction to SKIP, and execute grant limited to `authenticated`.
- Kept the original migration unchanged for migration history.

## Verification
- `tools/verify_step91_admin_role_null_guard.py`: 10/10 PASS (static checks and NULL truth-table model).
- Regression suites Step49–72 and Step73–90 rerun; results recorded in this checkpoint's progress log.
- ZIP integrity and SHA-256 verified.

## Limits
- No PostgreSQL parser/server or Supabase staging execution was performed. Static tests do not prove migration execution against the deployed schema.
- No live Admin/KTV/inactive/NULL-role authorization test, SQL deployment, APK build, Android runtime test, or GitHub push.
- Source is the reconstructed Step49–72 lineage, not byte-for-byte restoration of lost original source.
