begin;
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values('part-images','part-images',false,20971520,array['image/jpeg','image/png','image/webp']::text[])
on conflict(id) do update set public=false,file_size_limit=excluded.file_size_limit,allowed_mime_types=excluded.allowed_mime_types;
alter table public.sync_operations add column if not exists payload jsonb, add column if not exists result jsonb, add column if not exists processed_at timestamptz;
create table if not exists public.inventory_sync_events(
 event_id bigserial primary key, owner_id uuid not null references auth.users(id) on delete cascade,
 entity_type text not null check(entity_type in ('PART','MOVEMENT','RESTORE_COMMIT')),
 entity_id uuid not null, payload jsonb not null, created_at timestamptz not null default now());
create index if not exists inventory_sync_events_owner_event_idx on public.inventory_sync_events(owner_id,event_id);
alter table public.inventory_sync_events enable row level security;
revoke all privileges on table public.inventory_sync_events from public,anon,authenticated;
create or replace function public.is_active_profile() returns boolean language sql stable security definer
set search_path=pg_catalog,public,auth as $$ select exists(select 1 from public.profiles p where p.id=(select auth.uid()) and p.is_active=true); $$;
revoke all privileges on function public.is_active_profile() from public,anon;
grant execute on function public.is_active_profile() to authenticated;
do $$ declare t text; begin foreach t in array array['profiles','parts','movements','sync_operations'] loop
 execute format('drop policy if exists require_active_profile on public.%I',t);
 execute format('create policy require_active_profile on public.%I as restrictive for all to authenticated using (public.is_active_profile()) with check (public.is_active_profile())',t);
end loop; end $$;
create or replace function public.is_active_admin_profile() returns boolean language sql stable security definer
set search_path=pg_catalog,public,auth as $$ select exists(select 1 from public.profiles p where p.id=(select auth.uid()) and p.is_active and lower(coalesce(p.role,''))='admin'); $$;
revoke all privileges on function public.is_active_admin_profile() from public,anon;
grant execute on function public.is_active_admin_profile() to authenticated;
drop policy if exists profiles_select_self_or_active_admin on public.profiles;
create policy profiles_select_self_or_active_admin on public.profiles as restrictive for select to authenticated using(id=(select auth.uid()) or (select public.is_active_admin_profile()));
drop policy if exists profiles_block_client_insert on public.profiles;
create policy profiles_block_client_insert on public.profiles as restrictive for insert to authenticated with check(false);
drop policy if exists profiles_block_client_update on public.profiles;
create policy profiles_block_client_update on public.profiles as restrictive for update to authenticated using(false) with check(false);
drop policy if exists profiles_block_client_delete on public.profiles;
create policy profiles_block_client_delete on public.profiles as restrictive for delete to authenticated using(false);
drop policy if exists parts_owner_scope_authenticated on public.parts;
create policy parts_owner_scope_authenticated on public.parts as restrictive for all to authenticated using(owner_id=(select auth.uid())) with check(owner_id=(select auth.uid()));
drop policy if exists movements_owner_scope_authenticated on public.movements;
create policy movements_owner_scope_authenticated on public.movements as restrictive for all to authenticated using(owner_id=(select auth.uid())) with check(owner_id=(select auth.uid()));
revoke all privileges on table public.sync_operations from public,anon,authenticated;
revoke all privileges on table public.app_update_config from public,anon,authenticated;
grant select,insert,update,delete on table public.app_update_config to authenticated;
drop policy if exists part_images_owner_scope_guard on storage.objects;
create policy part_images_owner_scope_guard on storage.objects as restrictive for all to authenticated
using(bucket_id<>'part-images' or (public.is_active_profile() and coalesce((storage.foldername(name))[1],'')=(select auth.uid())::text))
with check(bucket_id<>'part-images' or (public.is_active_profile() and coalesce((storage.foldername(name))[1],'')=(select auth.uid())::text));
drop policy if exists part_images_no_authenticated_delete on storage.objects;
create policy part_images_no_authenticated_delete on storage.objects as restrictive for delete to anon,authenticated using(bucket_id<>'part-images');
alter table public.parts drop constraint if exists parts_owner_id_code_key;
drop index if exists public.parts_owner_id_code_key;
create unique index if not exists parts_owner_active_code_uidx on public.parts(owner_id,code) where deleted_at is null;
revoke all privileges on function public.handle_new_user() from public,anon,authenticated;
revoke all privileges on function public.rls_auto_enable() from public,anon,authenticated;
commit;
