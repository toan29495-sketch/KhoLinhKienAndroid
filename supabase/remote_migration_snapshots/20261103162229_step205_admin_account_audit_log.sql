-- RECONSTRUCTED REMOTE CATALOG SNAPSHOT — NOT ORIGINAL MIGRATION SOURCE.
-- Remote migration: 20261103162229_step205_admin_account_audit_log
-- Do not execute against production. See this directory's README.md.

create table if not exists public.admin_audit_events (
  id uuid primary key default gen_random_uuid(),
  actor_id uuid not null references auth.users(id),
  actor_username text not null,
  target_user_id uuid references auth.users(id) on delete set null,
  target_username text not null,
  action text not null check (action in ('create', 'reset_password', 'lock', 'unlock')),
  status text not null check (status in ('STARTED', 'SUCCEEDED', 'FAILED')),
  error_code text,
  created_at timestamptz not null default now(),
  finished_at timestamptz,
  constraint admin_audit_finish_state_check check (
    (status = 'STARTED' and finished_at is null)
    or (status <> 'STARTED' and finished_at is not null)
  )
);

comment on table public.admin_audit_events is
  'Internal Admin account-management audit trail. Service-role writes; authenticated Admin reads only through list_admin_audit_events RPC. Never stores passwords or tokens.';

create index if not exists admin_audit_events_created_idx
  on public.admin_audit_events (created_at desc, id desc);
create index if not exists admin_audit_events_target_idx
  on public.admin_audit_events (target_username, created_at desc);

alter table public.admin_audit_events enable row level security;

create or replace function public.list_admin_audit_events(p_limit integer default 100)
returns table (
  id uuid,
  actor_username text,
  target_username text,
  action text,
  status text,
  error_code text,
  created_at timestamptz,
  finished_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $function$
begin
  if auth.uid() is null or not public.is_active_profile() or not public.is_app_admin() then
    raise exception 'ADMIN_REQUIRED' using errcode = '42501';
  end if;
  if p_limit is null or p_limit < 1 or p_limit > 200 then
    raise exception 'INVALID_LIMIT' using errcode = '22023';
  end if;

  return query
  select e.id, e.actor_username, e.target_username, e.action, e.status,
         e.error_code, e.created_at, e.finished_at
  from public.admin_audit_events e
  order by e.created_at desc, e.id desc
  limit p_limit;
end;
$function$;

revoke all privileges on function public.list_admin_audit_events(integer) from public, anon;
grant execute on function public.list_admin_audit_events(integer) to authenticated, service_role;
