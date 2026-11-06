begin;
create or replace function public.apply_inventory_operation(p_operation_id uuid,p_entity_type text,p_entity_id uuid,p_payload jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog,public,auth as $$
declare
 v_owner uuid:=auth.uid(); v_existing public.sync_operations%rowtype; v_part public.parts%rowtype; v_movement public.movements%rowtype;
 v_code text; v_name text; v_quantity integer; v_min_quantity integer; v_unit text; v_photo_path text; v_note text; v_tracking_mode text;
 v_type text; v_amount integer; v_expected_server_version bigint; v_before integer; v_after integer; v_reason text; v_machine text; v_movement_id uuid; v_result jsonb;
begin
 if v_owner is null then raise exception 'AUTH_REQUIRED' using errcode='28000'; end if;
 if not public.is_active_profile() then raise exception 'PROFILE_INACTIVE' using errcode='42501'; end if;
 perform pg_advisory_xact_lock_shared(hashtextextended(v_owner::text,0));
 if p_operation_id is null or p_entity_id is null or p_payload is null then raise exception 'INVALID_OPERATION'; end if;
 if p_entity_type is null or p_entity_type not in ('PART_UPSERT','PART_DELETE','STOCK_CHANGE') then raise exception 'UNSUPPORTED_OPERATION_TYPE'; end if;
 insert into public.sync_operations(operation_id,owner_id,entity_type,entity_id,payload) values(p_operation_id,v_owner,p_entity_type,p_entity_id,p_payload) on conflict(operation_id) do nothing;
 select * into v_existing from public.sync_operations where operation_id=p_operation_id for update;
 if v_existing.owner_id<>v_owner or v_existing.entity_type<>p_entity_type or v_existing.entity_id<>p_entity_id then raise exception 'OPERATION_ID_CONFLICT' using errcode='23505'; end if;
 if v_existing.payload is distinct from p_payload then raise exception 'OPERATION_PAYLOAD_CONFLICT' using errcode='23505'; end if;
 if v_existing.processed_at is not null then return coalesce(v_existing.result,jsonb_build_object('status','already_processed')); end if;
 if p_entity_type='PART_UPSERT' then
  v_code:=nullif(btrim(p_payload->>'code'),''); v_name:=nullif(btrim(p_payload->>'name'),''); v_quantity:=(p_payload->>'quantity')::integer;
  v_min_quantity:=coalesce((p_payload->>'min_quantity')::integer,0); v_unit:=coalesce(nullif(btrim(p_payload->>'unit'),''),'cái');
  v_photo_path:=coalesce(p_payload->>'photo_path',''); v_note:=coalesce(p_payload->>'note',''); v_tracking_mode:=coalesce(p_payload->>'tracking_mode','SINGLE');
  if v_code is null or v_name is null or v_quantity is null or v_quantity<0 or v_min_quantity<0 or v_tracking_mode not in ('SINGLE','BULK') then raise exception 'INVALID_PART_PAYLOAD'; end if;
  if v_photo_path<>'' and split_part(v_photo_path,'/',1)<>v_owner::text then raise exception 'PHOTO_PATH_OWNER_MISMATCH' using errcode='42501'; end if;
  if p_payload ? 'expected_server_version' and (p_payload->>'expected_server_version')::bigint>0 and not exists(select 1 from public.parts where id=p_entity_id and owner_id=v_owner) then raise exception 'PART_VERSION_CONFLICT' using errcode='40001'; end if;
  insert into public.parts(id,owner_id,code,name,quantity,min_quantity,unit,photo_path,note,tracking_mode,updated_at,deleted_at,server_version)
  values(p_entity_id,v_owner,v_code,v_name,0,v_min_quantity,v_unit,v_photo_path,v_note,v_tracking_mode,now(),null,1)
  on conflict(id) do update set code=excluded.code,name=excluded.name,min_quantity=excluded.min_quantity,unit=excluded.unit,photo_path=excluded.photo_path,note=excluded.note,tracking_mode=excluded.tracking_mode,updated_at=now(),deleted_at=null,server_version=coalesce(public.parts.server_version,0)+1
  where public.parts.owner_id=v_owner and p_payload ? 'expected_server_version' and public.parts.server_version=(p_payload->>'expected_server_version')::bigint and public.parts.deleted_at is null;
  if not found then select * into v_part from public.parts where id=p_entity_id and owner_id=v_owner; if not found then raise exception 'PART_NOT_OWNED' using errcode='42501'; end if; if v_part.deleted_at is not null then raise exception 'PART_DELETED' using errcode='P0002'; end if; raise exception 'PART_VERSION_CONFLICT' using errcode='40001'; end if;
  select * into v_part from public.parts where id=p_entity_id and owner_id=v_owner;
  v_result:=jsonb_build_object('status','applied','entity_id',v_part.id,'server_version',v_part.server_version,'updated_at',v_part.updated_at);
 elsif p_entity_type='PART_DELETE' then
  update public.parts set deleted_at=now(),updated_at=now(),server_version=coalesce(server_version,0)+1 where id=p_entity_id and owner_id=v_owner and deleted_at is null and p_payload ? 'expected_server_version' and server_version=(p_payload->>'expected_server_version')::bigint returning * into v_part;
  if not found then select * into v_part from public.parts where id=p_entity_id and owner_id=v_owner; if not found then raise exception 'PART_NOT_OWNED' using errcode='42501'; end if; if v_part.deleted_at is not null then raise exception 'PART_DELETED' using errcode='P0002'; end if; raise exception 'PART_VERSION_CONFLICT' using errcode='40001'; end if;
  v_result:=jsonb_build_object('status','applied','entity_id',p_entity_id,'server_version',v_part.server_version,'deleted_at',v_part.deleted_at);
 else
  v_type:=p_payload->>'type'; v_amount:=(p_payload->>'amount')::integer; v_movement_id:=coalesce(nullif(p_payload->>'movement_sync_id','')::uuid,p_operation_id); v_reason:=coalesce(p_payload->>'reason',''); v_machine:=coalesce(p_payload->>'machine_or_store','');
  if v_type is null or v_type not in ('NHẬP','XUẤT','KIỂM KÊ','ĐẦU KỲ') or v_amount is null or (v_type='KIỂM KÊ' and v_amount<0) or (v_type in ('NHẬP','XUẤT','ĐẦU KỲ') and v_amount<=0) then raise exception 'INVALID_STOCK_CHANGE'; end if;
  if not (p_payload ? 'expected_server_version') then raise exception 'PART_VERSION_REQUIRED' using errcode='22023'; end if;
  begin v_expected_server_version:=nullif(btrim(p_payload->>'expected_server_version'),'')::bigint; exception when invalid_text_representation or numeric_value_out_of_range then raise exception 'PART_VERSION_REQUIRED' using errcode='22023'; end;
  if v_expected_server_version is null or v_expected_server_version<=0 then raise exception 'PART_VERSION_REQUIRED' using errcode='22023'; end if;
  select * into v_part from public.parts where id=p_entity_id and owner_id=v_owner and deleted_at is null for update;
  if not found then raise exception 'PART_NOT_FOUND' using errcode='P0002'; end if;
  if v_part.server_version is distinct from v_expected_server_version then raise exception 'PART_VERSION_CONFLICT' using errcode='40001'; end if;
  if v_type='XUẤT' and v_part.tracking_mode='SINGLE' and v_amount<>1 then raise exception 'SINGLE_ITEM_EXPORT_MUST_BE_ONE'; end if;
  v_before:=v_part.quantity; if v_type='ĐẦU KỲ' and v_before<>0 then raise exception 'OPENING_STOCK_REQUIRES_ZERO_CLOUD_STOCK'; end if;
  v_after:=case when v_type='NHẬP' then v_before+v_amount when v_type='XUẤT' then v_before-v_amount else v_amount end;
  if v_after<0 then raise exception 'INSUFFICIENT_STOCK'; end if;
  update public.parts set quantity=v_after,updated_at=now(),server_version=coalesce(server_version,0)+1 where id=p_entity_id and owner_id=v_owner returning * into v_part;
  insert into public.movements(id,owner_id,part_id,code,part_name,type,amount,before_qty,after_qty,reason,machine_or_store,occurred_at,created_at)
  values(v_movement_id,v_owner,v_part.id,v_part.code,v_part.name,v_type,v_amount,v_before,v_after,v_reason,v_machine,now(),now()) returning * into v_movement;
  v_result:=jsonb_build_object('status','applied','entity_id',v_part.id,'movement_id',v_movement_id,'before_qty',v_before,'after_qty',v_after,'server_version',v_part.server_version);
 end if;
 if p_entity_type in ('PART_UPSERT','PART_DELETE') then insert into public.inventory_sync_events(owner_id,entity_type,entity_id,payload) values(v_owner,'PART',p_entity_id,to_jsonb(v_part));
 else insert into public.inventory_sync_events(owner_id,entity_type,entity_id,payload) values(v_owner,'PART',p_entity_id,to_jsonb(v_part)),(v_owner,'MOVEMENT',v_movement.id,to_jsonb(v_movement)); end if;
 update public.sync_operations set result=v_result,processed_at=now() where operation_id=p_operation_id and owner_id=v_owner;
 return v_result;
end; $$;
revoke all on function public.apply_inventory_operation(uuid,text,uuid,jsonb) from public,anon,authenticated;
grant execute on function public.apply_inventory_operation(uuid,text,uuid,jsonb) to authenticated;
create or replace function public.pull_inventory_changes(p_after_event_id bigint default 0,p_limit integer default 200)
returns jsonb language plpgsql security definer set search_path=pg_catalog,public,auth as $$
declare v_owner uuid:=auth.uid(); v_events jsonb; v_next bigint;
begin
 if v_owner is null then raise exception 'AUTH_REQUIRED' using errcode='28000'; end if;
 if not public.is_active_profile() then raise exception 'PROFILE_INACTIVE' using errcode='42501'; end if;
 if p_after_event_id is null or p_after_event_id<0 then raise exception 'INVALID_CURSOR'; end if;
 with page as (select event_id,entity_type,entity_id,payload,created_at from public.inventory_sync_events where owner_id=v_owner and event_id>p_after_event_id order by event_id asc limit greatest(1,least(coalesce(p_limit,200),500)))
 select coalesce(jsonb_agg(jsonb_build_object('event_id',event_id,'entity_type',entity_type,'entity_id',entity_id,'payload',payload,'created_at',created_at) order by event_id),'[]'::jsonb),coalesce(max(event_id),p_after_event_id) into v_events,v_next from page;
 return jsonb_build_object('events',v_events,'next_cursor',v_next);
end; $$;
revoke all on function public.pull_inventory_changes(bigint,integer) from public,anon,authenticated;
grant execute on function public.pull_inventory_changes(bigint,integer) to authenticated;
create or replace function public.pull_admin_movement_changes(p_after_event_id bigint default 0,p_limit integer default 200)
returns jsonb language plpgsql security definer set search_path=pg_catalog,public,auth as $$
declare v_user uuid:=auth.uid(); v_role text; v_events jsonb; v_next bigint;
begin
 if v_user is null then raise exception 'AUTH_REQUIRED' using errcode='28000'; end if;
 select p.role into v_role from public.profiles p where p.id=v_user and p.is_active=true;
 if not found then raise exception 'PROFILE_INACTIVE' using errcode='42501'; end if;
 if v_role is null or lower(v_role)<>'admin' then raise exception 'ADMIN_REQUIRED' using errcode='42501'; end if;
 if p_after_event_id is null or p_after_event_id<0 then raise exception 'INVALID_CURSOR'; end if;
 with page as (select e.event_id,e.entity_type,e.entity_id,e.owner_id,e.payload,e.created_at,coalesce(nullif(btrim(pr.display_name),''),nullif(btrim(pr.username),''),e.owner_id::text) owner_name from public.inventory_sync_events e left join public.profiles pr on pr.id=e.owner_id where e.event_id>p_after_event_id order by e.event_id asc limit greatest(1,least(coalesce(p_limit,200),500))),
 shaped as (select event_id,case when entity_type='PART' then 'SKIP' else entity_type end entity_type,case when entity_type='PART' then null else entity_id end entity_id,case when entity_type='PART' then '{}'::jsonb else jsonb_set(jsonb_set(coalesce(payload,'{}'::jsonb),'{owner_id}',to_jsonb(owner_id::text),true),'{owner_name}',to_jsonb(owner_name),true) end payload,created_at from page)
 select coalesce(jsonb_agg(jsonb_build_object('event_id',event_id,'entity_type',entity_type,'entity_id',entity_id,'payload',payload,'created_at',created_at) order by event_id),'[]'::jsonb),coalesce(max(event_id),p_after_event_id) into v_events,v_next from shaped;
 return jsonb_build_object('events',v_events,'next_cursor',v_next);
end; $$;
revoke all on function public.pull_admin_movement_changes(bigint,integer) from public,anon,authenticated;
grant execute on function public.pull_admin_movement_changes(bigint,integer) to authenticated;
create or replace function public.assign_inventory_sync_event_commit_order() returns trigger language plpgsql security definer set search_path=pg_catalog as $$
declare v_sequence_name text;
begin
 perform pg_catalog.pg_advisory_xact_lock(130130,1);
 v_sequence_name:=pg_catalog.pg_get_serial_sequence('public.inventory_sync_events','event_id');
 if v_sequence_name is null then raise exception 'SYNC_EVENT_SEQUENCE_NOT_FOUND'; end if;
 new.event_id:=pg_catalog.nextval(v_sequence_name::pg_catalog.regclass); return new;
end; $$;
revoke all on function public.assign_inventory_sync_event_commit_order() from public,anon,authenticated;
alter table public.inventory_sync_events alter column event_id drop default;
drop trigger if exists inventory_sync_events_commit_order on public.inventory_sync_events;
create trigger inventory_sync_events_commit_order before insert on public.inventory_sync_events for each row execute function public.assign_inventory_sync_event_commit_order();
commit;
