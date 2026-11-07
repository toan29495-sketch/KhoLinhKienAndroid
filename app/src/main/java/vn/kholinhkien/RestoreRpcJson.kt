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
 * Unwrap JSON text carried by either a quoted JsonPrimitive or a raw JsonLiteral. Some SDK decoding paths preserve the response body as a primitive without setting isString. Scalar/non-JSON responses
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
        // Some serializer/transport combinations can preserve a JSONB object as
        // a JSON string that itself contains another JSON string. Unwrap bounded
        // layers until the requested shape is reached; never coerce scalar values.
        var current = value
        repeat(4) {
            if (current !is JsonPrimitive || current.isString.not() && current.content.trim().let {
                    !(it.startsWith("{") || it.startsWith("[") || it.startsWith(34.toChar().toString()))
                }) return current

            val text = current.content.trim()
            val looksLikeJsonContainer = text.startsWith("{") || text.startsWith("[")
            val looksLikeQuotedJson = text.startsWith(34.toChar().toString())
            if (!looksLikeJsonContainer && !looksLikeQuotedJson) return current

            val next = runCatching { parser.parseToJsonElement(text) }
                .getOrElse { throw IllegalStateException("RPC $operation chứa JSON primitive bọc nhiều lớp nhưng không hợp lệ", it) }
            if (next == current) return current
            current = next
            if (current is JsonObject || current is JsonArray) return current
        }
        return current
    }

    private fun kindOf(value: JsonElement): String = when (value) {
        is JsonObject -> "JSON object"
        is JsonArray -> "JSON array"
        is JsonPrimitive -> if (value.isString) "JSON string" else "JSON primitive"
        else -> value::class.simpleName ?: "JSON value"
    }
}
