package vn.kholinhkien

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Normalizes Supabase RPC results before accessing their JSON shape.
 * Depending on the PostgREST/Kotlin serialization path, a jsonb object can
 * arrive either as a JsonObject or as a JsonPrimitive containing JSON text.
 * Calling `.jsonObject` directly on the latter throws:
 * `JsonLiteral is not a JsonObject`.
 *
 * Only one explicit JSON-string unwrap is allowed. Scalar/non-JSON responses
 * are rejected with a useful error instead of being mistaken for an object.
 */
internal object RestoreRpcJson {
    private val parser = Json { isLenient = false }

    fun objectValue(raw: Any?, operation: String): JsonObject {
        val parsed = parseRaw(raw, operation)
        val normalized = unwrapJsonString(parsed, operation)
        return normalized as? JsonObject
            ?: error("RPC $operation trả về ${kindOf(normalized)}, cần JSON object")
    }

    fun arrayValue(raw: Any?, operation: String): JsonArray {
        val parsed = parseRaw(raw, operation)
        val normalized = unwrapJsonString(parsed, operation)
        return normalized as? JsonArray
            ?: error("RPC $operation trả về ${kindOf(normalized)}, cần JSON array")
    }

    private fun parseRaw(raw: Any?, operation: String): JsonElement {
        requireNotNull(raw) { "RPC $operation không trả dữ liệu" }
        return when (raw) {
            is JsonElement -> raw
            is String -> runCatching { parser.parseToJsonElement(raw) }
                .getOrElse { throw IllegalStateException("RPC $operation trả JSON không hợp lệ", it) }
            else -> runCatching { parser.parseToJsonElement(raw.toString()) }
                .getOrElse { throw IllegalStateException("Không phân tích được phản hồi RPC $operation", it) }
        }
    }

    private fun unwrapJsonString(value: JsonElement, operation: String): JsonElement {
        if (value !is JsonPrimitive || !value.isString) return value
        val trimmed = value.content.trim()
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return value
        return runCatching { parser.parseToJsonElement(trimmed) }
            .getOrElse { throw IllegalStateException("RPC $operation bọc JSON trong chuỗi nhưng nội dung không hợp lệ", it) }
    }

    private fun kindOf(value: JsonElement): String = when (value) {
        is JsonObject -> "JSON object"
        is JsonArray -> "JSON array"
        is JsonPrimitive -> if (value.isString) "JSON string" else "JSON primitive"
        else -> value::class.simpleName ?: "JSON value"
    }
}
