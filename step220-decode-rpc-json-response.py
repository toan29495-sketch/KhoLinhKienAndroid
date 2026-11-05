#!/usr/bin/env python3
"""Step220: safely decode JSONB RPC responses returned as JSON string primitives."""
from pathlib import Path

sender = Path("app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt")
s = sender.read_text(encoding="utf-8")
old = '                val responseObject = Json.parseToJsonElement(rpcResponse.toString()).jsonObject\n'
new = '''                // Supabase/PostgREST may expose a JSONB result either as an object
                // or as a JSON string containing that object. Decode both shapes
                // explicitly; never force JsonPrimitive/array/null into jsonObject.
                val responseElement = Json.parseToJsonElement(rpcResponse.toString())
                val responseObject = when (responseElement) {
                    is JsonObject -> responseElement
                    is JsonPrimitive -> {
                        require(responseElement.isString) {
                            "RPC đồng bộ trả về giá trị JSON không phải object; giữ giao dịch để đối soát"
                        }
                        val embeddedResponse = responseElement.contentOrNull
                            ?: error("RPC đồng bộ trả về chuỗi rỗng; giữ giao dịch để đối soát")
                        val decodedResponse = runCatching {
                            Json.parseToJsonElement(embeddedResponse)
                        }.getOrElse {
                            error("RPC đồng bộ trả về chuỗi không phải JSON hợp lệ; giữ giao dịch để đối soát")
                        }
                        decodedResponse as? JsonObject
                            ?: error("RPC đồng bộ không trả về JSON object; giữ giao dịch để đối soát")
                    }
                    else -> error("RPC đồng bộ không trả về JSON object; giữ giao dịch để đối soát")
                }
'''
if old not in s:
    raise SystemExit("Step220: exact RPC response decoding line not found; refusing unsafe edit")
s = s.replace(old, new, 1)
sender.write_text(s, encoding="utf-8")

progress = Path("PROGRESS.md")
if progress.exists():
    log = progress.read_text(encoding="utf-8")
    heading = "## Step220 — Decode JSONB RPC response safely (05/11/2026)"
    if heading not in log:
        log += """
\n
## Step220 — Decode JSONB RPC response safely (05/11/2026)
- Device still reported the exact JsonLiteral-is-not-JsonObject exception after v1.12.
- Step219 normalizes the outgoing PART_UPSERT payload, but the sender also forced the RPC response through JsonElement.jsonObject. A JSON string primitive response could therefore throw the same kotlinx.serialization exception after the server call.
- Decode RPC response as either a native JsonObject or a JSON string whose contents parse to JsonObject. Reject scalar, array, null, malformed string, and empty string with explicit reconciliation errors.
- Preserve status/entity_id/server_version acknowledgement guards, operation ID, queue order, and all local outbox rows.
- Build and device verification pending.
"""
        progress.write_text(log, encoding="utf-8")
print("Step220 RPC response decoder applied.")
