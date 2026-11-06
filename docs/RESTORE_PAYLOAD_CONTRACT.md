# ZIP v2 → Supabase restore payload contract

## Purpose

`parts.jsonl` and `movements.jsonl` in ZIP v2 remain the portable backup format and use camelCase. Supabase staging RPCs consume canonical snake_case JSON objects. `RestorePayloadMapper` is the single conversion boundary; callers must not hand-build payload keys in multiple places.

## PART mapping

| ZIP v2 | Staging JSON | Notes |
|---|---|---|
| `syncId` | `sync_id` | UUID, preserved; deterministic UUID fallback for legacy rows |
| `code` | `code` | trimmed, required, unique per owner |
| `name` | `name` | required |
| `quantity` | `quantity` | non-negative integer |
| `minQuantity` | `min_quantity` | non-negative integer |
| `unit` | `unit` | defaults to `cái` |
| `photoEntry` | `photo_path` | must be uploaded and verified first; owner-prefixed Storage path only |
| `note` | `note` | optional |
| `trackingMode` | `tracking_mode` | `SINGLE` or `BULK` |
| `updatedAt` | `updated_at` | epoch milliseconds |

## MOVEMENT mapping

`syncId→sync_id`, `code→code`, `partName→part_name`, `type→type`, `amount→amount`, `beforeQty→before_qty`, `afterQty→after_qty`, `reason→reason`, `machineOrStore→machine_or_store`, `time→time` (epoch milliseconds). For legacy rows without `syncId`, the caller supplies a stable `fallbackSeed` containing the restore ID and JSONL line index, preventing collisions between otherwise identical movements.

## Important boundaries

- The mapper does not call Storage or Supabase and does not claim an image is uploaded.
- A non-empty `photoEntry` without a verified `photoPathByEntry` mapping is rejected.
- The caller must chunk the mapped objects into at most 200 rows and at most 1 MiB serialized payload before calling `stage_inventory_restore_chunk`.
- The current local `ZipRestore.restore()` is still the old local-only restore flow. This mapper is groundwork and is not wired into the restore UI or network protocol yet.

## Retry-safe identity and chunk validation

- `RestorePayloadMapper.part` now requires a caller-provided `fallbackSeed` when mapping a legacy PART row without `syncId`. The seed must include `ownerId`, `restoreId`, entity type, and the 1-based JSONL line number. Do not use only the part code: the same code may exist in different owners or in a later restore.
- MOVEMENT uses the same seed rule. Both preserve a valid `syncId` already present in ZIP v2.
- The mapper rejects malformed owner UUIDs and unsafe/non-owner-prefixed Storage paths.
- `requireUniquePartChunk` checks duplicate `sync_id` and `code` within the outgoing chunk; `requireUniqueMovementChunk` checks duplicate `sync_id`. PostgreSQL commit still performs the authoritative cross-chunk duplicate checks.
- Callers must keep chunk boundaries and fallback seeds stable across retries. A retry must resend the exact same canonical JSON payload for a given `(restoreId, entityType, chunkIndex)`.
