package vn.kholinhkien

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.parseToJsonElement

/** Creates/resumes the owner-scoped server session and sends durable payload chunks.
 * The live inventory is never modified by this sender; only the server staging tables
 * are touched until the separate commit RPC is explicitly invoked. */
internal class RestoreCloudSessionSender(
    private val dao: InventoryDao,
    private val client: SupabaseClient = SupabaseClientProvider.client
) {
    data class Result(val partChunksSent: Int, val movementChunksSent: Int)

    suspend fun stage(ownerId: String, restoreId: String): Result = mutex.withLock {
        val userId = client.auth.currentUserOrNull()?.id
            ?: error("Cần đăng nhập trước khi tải dữ liệu khôi phục lên cloud")
        require(userId == ownerId) { "Tài khoản đăng nhập không khớp chủ kho cần khôi phục" }

        val journal = requireNotNull(dao.restoreJournal(ownerId, restoreId)) { "Không tìm thấy journal khôi phục" }
        require(journal.ownerId == ownerId) { "Phiên khôi phục không thuộc tài khoản hiện tại" }
        require(journal.status in setOf("PAYLOAD_READY", "CLOUD_STAGING")) {
            "Payload chưa sẵn sàng để gửi lên cloud: ${journal.status}"
        }

        // Expire abandoned server-side staging before creating/resuming a restore.
        // The RPC is owner-bound and retains committed snapshots for other devices.
        client.postgrest.rpc("cleanup_my_inventory_restore_staging")
        require(client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đã thay đổi trong lúc dọn phiên khôi phục"
        }

        val parts = loadChunks(ownerId, restoreId, PAYLOAD_PART, journal.partCount)
        val movements = loadChunks(ownerId, restoreId, PAYLOAD_MOVEMENT, journal.movementCount)
        val manifestDigest = journal.manifestSha256.lowercase()
        require(manifestDigest.matches(Regex("^[a-f0-9]{64}$"))) { "Manifest SHA-256 không hợp lệ" }

        requireActiveOwner(ownerId)
        client.postgrest.rpc(
            "begin_inventory_restore",
            parameters = buildJsonObject {
                put("p_restore_id", restoreId)
                put("p_manifest_sha256", manifestDigest)
                put("p_expected_part_count", journal.partCount)
                put("p_expected_movement_count", journal.movementCount)
                put("p_expected_part_chunks", parts.size)
                put("p_expected_movement_chunks", movements.size)
            }
        )
        requireActiveOwner(ownerId)
        dao.saveRestoreJournal(journal.copy(
            status = "CLOUD_STAGING",
            lastError = "",
            updatedAt = System.currentTimeMillis()
        ))

        var partSent = 0
        var movementSent = 0
        try {
            parts.forEachIndexed { index, payload ->
                requireActiveOwner(ownerId)
                client.postgrest.rpc(
                    "stage_inventory_restore_chunk",
                    parameters = buildJsonObject {
                        put("p_restore_id", restoreId)
                        put("p_entity_type", "PART")
                        put("p_chunk_index", index)
                        put("p_payload", payload)
                    }
                )
                requireActiveOwner(ownerId)
                partSent++
            }
            movements.forEachIndexed { index, payload ->
                requireActiveOwner(ownerId)
                client.postgrest.rpc(
                    "stage_inventory_restore_chunk",
                    parameters = buildJsonObject {
                        put("p_restore_id", restoreId)
                        put("p_entity_type", "MOVEMENT")
                        put("p_chunk_index", index)
                        put("p_payload", payload)
                    }
                )
                requireActiveOwner(ownerId)
                movementSent++
            }
            requireActiveOwner(ownerId)
            // The server's commit function performs the authoritative completeness and
            // referential checks. This status means all staging RPCs returned successfully,
            // not that the live inventory has been replaced.
            dao.saveRestoreJournal(journal.copy(
                status = "CLOUD_STAGED",
                lastError = "",
                updatedAt = System.currentTimeMillis()
            ))
            Result(partSent, movementSent)
        } catch (cancelled: CancellationException) {
            // Keep CLOUD_STAGING: chunk RPCs are idempotent for the same restore/chunk key.
            // Resume will safely retry uncertain acknowledgements without changing payloads.
            throw cancelled
        } catch (error: Exception) {
            val message = (error.message ?: error::class.java.simpleName).take(1000)
            dao.saveRestoreJournal(journal.copy(
                status = "CLOUD_STAGING",
                lastError = message,
                updatedAt = System.currentTimeMillis()
            ))
            throw error
        }
    }

    private fun requireActiveOwner(ownerId: String) {
        require(client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đã thay đổi hoặc phiên đăng nhập hết hạn; đã dừng gửi dữ liệu khôi phục"
        }
    }

    private suspend fun loadChunks(
        ownerId: String,
        restoreId: String,
        type: String,
        expectedRows: Int
    ): List<kotlinx.serialization.json.JsonArray> {
        val rows = dao.stagedRestoreRows(ownerId, restoreId, type)
        require(rows.size == expectedRows) { "$type payload thiếu bản ghi: ${rows.size}/$expectedRows" }
        if (rows.isEmpty()) return emptyList()
        val grouped = rows.groupBy { it.chunkIndex }.toSortedMap()
        require(grouped.keys.toList() == grouped.keys.indices.toList()) { "$type payload thiếu hoặc lệch chỉ số chunk" }
        return grouped.map { (index, chunkRows) ->
            val ordered = chunkRows.sortedBy { it.rowIndex }
            require(ordered.map { it.rowIndex } == ordered.indices.toList()) { "$type chunk $index thiếu dòng hoặc sai thứ tự" }
            require(ordered.size <= MAX_ROWS_PER_CHUNK) { "$type chunk $index vượt 200 dòng" }
            buildJsonArray {
                ordered.forEach { row -> add(Json.parseToJsonElement(row.payloadJson)) }
            }.also { payload ->
                require(payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_CHUNK_BYTES) {
                    "$type chunk $index vượt giới hạn kích thước an toàn"
                }
            }
        }
    }

    private companion object {
        const val PAYLOAD_PART = "PART_PAYLOAD"
        const val PAYLOAD_MOVEMENT = "MOVEMENT_PAYLOAD"
        const val MAX_ROWS_PER_CHUNK = 200
        const val MAX_CHUNK_BYTES = 900 * 1024
        val mutex = Mutex()
    }
}
