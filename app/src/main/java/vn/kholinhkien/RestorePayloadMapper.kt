package vn.kholinhkien

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Converts ZIP v2 JSONL records (camelCase) into canonical snake_case payloads
 * accepted by the restore staging RPCs. Network and Storage operations are
 * intentionally kept outside this mapper.
 */
internal object RestorePayloadMapper {
    fun part(
        item: JSONObject,
        ownerId: String,
        fallbackSeed: String,
        photoPathByEntry: Map<String, String>
    ): JSONObject {
        require(ownerId.isNotBlank()) { "Thiếu ownerId" }
        UUID.fromString(ownerId) // Supabase auth.uid() is a UUID.
        require(fallbackSeed.isNotBlank()) { "Thiếu khóa ổn định cho linh kiện" }

        val code = item.optString("code", "").trim()
        val name = item.optString("name", "").trim()
        require(code.isNotEmpty() && name.isNotEmpty()) { "Linh kiện thiếu mã hoặc tên" }

        val syncId = stableUuid(item.optString("syncId", ""), fallbackSeed)
        val photoEntry = item.optString("photoEntry", "")
        val photoPath = if (photoEntry.isBlank()) "" else
            photoPathByEntry[photoEntry] ?: error("Ảnh chưa được tải lên Storage: $photoEntry")
        if (photoPath.isNotBlank()) {
            require(photoPath.startsWith("$ownerId/")) { "Đường dẫn ảnh không thuộc tài khoản hiện tại" }
            require(!photoPath.contains("\\") && photoPath.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                "Đường dẫn ảnh Storage không hợp lệ"
            }
        }

        val quantity = item.optInt("quantity", -1)
        val minQuantity = item.optInt("minQuantity", 0)
        require(quantity >= 0 && minQuantity >= 0) { "Số lượng linh kiện không hợp lệ: $code" }
        val trackingMode = item.optString("trackingMode", "SINGLE")
        require(trackingMode == "SINGLE" || trackingMode == "BULK") { "trackingMode không hợp lệ: $code" }

        return JSONObject()
            .put("sync_id", syncId)
            .put("code", code)
            .put("name", name)
            .put("quantity", quantity)
            .put("min_quantity", minQuantity)
            .put("unit", item.optString("unit", "cái").ifBlank { "cái" })
            .put("photo_path", photoPath)
            .put("note", item.optString("note", ""))
            .put("tracking_mode", trackingMode)
            .put("updated_at", item.optLong("updatedAt", 0L).takeIf { it > 0L } ?: System.currentTimeMillis())
    }

    fun movement(item: JSONObject, fallbackSeed: String): JSONObject {
        require(fallbackSeed.isNotBlank()) { "Thiếu khóa ổn định cho lịch sử" }
        val code = item.optString("code", "").trim()
        val partName = item.optString("partName", "").trim()
        val type = item.optString("type", "").trim()
        require(code.isNotEmpty() && partName.isNotEmpty() && type.isNotEmpty()) {
            "Lịch sử thiếu mã linh kiện, tên hoặc loại giao dịch"
        }
        val amount = item.optInt("amount", -1)
        val beforeQty = item.optInt("beforeQty", -1)
        val afterQty = item.optInt("afterQty", -1)
        val time = item.optLong("time", 0L)
        require(amount >= 0 && beforeQty >= 0 && afterQty >= 0 && time > 0L) {
            "Thông tin số lượng hoặc thời gian lịch sử không hợp lệ"
        }
        require(type in setOf("NHẬP", "XUẤT", "KIỂM KÊ", "ĐẦU KỲ")) {
            "Loại giao dịch lịch sử không hợp lệ: $type"
        }
        require(type == "KIỂM KÊ" || amount > 0) {
            "Số lượng giao dịch nhập/xuất/đầu kỳ phải lớn hơn 0"
        }
        when (type) {
            "NHẬP" -> require(afterQty.toLong() == beforeQty.toLong() + amount.toLong()) {
                "Lịch sử nhập kho không khớp số lượng trước/sau"
            }
            "XUẤT" -> require(beforeQty >= amount && afterQty.toLong() == beforeQty.toLong() - amount.toLong()) {
                "Lịch sử xuất kho không khớp số lượng trước/sau"
            }
            "KIỂM KÊ" -> require(afterQty == amount) {
                "Lịch sử kiểm kê không khớp số lượng sau kiểm kê"
            }
            "ĐẦU KỲ" -> require(beforeQty == 0 && afterQty == amount) {
                "Lịch sử đầu kỳ không hợp lệ"
            }
        }

        return JSONObject()
            .put("sync_id", stableUuid(item.optString("syncId", ""), fallbackSeed))
            .put("code", code)
            .put("part_name", partName)
            .put("type", type)
            .put("amount", amount)
            .put("before_qty", beforeQty)
            .put("after_qty", afterQty)
            .put("reason", item.optString("reason", ""))
            .put("machine_or_store", item.optString("machineOrStore", ""))
            .put("time", time)
    }

    /** Reject duplicates within a staged chunk before sending it to Postgres. */
    fun requireUniquePartChunk(rows: List<JSONObject>) {
        val ids = HashSet<String>(rows.size)
        val codes = HashSet<String>(rows.size)
        rows.forEach { row ->
            require(ids.add(row.getString("sync_id"))) { "Chunk có sync_id linh kiện trùng" }
            require(codes.add(row.getString("code"))) { "Chunk có mã linh kiện trùng" }
        }
    }

    fun requireUniqueMovementChunk(rows: List<JSONObject>) {
        val ids = HashSet<String>(rows.size)
        rows.forEach { row ->
            require(ids.add(row.getString("sync_id"))) { "Chunk có sync_id lịch sử trùng" }
        }
    }

    private fun stableUuid(raw: String, fallbackSeed: String): String {
        if (raw.isNotBlank()) return UUID.fromString(raw).toString()
        // Include owner + restore ID + entity line index in fallbackSeed. This
        // keeps retry IDs stable without colliding across accounts/restores.
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(fallbackSeed.toByteArray(Charsets.UTF_8)).copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return UUID.fromString("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}").toString()
    }
}
