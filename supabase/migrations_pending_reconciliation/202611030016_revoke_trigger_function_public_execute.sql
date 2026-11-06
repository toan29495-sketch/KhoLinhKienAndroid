-- Step138: trigger/event-trigger functions are not public RPC endpoints.
-- Revoking EXECUTE from client roles does not disable the already-installed
-- auth.users trigger or ensure_rls event trigger; PostgreSQL checks the
-- privilege when the trigger is created, not on each trigger invocation.
-- is_app_admin() intentionally remains executable by authenticated because
-- existing RLS policies use it as a narrowly scoped predicate.
begin;

revoke all privileges on function public.handle_new_user()
    from public, anon, authenticated;
revoke all privileges on function public.rls_auto_enable()
    from public, anon, authenticated;

commit;
