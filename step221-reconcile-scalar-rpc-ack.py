#!/usr/bin/env python3
"""Step221: reconcile primitive RPC replies against the authoritative idempotency row."""
from pathlib import Path
p = Path("app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt")
s = p.read_text(encoding="utf-8")
old = '''                    is JsonPrimitive -> {
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
'''
new = '''                    is JsonPrimitive -> {
                        if (responseElement.isString) {
                            val embeddedResponse = responseElement.contentOrNull
                                ?: error("RPC đồng bộ trả về chuỗi rỗng; giữ giao dịch để đối soát")
                            val decodedResponse = runCatching {
                                Json.parseToJsonElement(embeddedResponse)
                            }.getOrNull() as? JsonObject
                            decodedResponse ?: error("RPC đồng bộ trả về chuỗi không chứa JSON object; giữ giao dịch để đối soát")
                        } else {
                            // The RPC may have committed successfully even when the client
                            // library exposes its response as a scalar. Confirm success from
                            // the owner's idempotency row; never acknowledge on the scalar alone.
                            val operationRows = client.from("sync_operations").select {
                                filter {
                                    eq("operation_id", claimed.operationId)
                                    eq("owner_id", ownerId)
                                }
                            }.decodeList<JsonObject>()
                            val operationRow = operationRows.singleOrNull()
                                ?: error("RPC trả phản hồi dạng scalar và không tìm thấy đúng một bản ghi đối soát; giữ giao dịch")
                            val rowOperationId = operationRow["operation_id"]?.jsonPrimitive?.contentOrNull
                            val rowOwnerId = operationRow["owner_id"]?.jsonPrimitive?.contentOrNull
                            val rowEntityType = operationRow["entity_type"]?.jsonPrimitive?.contentOrNull
                            val rowEntityId = operationRow["entity_id"]?.jsonPrimitive?.contentOrNull
                            val processedAt = operationRow["processed_at"]
                            require(rowOperationId == claimed.operationId &&
                                rowOwnerId == ownerId &&
                                rowEntityType == operation.operationType &&
                                rowEntityId == claimed.entityId &&
                                processedAt != null && processedAt !is JsonNull) {
                                "Bản ghi đối soát không khớp giao dịch hoặc chưa xử lý; giữ giao dịch"
                            }
                            operationRow["result"] as? JsonObject
                                ?: error("Bản ghi đối soát chưa có kết quả JSON object; giữ giao dịch")
                        }
                    }
'''
if old not in s:
    raise SystemExit("Step221: expected Step220 primitive response branch not found; no edit made")
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8")

progress = Path("PROGRESS.md")
if progress.exists():
    log = progress.read_text(encoding="utf-8")
    heading = "## Step221 — Reconcile scalar RPC response against server idempotency row (05/11/2026)"
    if heading not in log:
        log += """
\n
## Step221 — Reconcile scalar RPC response against server idempotency row (05/11/2026)
- Production Supabase read-only inspection confirmed operation f5f3e13f-dcc8-4cff-b7c7-3abb302b504b is already committed: processed_at is set and result.status is applied for the expected owner/entity.
- The server function apply_inventory_operation returns jsonb; the client nevertheless received a scalar-shaped RPC response and incorrectly left the locally queued operation FAILED.
- Step221 never trusts the scalar itself. It performs a read-only SELECT against sync_operations under the authenticated owner's RLS and accepts acknowledgement only when exactly one row matches operation_id, owner_id, entity_type, entity_id, has processed_at, and contains a JSON-object result.
- If any identity/status check fails, the operation remains failed for reconciliation. No queue deletion, payload rewrite, or server-side data mutation.
- Build and device verification pending.
"""
        progress.write_text(log, encoding="utf-8")
print("Step221 safe server-side acknowledgement reconciliation applied.")
