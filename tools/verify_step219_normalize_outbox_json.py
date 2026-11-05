from pathlib import Path
s=Path("app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt").read_text(encoding="utf-8")
checks={
"recognizes native JsonObject":"is JsonObject -> originalPayload" in s,
"normalizes only JSON string primitives":"is JsonPrimitive" in s and "originalPayload.isString" in s,
"embedded content must parse as JsonObject":"Json.parseToJsonElement(embedded)" in s and "as? JsonObject" in s,
"rejects invalid payload explicitly":"Payload giao dịch không phải JSON object" in s,
"photo upload receives normalized object":"claimed.operationId, normalizedPayload" in s,
"PART_UPSERT RPC receives normalized payload":'operation.operationType == "PART_UPSERT") normalizedPayload else originalPayload' in s,
"no operation ID rewrite":"claimed.operationId" in s,
}
for k,v in checks.items(): print(("PASS" if v else "FAIL")+" - "+k)
print(f"RESULT: {sum(checks.values())}/{len(checks)} PASS")
if not all(checks.values()): raise SystemExit(1)
