package vn.kholinhkien

import org.json.JSONObject

/** Performs a fresh authenticated server read immediately before the atomic Room replacement. */
class SyncConflictKeepLocalResolver(
    private val dao: InventoryDao,
    private val reader: SyncConflictSnapshotReader = SyncConflictSnapshotReader()
) {
    suspend fun resolve(ownerId: String, blockedOperationId: String): SyncOutbox {
        val decision = dao.conflictDecision(ownerId, blockedOperationId)
            ?: error("Chưa lưu quyết định xử lý")
        require(decision.decisionKind == "KEEP_LOCAL")
        if (decision.replacementOperationId.isNotBlank()) {
            return dao.outboxById(decision.replacementOperationId)
                ?: error("Thiếu giao dịch thay thế đã liên kết; từ chối tạo bản trùng")
        }
        require(decision.status == "READY_FOR_SERVER_RECHECK")
        val preflight = dao.inspectBlockedResolution(ownerId, blockedOperationId)
            ?: error("Thao tác bị chặn không còn tồn tại")
        require(preflight.blocker.operationType == "PART_UPSERT")
        require(preflight.dependentOperations.isEmpty()) {
            "Có thao tác phía sau; không thể thay thế riêng lẻ"
        }
        val snapshot = reader.read(ownerId, preflight.blocker.entityId)
            ?: error("Linh kiện không còn tồn tại trên máy chủ; không tự tạo lại tombstone")
        val server = JSONObject(snapshot)
        require(server.isNull("deleted_at")) { "Linh kiện đã bị xóa trên máy chủ; không tự hồi sinh tombstone" }
        val serverVersion = server.optLong("server_version", 0L)
        require(serverVersion > 0L) { "Snapshot máy chủ thiếu server_version hợp lệ" }
        val localPart = dao.partBySyncId(ownerId, preflight.blocker.entityId)
            ?: error("Không còn linh kiện local để ưu tiên")
        val serverQuantity = server.optInt("quantity", Int.MIN_VALUE)
        require(localPart.quantity == serverQuantity) {
            "Tồn kho điện thoại và máy chủ khác nhau; PART_UPSERT không được phép ghi đè tồn kho"
        }
        return dao.resolveIsolatedKeepLocal(ownerId, blockedOperationId, serverVersion, serverQuantity)
    }
}
