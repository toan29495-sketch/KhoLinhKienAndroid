-- Private part-image storage access: each authenticated account may only
-- read/write objects inside its own first-level folder (<auth.uid()>/...).
-- The bucket remains private; clients must request authenticated downloads.

begin;

drop policy if exists part_images_owner_select on storage.objects;
create policy part_images_owner_select
on storage.objects
for select
to authenticated
using (
    bucket_id = 'part-images'
    and (storage.foldername(name))[1] = (select auth.uid())::text
);

drop policy if exists part_images_owner_insert on storage.objects;
create policy part_images_owner_insert
on storage.objects
for insert
to authenticated
with check (
    bucket_id = 'part-images'
    and (storage.foldername(name))[1] = (select auth.uid())::text
);

drop policy if exists part_images_owner_update on storage.objects;
create policy part_images_owner_update
on storage.objects
for update
to authenticated
using (
    bucket_id = 'part-images'
    and (storage.foldername(name))[1] = (select auth.uid())::text
)
with check (
    bucket_id = 'part-images'
    and (storage.foldername(name))[1] = (select auth.uid())::text
);

drop policy if exists part_images_owner_delete on storage.objects;
create policy part_images_owner_delete
on storage.objects
for delete
to authenticated
using (
    bucket_id = 'part-images'
    and (storage.foldername(name))[1] = (select auth.uid())::text
);

commit;
