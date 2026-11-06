# Step92 – Harden profiles RLS (2026-11-03)

## Scope
Audit of `profiles` access after reviewing `AuthRepository.getCurrentProfile()`, `getKtvUsers()`, the `admin-users` Edge Function, and the existing active-profile migration.

## Finding
The repository snapshot does not contain the original/base migration that created `profiles` or its initial permissive RLS policies. Therefore, the exact prior policy set and table grants cannot be reconstructed from this source archive. UI gating of the Admin KTV screen is not a security boundary, and the application directly reads `profiles` for the current user and for the Admin KTV list.

## Change
Added additive migration `supabase/migrations/202611030004_lock_down_profile_access.sql`:
- Explicitly enables RLS on `public.profiles`.
- Adds `is_active_admin_profile()` as a `STABLE SECURITY DEFINER` helper bound to `auth.uid()`, requiring an active profile and case-insensitive Admin role.
- Revokes helper execution from PUBLIC/anon and grants it only to authenticated.
- Adds a RESTRICTIVE SELECT policy allowing a user to read their own profile, or an active Admin to read profiles for the KTV management list. Because it is RESTRICTIVE, it narrows rather than grants access beyond existing permissive SELECT policies.
- Adds RESTRICTIVE INSERT/UPDATE/DELETE policies with false predicates for authenticated clients. Profile creation/management remains on the service-role `admin-users` Edge Function; password completion remains in `complete_password_change()` SECURITY DEFINER RPC.
- Existing migration files are not modified.

## Verification
- `tools/verify_step92_profile_rls.py`: 11/11 static assertions PASS.
- Re-ran Step49–72 static checks (24/24) and SQLite smoke test.
- Re-ran all regression scripts Step73–92; all passed (Step73 5/5, Step74 5/5, Step75 7/7, Step76 7/7, Step77 7/7 + model, Step78 7/7 + model, Step79 12/12, Step80 16/16, Step81 20/20, Step82 14/14, Step83 16/16, Step84 20/20, Step85 13/13, Step86 7/7, Step87 10/10, Step88 11/11, Step89 8/8, Step90 10/10, Step91 10/10, Step92 11/11).

## Important limitations / pending validation
- Static review only; migration has NOT been executed against PostgreSQL/Supabase staging.
- Since base schema/policy migrations are absent from this reconstructed archive, existing permissive `profiles` SELECT policies and table grants must be inspected in the actual Supabase project before deployment. The restrictive SELECT policy narrows RLS policies but does not by itself remove broad SQL grants; RLS still applies to authenticated table access.
- Validate that the project uses the expected `profiles` schema/columns (`id`, `role`, `is_active`) and that `admin-users` service-role calls and `complete_password_change()` SECURITY DEFINER function owner can perform their intended writes.
- No live Admin/KTV direct PostgREST tests, SQL deployment, APK build, Android device test, or GitHub push.
- Source lineage remains reconstructed Step49–72 branch, not byte-for-byte restoration of missing original source.
