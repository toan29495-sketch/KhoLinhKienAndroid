# Step100 – Concurrent QR/part-code uniqueness (2026-11-03)

## Finding

Room enforces `(ownerId, code)` uniqueness on each device, but that local index cannot coordinate two devices. The reconstructed SQL history did not include a server-side unique constraint on active `parts(owner_id, code)`. Two devices could therefore create different part UUIDs with the same QR/code before either device pulled the other's change, depending on the live base schema.

## Change

Added additive migration `202611030012_unique_active_part_code_per_owner.sql`:

- Preflight fails closed with `DUPLICATE_ACTIVE_PART_CODES_REQUIRE_RECONCILIATION` if duplicate active `(owner_id, code)` rows already exist.
- Adds a unique partial index on `(owner_id, code)` only where `deleted_at IS NULL`.
- Different KTV owners may reuse the same QR/code.
- A soft-deleted row releases its code, matching local behavior where deletion physically removes the Room row and a new part can be created later.
- Concurrent inserts for the same owner/code are arbitrated by PostgreSQL's unique index; one transaction wins and the other fails rather than creating duplicate active inventory rows.

No Android source behavior was changed. Existing Room uniqueness and transactional create-plus-opening-movement behavior remain in place. Restore duplicate-code validation remains in place.

## Verification

- `tools/verify_step100_qr_code_concurrency.py`: 15/15 PASS (static assertions plus deterministic unique-key behavior model).
- Full regression: Step49–72 verifier 24/24 + SQLite smoke; every verifier Step73–100 passed.
- ZIP integrity and SHA-256 verified.

## Important limitations / deployment gates

- This is source/static/model verification, not a PostgreSQL concurrency test.
- The base `public.parts` table migration is absent from the reconstructed source lineage. Before deployment, inspect actual column types/nullability, existing indexes/constraints, duplicate active rows, and exact live schema. The migration's preflight intentionally aborts rather than guessing which duplicate to merge.
- Test simultaneous create with same owner/code, same code across two owners, delete-then-recreate, restore snapshot, and duplicate legacy rows on Supabase staging.
- The unique-index violation may surface to the Android client as a generic database constraint error until a dedicated conflict mapping/UX is added. No automatic merge or destructive overwrite is performed.
- No SQL deployment, APK build, Android device test, Edge Function deployment, GitHub push, or production change.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source.
