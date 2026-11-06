-- Historical Step160 SQL baseline, deployed remotely in Step187.
-- The begin RPC ambiguity in this baseline is corrected by the following Step187.01 migration.
-- No service-role key is used by the Android client.

begin;

-- Dedicated private bucket. If it already exists with different settings, stop
-- rather than silently changing a bucket that may contain user data.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'inventory-backups',
  'inventory-backups',
  false,
  268435456,
  array['application/zip']::text[]
)
on conflict (id) do nothing;

do $$
declare
  v_bucket record;
begin
  select id, name, public, file_size_limit, allowed_mime_types
    into v_bucket
  from storage.buckets
  where id = 'inventory-backups';

  if not found
     or v_bucket.name <> 'inventory-backups'
     or v_bucket.public is distinct from false
     or v_bucket.file_size_limit is distinct from 268435456
     or v_bucket.allowed_mime_types is distinct from array['application/zip']::text[] then
    raise exception 'INVENTORY_BACKUP_BUCKET_CONFIG_MISMATCH';
  end if;
end;
$$;

create table public.inventory_backup_objects (
  backup_id uuid primary key,
  owner_id uuid not null references auth.users(id),
  backup_kind text not null check (backup_kind in ('WEEKLY_AUTO', 'MONTHLY_AUTO', 'PRE_UPDATE')),
  status text not null default 'PENDING' check (status in ('PENDING', 'VERIFIED', 'FAILED', 'DELETED')),
  storage_path text not null unique,
  snapshot_cursor bigint not null check (snapshot_cursor >= 0),
  archive_size_bytes bigint not null check (archive_size_bytes > 0 and archive_size_bytes <= 268435456),
  sha256 text not null check (sha256 ~ '^[a-f0-9]{64}$'),
  part_count integer not null check (part_count >= 0 and part_count <= 500000),
  movement_count integer not null check (movement_count >= 0 and movement_count <= 1000000),
  verification_method text check (verification_method in ('CLIENT_DOWNLOAD_SHA256')),
  created_at timestamptz not null default now(),
  upload_authorized_until timestamptz not null default (now() + interval '24 hours'),
  verified_at timestamptz,
  deleted_at timestamptz,
  constraint inventory_backup_verified_fields_check check (
    (status = 'VERIFIED' and verified_at is not null and verification_method = 'CLIENT_DOWNLOAD_SHA256')
    or status <> 'VERIFIED'
  ),
  constraint inventory_backup_deleted_fields_check check (
    (status = 'DELETED' and deleted_at is not null) or status <> 'DELETED'
  )
);

create index inventory_backup_owner_created_idx
  on public.inventory_backup_objects (owner_id, created_at desc);
create index inventory_backup_owner_kind_status_idx
  on public.inventory_backup_objects (owner_id, backup_kind, status, created_at desc);

alter table public.inventory_backup_objects enable row level security;
revoke all on table public.inventory_backup_objects from public, anon, authenticated;

