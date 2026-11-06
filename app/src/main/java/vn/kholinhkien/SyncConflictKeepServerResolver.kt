package vn.kholinhkien

import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Applies a freshly fetched owner-scoped server snapshot only for an isolated PART_UPSERT. */
class SyncConflictKeepServerResolver(
    private val dao: InventoryDao,
    private val reader: SyncConflictSnapshotReader = SyncConflictSnapshotReader()
) {
    suspend fun resolve(ownerId: String, blockedOperationId: String) {
        val decision = dao.conflictDecision(ownerId, blockedOperationId)
            ?: error("Chưa lưu quyết định xử lý")
        require(decision.decisionKind == "KEEP_SERVER")
        require(decision.status == "READY_FOR_SERVER_RECHECK")
        val preflight = dao.inspectBlockedResolution(ownerId, blockedOperationId)
            ?: error("Thao tác bị chặn không còn tồn tại")
        require(preflight.blocker.operationType == "PART_UPSERT")
        require(preflight.dependentOperations.isEmpty() && !preflight.containsAttemptedOperations) {
            "Có giao dịch phụ thuộc; cần xử lý thủ công, không tự bỏ giao dịch"
        }
        val raw = reader.read(ownerId, preflight.blocker.entityId)
            ?: error("Linh kiện không còn trên máy chủ; không tự tạo lại")
        val json = JSONObject(raw)
        require(json.isNull("deleted_at")) { "Linh kiện đã bị xóa trên máy chủ; không tự hồi sinh" }
        val serverOwner = json.getString("owner_id")
        val syncId = UUID.fromString(json.getString("sync_id")).toString()
        require(serverOwner == ownerId && syncId == preflight.blocker.entityId) {
            "Snapshot không khớp chủ sở hữu hoặc linh kiện đang đối soát"
        }
        val version = json.getLong("server_version")
        require(version > 0L)
        val updatedAt = runCatching { Instant.parse(json.getString("updated_at")).toEpochMilli() }
            .getOrElse { error("Snapshot có updated_at không hợp lệ") }
        val serverPart = Part(
            id = dao.partBySyncId(ownerId, syncId)?.id ?: 0L,
            code = json.getString("code"),
            name = json.getString("name"),
            quantity = json.getInt("quantity"),
            minQuantity = json.optInt("min_quantity", 0),
            unit = json.optString("unit", "cái"),
            photoPath = json.optString("photo_path", ""),
            note = json.optString("note", ""),
            updatedAt = updatedAt,
            trackingMode = json.optString("tracking_mode", "SINGLE"),
            ownerId = ownerId,
            syncId = syncId,
            serverVersion = version
        )
        dao.resolveIsolatedKeepServer(ownerId, blockedOperationId, serverPart)
    }
}
