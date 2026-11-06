package vn.kholinhkien

import android.content.Context
import android.os.StatFs
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Reads only a server-confirmed snapshot into separate Room staging buckets.
 * It never writes live parts, movements, outbox, or sync cursors. */
internal class RestoreCloudSnapshotReader(
    private val context: Context,
    private val dao: InventoryDao,
    private val client: SupabaseClient = SupabaseClientProvider.client
) {
    data class Result(val partRows: Int, val movementRows: Int, val partChunks: Int, val movementChunks: Int)

    private companion object {
        const val MIN_FREE_BYTES_DURING_SNAPSHOT = 16L * 1024L * 1024L
        const val SQLITE_WRITE_MULTIPLIER = 2L
        const val PAYLOAD_PART = "PART_PAYLOAD"
        const val PAYLOAD_MOVEMENT = "MOVEMENT_PAYLOAD"
        const val SNAPSHOT_PART = "SNAPSHOT_PART"
        const val SNAPSHOT_MOVEMENT = "SNAPSHOT_MOVEMENT"
    }

    suspend fun download(ownerId: String, restoreId: String): Result {
        val userId = client.auth.currentUserOrNull()?.id
            ?: error("Cần đăng nhập trước khi tải snapshot khôi phục")
        require(userId == ownerId) { "Tài khoản đăng nhập không khớp chủ kho" }

        val journal = requireNotNull(dao.restoreJournal(ownerId, restoreId)) { "Không tìm thấy journal khôi phục" }
        require(journal.ownerId == ownerId) { "Phiên khôi phục không thuộc tài khoản hiện tại" }
        requireActiveOwner(ownerId)
        require(journal.status == "COMMIT_CONFIRMED" || journal.status == "REMOTE_COMMIT_CONFIRMED" || journal.status == "SNAPSHOT_DOWNLOADING" || journal.status == "SNAPSHOT_DOWNLOADED") {
            "Snapshot chỉ được tải sau khi máy chủ xác nhận commit: ${journal.status}"
        }
        require(journal.snapshotCursor > 0L) { "Snapshot cursor không hợp lệ" }
        require(journal.manifestSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) { "Manifest SHA-256 không hợp lệ" }

        val (localPartChunks, localMovementChunks) = if (journal.sourceType == "REMOTE_FEED") {
            validateRemoteChunkCount(journal.partCount, journal.partChunkCount, "PART")
            validateRemoteChunkCount(journal.movementCount, journal.movementChunkCount, "MOVEMENT")
            journal.partChunkCount to journal.movementChunkCount
        } else {
            chunkCount(dao.stagedRestoreRows(ownerId, restoreId, PAYLOAD_PART), journal.partCount) to
                chunkCount(dao.stagedRestoreRows(ownerId, restoreId, PAYLOAD_MOVEMENT), journal.movementCount)
        }
        requireActiveOwner(ownerId)
        dao.saveRestoreJournal(journal.copy(status = "SNAPSHOT_DOWNLOADING", lastError = "", updatedAt = System.currentTimeMillis()))

        // A retry starts from a clean snapshot staging area. Source ZIP and cloud payload
        // staging remain untouched, and live inventory is never involved.
        dao.clearRestoreStagedRowsByType(ownerId, restoreId, SNAPSHOT_PART)
        dao.clearRestoreStagedRowsByType(ownerId, restoreId, SNAPSHOT_MOVEMENT)

        return try {
            val partRows = readEntity(ownerId, restoreId, "PART", SNAPSHOT_PART, localPartChunks, journal)
            val movementRows = readEntity(ownerId, restoreId, "MOVEMENT", SNAPSHOT_MOVEMENT, localMovementChunks, journal)
            require(partRows == journal.partCount) { "Số linh kiện snapshot không khớp journal" }
            require(movementRows == journal.movementCount) { "Số lịch sử snapshot không khớp journal" }
            requireActiveOwner(ownerId)
            dao.saveRestoreJournal(journal.copy(status = "SNAPSHOT_DOWNLOADED", lastError = "", updatedAt = System.currentTimeMillis()))
            Result(partRows, movementRows, localPartChunks, localMovementChunks)
        } catch (cancelled: CancellationException) {
            // Keep SNAPSHOT_DOWNLOADING. A retry clears only the partial snapshot buckets
            // before downloading again; source payload and live inventory remain intact.
            throw cancelled
        } catch (error: Exception) {
            dao.saveRestoreJournal(journal.copy(status = "SNAPSHOT_DOWNLOADING", lastError = (error.message ?: error::class.java.simpleName).take(1000), updatedAt = System.currentTimeMillis()))
            throw error
        }
    }

    private suspend fun readEntity(
        ownerId: String,
        restoreId: String,
        entityType: String,
        storageType: String,
        chunkCount: Int,
        journal: RestoreJournal
    ): Int {
        var totalRows = 0
        repeat(chunkCount) { index ->
            val raw = client.postgrest.rpc(
                "read_inventory_restore_snapshot_chunk",
                parameters = buildJsonObject {
                    put("p_restore_id", restoreId)
                    put("p_entity_type", entityType)
                    put("p_chunk_index", index)
                }
            )
            requireActiveOwner(ownerId)
            val envelope = Json.parseToJsonElement(raw.toString()).jsonObject
            require(envelope["restore_id"]?.jsonPrimitive?.contentOrNull == restoreId) { "Snapshot trả sai restoreId" }
            require(envelope["manifest_sha256"]?.jsonPrimitive?.contentOrNull.equals(journal.manifestSha256, true)) { "Snapshot manifest không khớp" }
            require(envelope["snapshot_cursor"]?.jsonPrimitive?.longOrNull == journal.snapshotCursor) { "Snapshot cursor thay đổi giữa các chunk" }
            require(envelope["entity_type"]?.jsonPrimitive?.contentOrNull == entityType) { "Snapshot trả sai loại dữ liệu" }
            require(envelope["chunk_index"]?.jsonPrimitive?.intOrNull == index) { "Snapshot trả sai chỉ số chunk" }
            require(envelope["chunk_count"]?.jsonPrimitive?.intOrNull == chunkCount) { "Số chunk snapshot không khớp payload đã commit" }
            val md5 = envelope["payload_md5"]?.jsonPrimitive?.contentOrNull
            require(md5 != null && md5.matches(Regex("^[a-fA-F0-9]{32}$"))) { "Snapshot thiếu checksum MD5 hợp lệ" }
            val payload = envelope["payload"]?.jsonArray ?: error("Snapshot thiếu payload")
            val rowCount = envelope["row_count"]?.jsonPrimitive?.intOrNull
            require(rowCount == payload.size && payload.isNotEmpty() && payload.size <= 200) { "Số dòng snapshot không hợp lệ" }

            // PostgreSQL computes MD5 over jsonb::text. Kotlin's JSON serializer is
            // not byte-compatible with PostgreSQL's canonical JSONB rendering, so
            // round-trip the parsed JSON value to a narrowly scoped owner-only RPC.
            // The server compares its canonical digest and row count to the immutable
            // committed chunk before Android accepts any row into local staging.
            val verificationRaw = client.postgrest.rpc(
                "verify_inventory_restore_snapshot_chunk",
                parameters = buildJsonObject {
                    put("p_restore_id", restoreId)
                    put("p_entity_type", entityType)
                    put("p_chunk_index", index)
                    put("p_payload", payload)
                }
            )
            requireActiveOwner(ownerId)
            val verification = Json.parseToJsonElement(verificationRaw.toString()).jsonObject
            require(verification["verified"]?.jsonPrimitive?.contentOrNull == "true") {
                "Checksum snapshot không khớp dữ liệu máy chủ ở chunk $index"
            }
            require(verification["payload_md5"]?.jsonPrimitive?.contentOrNull.equals(md5, true)) {
                "Checksum snapshot trả về không khớp ở chunk $index"
            }
            require(verification["row_count"]?.jsonPrimitive?.intOrNull == rowCount) {
                "Số dòng xác minh snapshot không khớp ở chunk $index"
            }
            val totalCount = envelope["total_count"]?.jsonPrimitive?.intOrNull
            require(totalCount == if (entityType == "PART") journal.partCount else journal.movementCount) { "Tổng số dòng snapshot không khớp" }

            val rows = payload.mapIndexed { rowIndex, element ->
                require(element is kotlinx.serialization.json.JsonObject) { "Dòng snapshot không phải JSON object" }
                RestoreStagedRow(
                    restoreId = restoreId,
                    ownerId = ownerId,
                    entityType = storageType,
                    chunkIndex = index,
                    rowIndex = rowIndex,
                    payloadJson = element.toString()
                )
            }
            // SQLite may need journal/WAL pages beyond the serialized payload size.
            // Keep a fixed reserve and estimate at 2x before every durable chunk write.
            val estimatedWriteBytes = rows.sumOf { it.payloadJson.toByteArray(Charsets.UTF_8).size.toLong() }
                .coerceAtLeast(1L) * SQLITE_WRITE_MULTIPLIER
            requireSnapshotSpace(estimatedWriteBytes)
            require(client.auth.currentUserOrNull()?.id == ownerId) {
                "Tài khoản đã thay đổi trước khi lưu snapshot; đã dừng tải"
            }
            dao.insertRestoreStagedRows(rows)
            require(client.auth.currentUserOrNull()?.id == ownerId) {
                "Tài khoản đã thay đổi sau khi lưu snapshot; dữ liệu vẫn nằm trong staging của chủ kho cũ"
            }
            totalRows += rows.size
        }
        return totalRows
    }


    private fun requireActiveOwner(ownerId: String) {
        require(client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đã thay đổi hoặc phiên đăng nhập hết hạn; đã dừng nhận snapshot"
        }
    }

    private fun validateRemoteChunkCount(rowCount: Int, chunkCount: Int, entity: String) {
        require(rowCount >= 0 && chunkCount >= 0 && chunkCount <= rowCount) {
            "Số chunk $entity từ RESTORE_COMMIT không hợp lệ"
        }
        require((rowCount == 0) == (chunkCount == 0)) {
            "Số chunk $entity không khớp tổng số dòng"
        }
        require(chunkCount <= 8192) { "Số chunk $entity vượt giới hạn an toàn" }
    }

    private fun chunkCount(rows: List<RestoreStagedRow>, expectedCount: Int): Int {
        require(rows.size == expectedCount) { "Payload cục bộ thiếu dòng trước khi đọc snapshot" }
        if (rows.isEmpty()) return 0
        val grouped = rows.groupBy { it.chunkIndex }.toSortedMap()
        require(grouped.keys.toList() == grouped.keys.indices.toList()) { "Payload cục bộ thiếu chỉ số chunk" }
        grouped.forEach { (index, chunk) ->
            require(chunk.sortedBy { it.rowIndex }.map { it.rowIndex } == chunk.indices.toList()) { "Payload cục bộ sai thứ tự dòng ở chunk $index" }
            require(chunk.size <= 200) { "Payload cục bộ vượt giới hạn chunk" }
        }
        return grouped.size
    }

    private fun requireSnapshotSpace(estimatedWriteBytes: Long) {
        require(estimatedWriteBytes > 0L) { "Kích thước ghi snapshot không hợp lệ" }
        val available = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }
            .getOrElse { error("Không kiểm tra được dung lượng trống: ${it.message ?: "lỗi hệ thống"}") }
        val required = MIN_FREE_BYTES_DURING_SNAPSHOT + estimatedWriteBytes
        require(available >= required) {
            "Điện thoại không đủ dung lượng để tải snapshot cloud. Dữ liệu kho hiện tại chưa bị thay đổi; hãy giải phóng bộ nhớ rồi tiếp tục khôi phục."
        }
    }

}
