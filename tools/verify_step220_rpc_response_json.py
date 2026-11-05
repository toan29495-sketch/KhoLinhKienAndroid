from pathlib import Path

s = Path("app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt").read_text(encoding="utf-8")
checks = {
    "RPC response accepts native JsonObject": "is JsonObject -> responseElement" in s,
    "RPC response accepts only JSON string primitive for embedded object": "is JsonPrimitive" in s and "responseElement.isString" in s,
    "embedded RPC response is parsed and checked as JsonObject": "Json.parseToJsonElement(embeddedResponse)" in s and "decodedResponse as? JsonObject" in s,
    "invalid RPC response shapes fail with reconciliation message": "RPC đồng bộ không trả về JSON object" in s,
    "status acknowledgement guard retained": 'responseObject["status"]' in s and 'responseStatus == "applied"' in s,
    "entity identity acknowledgement guard retained": 'responseObject["entity_id"]' in s and "claimed.entityId" in s,
    "server version acknowledgement guard retained": 'responseObject["server_version"]' in s and "serverVersion > 0L" in s,
    "no outbox deletion or operation ID rewrite introduced": "deleteOutbox" not in s and "claimed.operationId" in s,
}
for label, passed in checks.items():
    print(("PASS" if passed else "FAIL") + " - " + label)
print(f"RESULT: {sum(checks.values())}/{len(checks)} PASS")
if not all(checks.values()):
    raise SystemExit(1)
