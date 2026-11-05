#!/usr/bin/env python3
"""Step219: safely normalize legacy double-encoded outbox JSON objects."""
from pathlib import Path
p = Path("app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt")
s = p.read_text(encoding="utf-8")
old = '''                val originalPayload = Json.parseToJsonElement(claimed.payloadJson)
                val photoUpload = if (operation.operationType == "PART_UPSERT") {
                    uploadPartPhotoIfPresent(ownerId, claimed.entityId, claimed.operationId, originalPayload.jsonObject)
                } else null
'''
new = '''                val originalPayload = Json.parseToJsonElement(claimed.payloadJson)
                // Older/local outbox rows may contain a JSON object encoded once more
                // as a JSON string. Normalize only that exact representation; never
                // coerce numbers, booleans, null, arrays, or malformed payloads.
                val normalizedPayload = when (originalPayload) {
                    is JsonObject -> originalPayload
                    is JsonPrimitive -> {
                        val embedded = originalPayload.contentOrNull
                        if (originalPayload.isString && !embedded.isNullOrBlank()) {
                            runCatching { Json.parseToJsonElement(embedded) }.getOrNull() as? JsonObject
                                ?: error("Payload giao dịch PART_UPSERT là chuỗi nhưng không chứa JSON object hợp lệ; giữ giao dịch để kiểm tra")
                        } else {
                            error("Payload giao dịch không phải JSON object; giữ giao dịch để kiểm tra")
                        }
                    }
                    else -> error("Payload giao dịch không phải JSON object; giữ giao dịch để kiểm tra")
                }
                val photoUpload = if (operation.operationType == "PART_UPSERT") {
                    uploadPartPhotoIfPresent(ownerId, claimed.entityId, claimed.operationId, normalizedPayload)
                } else null
'''
if old not in s: raise SystemExit("Step219: expected sender payload parsing block not found")
s = s.replace(old,new,1)
# Use normalized object as the RPC payload for PART_UPSERT; preserve original element for other operation types.
old2 = '''                val payload = photoUpload?.payload ?: originalPayload
'''
new2 = '''                val payload = photoUpload?.payload ?: if (operation.operationType == "PART_UPSERT") normalizedPayload else originalPayload
'''
if old2 not in s: raise SystemExit("Step219: payload selection block not found")
s = s.replace(old2,new2,1)
p.write_text(s,encoding="utf-8")
progress=Path("PROGRESS.md")
if progress.exists():
 log=progress.read_text(encoding="utf-8")
 heading="## Step219 — Normalize double-encoded outbox JSON (05/11/2026)"
 if heading not in log:
  log+="""

## Step219 — Normalize double-encoded outbox JSON (05/11/2026)
- Root cause observed on device: a FAILED PART_UPSERT row reaches SyncOutboxSender as JsonLiteral/JsonPrimitive, while photo upload handling requires JsonObject. This throws before the RPC call and stops the ordered queue, leaving subsequent operations PENDING.
- Added strict normalization for legacy double-encoded JSON object strings.
- Only a JSON string whose contents parse to a JsonObject is normalized. Scalars, arrays, null, malformed strings, and other shapes remain failures with an actionable message; no payload is discarded or guessed.
- PART_UPSERT RPC receives the normalized object. Other operation payloads retain their original JSON representation.
- Operation ID and ordering are unchanged; no queue deletion or restore-gate bypass.
- Build and device verification pending.
"""
  progress.write_text(log,encoding="utf-8")
print("Step219 payload normalization applied.")