-- Storage INSERT policy calls this checked helper so an authenticated user
-- cannot upload unregistered/orphan objects merely by guessing a UUID path.
create or replace function public.can_upload_inventory_backup_object(p_name text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select public.is_active_profile()
     and exists (
       select 1
       from public.inventory_backup_objects b
       where b.owner_id = (select auth.uid())
         and b.storage_path = p_name
         and b.status = 'PENDING'
         and b.upload_authorized_until >= now()
     );
$$;
revoke all on function public.can_upload_inventory_backup_object(text) from public, anon, authenticated;
grant execute on function public.can_upload_inventory_backup_object(text) to authenticated;


-- Clients can only create immutable objects under their own UUID folder.
-- There is deliberately no UPDATE or DELETE policy for this bucket.
create policy inventory_backup_owner_insert
on storage.objects
as permissive
for insert
to authenticated
with check (
  bucket_id = 'inventory-backups'
  and public.is_active_profile()
  and public.can_upload_inventory_backup_object(name)
  and (storage.foldername(name))[1] = (select auth.uid())::text
  and coalesce(array_length(storage.foldername(name), 1), 0) = 1
  and storage.filename(name) ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.zip$'
);

-- SELECT is needed for owner-only listing and authenticated download. The
-- operation-aware filter prevents a list-only policy from becoming a download
-- policy or vice versa for unrelated Storage SELECT operations.
create policy inventory_backup_owner_select
on storage.objects
as permissive
for select
to authenticated
using (
  bucket_id = 'inventory-backups'
  and public.is_active_profile()
  and owner_id = (select auth.uid())::text
  and storage.allow_any_operation(array[
    'object.list',
    'storage.object.get_authenticated'
  ])
);

-- Begin registration: the server derives owner and object path; caller cannot
-- register an arbitrary owner's path or a caller-chosen path. Repeating the
-- exact begin request is idempotent; reusing an ID with different metadata is denied.
create or replace function public.begin_inventory_backup(
  p_backup_id uuid,
  p_backup_kind text,
  p_snapshot_cursor bigint,
  p_archive_size_bytes bigint,
  p_sha256 text,
  p_part_count integer,
  p_movement_count integer
)
returns table (backup_id uuid, storage_path text, status text)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_owner uuid := (select auth.uid());
  v_path text;
  v_existing public.inventory_backup_objects%rowtype;
begin
  if v_owner is null then raise exception 'AUTH_REQUIRED' using errcode = '42501'; end if;
  if not public.is_active_profile() then raise exception 'PROFILE_INACTIVE' using errcode = '42501'; end if;
  if p_backup_id is null
     or p_backup_kind not in ('WEEKLY_AUTO', 'MONTHLY_AUTO', 'PRE_UPDATE')
     or p_snapshot_cursor < 0
     or p_archive_size_bytes <= 0 or p_archive_size_bytes > 268435456
     or p_sha256 !~ '^[a-f0-9]{64}$'
     or p_part_count < 0 or p_part_count > 500000
     or p_movement_count < 0 or p_movement_count > 1000000 then
    raise exception 'INVALID_BACKUP_METADATA' using errcode = '22023';
  end if;

  v_path := v_owner::text || '/' || p_backup_id::text || '.zip';
  insert into public.inventory_backup_objects (
    backup_id, owner_id, backup_kind, storage_path, snapshot_cursor,
    archive_size_bytes, sha256, part_count, movement_count
  ) values (
    p_backup_id, v_owner, p_backup_kind, v_path, p_snapshot_cursor,
    p_archive_size_bytes, p_sha256, p_part_count, p_movement_count
  ) on conflict (backup_id) do nothing;

  select * into v_existing
  from public.inventory_backup_objects b
  where b.backup_id = p_backup_id and b.owner_id = v_owner
  for update;
  if not found then raise exception 'BACKUP_ID_CONFLICT' using errcode = '23505'; end if;
  if v_existing.backup_kind is distinct from p_backup_kind
     or v_existing.storage_path is distinct from v_path
     or v_existing.snapshot_cursor is distinct from p_snapshot_cursor
     or v_existing.archive_size_bytes is distinct from p_archive_size_bytes
     or v_existing.sha256 is distinct from p_sha256
     or v_existing.part_count is distinct from p_part_count
     or v_existing.movement_count is distinct from p_movement_count then
    raise exception 'BACKUP_ID_METADATA_CONFLICT' using errcode = '23505';
  end if;

  -- A retried begin call refreshes the short-lived upload authorization window.
  -- The TUS upload URL itself also expires after 24 hours.
  if v_existing.status = 'PENDING' then
    update public.inventory_backup_objects b
    set upload_authorized_until = now() + interval '24 hours'
    where b.backup_id = p_backup_id and b.owner_id = v_owner
    returning * into v_existing;
  end if;

  return query select v_existing.backup_id, v_existing.storage_path, v_existing.status;
end;
$$;

-- Complete only after the Android client has downloaded the uploaded object
-- to a private temporary file and independently matched size + SHA-256.
-- This records CLIENT verification; PostgreSQL does not recompute object bytes.
create or replace function public.complete_inventory_backup(
  p_backup_id uuid,
  p_verified_sha256 text
)
returns table (backup_id uuid, status text, verified_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_owner uuid := (select auth.uid());
  v_backup public.inventory_backup_objects%rowtype;
  v_object_size bigint;
  v_verified_at timestamptz;
begin
  if v_owner is null then raise exception 'AUTH_REQUIRED' using errcode = '42501'; end if;
  if not public.is_active_profile() then raise exception 'PROFILE_INACTIVE' using errcode = '42501'; end if;

  select * into v_backup
  from public.inventory_backup_objects b
  where b.backup_id = p_backup_id and b.owner_id = v_owner
  for update;
  if not found then raise exception 'BACKUP_NOT_FOUND' using errcode = 'P0002'; end if;

  if v_backup.status = 'VERIFIED' then
    if p_verified_sha256 is distinct from v_backup.sha256 then
      raise exception 'BACKUP_CHECKSUM_MISMATCH' using errcode = '22000';
    end if;
    return query select v_backup.backup_id, v_backup.status, v_backup.verified_at;
    return;
  end if;
  if v_backup.status <> 'PENDING' then raise exception 'BACKUP_NOT_PENDING' using errcode = '55000'; end if;
  if p_verified_sha256 is distinct from v_backup.sha256 then
    raise exception 'BACKUP_CHECKSUM_MISMATCH' using errcode = '22000';
  end if;

  select case
           when coalesce(o.metadata->>'size', '') ~ '^[0-9]+$'
             then (o.metadata->>'size')::bigint
           else null
         end
    into v_object_size
  from storage.objects o
  where o.bucket_id = 'inventory-backups'
    and o.name = v_backup.storage_path
    and o.owner_id = v_owner::text
    and coalesce(o.is_delete_marker, false) = false
    and o.archived_at is null;

  if v_object_size is null or v_object_size <> v_backup.archive_size_bytes then
    raise exception 'BACKUP_OBJECT_MISSING_OR_SIZE_MISMATCH' using errcode = '22000';
  end if;

  v_verified_at := now();
  update public.inventory_backup_objects b
  set status = 'VERIFIED',
      verification_method = 'CLIENT_DOWNLOAD_SHA256',
      verified_at = v_verified_at
  where b.backup_id = p_backup_id and b.owner_id = v_owner;

  return query select p_backup_id, 'VERIFIED'::text, v_verified_at;
end;
$$;

create or replace function public.list_my_inventory_backups()
returns table (
  backup_id uuid,
  backup_kind text,
  status text,
  storage_path text,
  snapshot_cursor bigint,
  archive_size_bytes bigint,
  sha256 text,
  part_count integer,
  movement_count integer,
  created_at timestamptz,
  verified_at timestamptz,
  deleted_at timestamptz
)
language sql
stable
security definer
set search_path = ''
as $$
  select b.backup_id, b.backup_kind, b.status, b.storage_path,
         b.snapshot_cursor, b.archive_size_bytes, b.sha256,
         b.part_count, b.movement_count, b.created_at, b.verified_at, b.deleted_at
  from public.inventory_backup_objects b
  where b.owner_id = (select auth.uid())
    and public.is_active_profile()
  order by b.created_at desc, b.backup_id desc;
$$;

-- Admin status view intentionally omits storage_path and all download data.
create or replace function public.list_inventory_backup_status_admin()
returns table (
  owner_id uuid,
  username text,
  display_name text,
  backup_id uuid,
  backup_kind text,
  status text,
  snapshot_cursor bigint,
  archive_size_bytes bigint,
  part_count integer,
  movement_count integer,
  created_at timestamptz,
  verified_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
begin
  if v_uid is null or not public.is_active_profile() or not public.is_app_admin() then
    raise exception 'ADMIN_REQUIRED' using errcode = '42501';
  end if;

  return query
  select b.owner_id, p.username, p.display_name,
         b.backup_id, b.backup_kind, b.status, b.snapshot_cursor,
         b.archive_size_bytes, b.part_count, b.movement_count,
         b.created_at, b.verified_at
  from public.inventory_backup_objects b
  join public.profiles p on p.id = b.owner_id
  order by b.created_at desc, b.backup_id desc;
end;
$$;

revoke all on function public.begin_inventory_backup(uuid, text, bigint, bigint, text, integer, integer) from public, anon, authenticated;
revoke all on function public.complete_inventory_backup(uuid, text) from public, anon, authenticated;
revoke all on function public.list_my_inventory_backups() from public, anon, authenticated;
revoke all on function public.list_inventory_backup_status_admin() from public, anon, authenticated;
grant execute on function public.begin_inventory_backup(uuid, text, bigint, bigint, text, integer, integer) to authenticated;
grant execute on function public.complete_inventory_backup(uuid, text) to authenticated;
grant execute on function public.list_my_inventory_backups() to authenticated;
grant execute on function public.list_inventory_backup_status_admin() to authenticated;

commit;
