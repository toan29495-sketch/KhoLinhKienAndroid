package vn.kholinhkien

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Explicit, read-only and owner-scoped server snapshot lookup for a blocked PART operation. */
class SyncConflictSnapshotReader(
    private val client: io.github.jan.supabase.SupabaseClient = SupabaseClientProvider.client
) {
    suspend fun read(ownerId: String, partSyncId: String): String? {
        require(ownerId.isNotBlank()) { "Thiếu chủ tài khoản" }
        val authenticated = client.auth.currentUserOrNull()?.id
            ?: error("Phiên đăng nhập Supabase đã hết hạn")
        require(authenticated == ownerId) { "Tài khoản hiện tại không sở hữu kho này" }
        val uuid = UUID.fromString(partSyncId).toString()
        val raw = client.postgrest.rpc(
            "get_inventory_part_conflict_snapshot",
            parameters = buildJsonObject { put("p_part_sync_id", uuid) }
        )
        val element = Json.parseToJsonElement(raw.toString())
        if (element == JsonNull) return null
        return element.jsonObject.toString()
    }
}
