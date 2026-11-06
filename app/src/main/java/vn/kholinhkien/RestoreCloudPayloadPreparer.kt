package vn.kholinhkien

import org.json.JSONObject
import io.github.jan.supabase.auth.auth
import java.io.File

/**
 * Durable preparation boundary between ZIP staging and the future RPC sender.
 * Uploads referenced photos, maps ZIP records to the SQL contract, and persists
 * bounded JSON chunks back into Room. It never changes live inventory or sync state.
 */
internal class RestoreCloudPayloadPreparer(
    private val dao: InventoryDao,
    private val photoUploader: RestorePhotoUploadCoordinator
) {
    data class Result(val partChunks: Int, val movementChunks: Int, val uploadedPhotos: Int)

    suspend fun prepare(ownerId: String, restoreId: String): Result {
        requireActiveOwner(ownerId)
        val journal = requireNotNull(dao.restoreJournal(ownerId, restoreId)) { "Không tìm thấy phiên khôi phục" }
        require(journal.ownerId == ownerId) { "Phiên khôi phục không thuộc tài khoản hiện tại" }
        require(journal.status in setOf("STAGED", "PHOTOS_UPLOADED", "PAYLOAD_READY")) {
            "Phiên chưa staging hoàn chỉnh hoặc đã chuyển sang giai đoạn khác: ${journal.status}"
        }
        val stage = File(journal.stagedDirectory).canonicalFile
        val root = File(stage.parentFile, stage.name).canonicalFile
        require(stage == root && stage.isDirectory && stage.name == restoreId) {
            "Thư mục staging không hợp lệ"
        }

        val sourceParts = dao.stagedRestoreRows(ownerId, restoreId, SOURCE_PART)
        val sourceMovements = dao.stagedRestoreRows(ownerId, restoreId, SOURCE_MOVEMENT)
        require(RestoreStagingLifecycle.validateSourceRows(dao, journal)) {
            "Dữ liệu staging thiếu, trùng thứ tự hoặc chunk không liên tục; không thể chuẩn bị payload"
        }
        require(sourceParts.size == journal.partCount && sourceMovements.size == journal.movementCount) {
            "Số bản ghi staging không khớp journal"
        }

        val photoEntries = sourceParts.mapNotNull { row ->
            val entry = JSONObject(row.payloadJson).optString("photoEntry", "")
            entry.takeIf { it.isNotBlank() }
        }.toSet()
        val photoPaths = photoUploader.uploadAndVerify(ownerId, restoreId, photoEntries, stage)
        requireActiveOwner(ownerId)
        require(photoPaths.keys == photoEntries) { "Chưa upload/xác minh đủ ảnh được tham chiếu" }

        // Clear only derived payloads. Original ZIP JSON rows and photo checkpoint remain
        // intact, so a failed preparation can safely be retried.
        dao.clearRestoreStagedRowsByType(ownerId, restoreId, PAYLOAD_PART)
        dao.clearRestoreStagedRowsByType(ownerId, restoreId, PAYLOAD_MOVEMENT)

        val partChunks = persistMappedChunks(ownerId, restoreId, PAYLOAD_PART, sourceParts) { row ->
            val source = JSONObject(row.payloadJson)
            val seed = "$ownerId:$restoreId:PART:${row.chunkIndex}:${row.rowIndex}"
            RestorePayloadMapper.part(source, ownerId, seed, photoPaths)
        }
        val movementChunks = persistMappedChunks(ownerId, restoreId, PAYLOAD_MOVEMENT, sourceMovements) { row ->
            val source = JSONObject(row.payloadJson)
            val seed = "$ownerId:$restoreId:MOVEMENT:${row.chunkIndex}:${row.rowIndex}"
            RestorePayloadMapper.movement(source, seed)
        }

        require(dao.countStagedRestoreRows(ownerId, restoreId, PAYLOAD_PART) == journal.partCount &&
            dao.countStagedRestoreRows(ownerId, restoreId, PAYLOAD_MOVEMENT) == journal.movementCount) {
            "Payload cloud sau ánh xạ chưa đầy đủ"
        }
        requireActiveOwner(ownerId)
        dao.saveRestoreJournal(journal.copy(
            status = "PAYLOAD_READY",
            lastError = "",
            updatedAt = System.currentTimeMillis()
        ))
        return Result(partChunks, movementChunks, photoPaths.size)
    }

    private fun requireActiveOwner(ownerId: String) {
        require(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đã thay đổi hoặc phiên đăng nhập hết hạn; đã dừng chuẩn bị khôi phục"
        }
    }

    private suspend fun persistMappedChunks(
        ownerId: String,
        restoreId: String,
        entityType: String,
        sourceRows: List<RestoreStagedRow>,
        mapper: (RestoreStagedRow) -> JSONObject
    ): Int {
        var chunkIndex = 0
        var rowIndex = 0
        var bytes = 2 // JSON array brackets
        val batch = ArrayList<RestoreStagedRow>(MAX_ROWS_PER_CHUNK)
        suspend fun flush() {
            if (batch.isEmpty()) return
            dao.insertRestoreStagedRows(batch.toList())
            batch.clear()
            chunkIndex++
            rowIndex = 0
            bytes = 2
        }
        for (source in sourceRows) {
            val payload = mapper(source).toString()
            val payloadBytes = payload.toByteArray(Charsets.UTF_8).size
            require(payloadBytes + 2 <= MAX_CHUNK_BYTES) { "Một bản ghi vượt giới hạn payload 1 MB" }
            val addition = payloadBytes + if (batch.isEmpty()) 0 else 1
            if (batch.isNotEmpty() && (batch.size >= MAX_ROWS_PER_CHUNK || bytes + addition > MAX_CHUNK_BYTES)) {
                flush()
            }
            batch.add(RestoreStagedRow(
                restoreId = restoreId,
                ownerId = ownerId,
                entityType = entityType,
                chunkIndex = chunkIndex,
                rowIndex = rowIndex++,
                payloadJson = payload
            ))
            bytes += payloadBytes + if (batch.size == 1) 0 else 1
        }
        flush()
        return chunkIndex
    }

    private companion object {
        const val SOURCE_PART = "PART"
        const val SOURCE_MOVEMENT = "MOVEMENT"
        const val PAYLOAD_PART = "PART_PAYLOAD"
        const val PAYLOAD_MOVEMENT = "MOVEMENT_PAYLOAD"
        const val MAX_ROWS_PER_CHUNK = 200
        // Leave headroom below SQL's 1 MiB JSONB limit for RPC envelope fields.
        const val MAX_CHUNK_BYTES = 900 * 1024
    }
}
