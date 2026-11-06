-- Step129: part-image objects may only be deleted by a trusted server role.
-- Authenticated KTV/Admin clients can still SELECT, INSERT and UPDATE within
-- their owner folder, but cannot delete objects from the private part-images
-- bucket. A service-role Edge Function (which bypasses RLS) may later perform
-- reference-aware garbage collection after its own final authorization checks.
-- This migration does not delete any Storage object or grant service-role access.
begin;

drop policy if exists part_images_owner_delete on storage.objects;
drop policy if exists part_images_no_authenticated_delete on storage.objects;

create policy part_images_no_authenticated_delete
on storage.objects
as restrictive
for delete
to anon, authenticated
using (bucket_id <> 'part-images');

commit;
