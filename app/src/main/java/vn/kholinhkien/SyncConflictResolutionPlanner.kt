package vn.kholinhkien

enum class SyncConflictKind {
    PART_VERSION_CONFLICT, PART_DELETED, VERSION_REQUIRED, OWNERSHIP, OPERATION_ID, UNKNOWN
}

data class SyncConflictPlan(
    val kind: SyncConflictKind,
    val explanation: String,
    val nextSteps: List<String>,
    val executableKeepLocal: Boolean
)

/** Pure read-only policy planner: it has no DAO, Supabase client, or mutation capability. */
object SyncConflictResolutionPlanner {
    /** Keep-local replay is implemented only for an isolated metadata PART_UPSERT.
     * Delete and stock conflicts must remain manual: replaying either can resurrect a
     * tombstone or manufacture/drop a stock movement. */
    fun allowedDecisions(operationType: String): Set<String> = when (operationType) {
        "PART_UPSERT" -> setOf("KEEP_LOCAL", "KEEP_SERVER", "MANUAL_REVIEW")
        "PART_DELETE", "STOCK_CHANGE" -> setOf("MANUAL_REVIEW")
        else -> setOf("MANUAL_REVIEW")
    }

    fun classify(raw: String): SyncConflictKind = when {
        raw.contains("PART_VERSION_CONFLICT", true) -> SyncConflictKind.PART_VERSION_CONFLICT
        raw.contains("PART_DELETED", true) || raw.contains("PART_NOT_FOUND", true) -> SyncConflictKind.PART_DELETED
        raw.contains("PART_VERSION_REQUIRED", true) -> SyncConflictKind.VERSION_REQUIRED
        raw.contains("PART_NOT_OWNED", true) || raw.contains("AUTH_REQUIRED", true) -> SyncConflictKind.OWNERSHIP
        raw.contains("OPERATION_ID_CONFLICT", true) || raw.contains("OPERATION_PAYLOAD_CONFLICT", true) -> SyncConflictKind.OPERATION_ID
        else -> SyncConflictKind.UNKNOWN
    }

    fun plan(raw: String, hasServerSnapshot: Boolean, dependentCount: Int): SyncConflictPlan {
        val kind = classify(raw)
        val steps = when (kind) {
            SyncConflictKind.PART_VERSION_CONFLICT -> listOf(
                "Giữ nguyên giao dịch đã gửi và payload gốc",
                "Đọc snapshot máy chủ mới nhất và đối chiếu từng trường",
                if (dependentCount == 0) "Chỉ PART_UPSERT độc lập mới có thể tạo giao dịch thay thế" else "Rà soát toàn bộ $dependentCount thao tác phía sau trước khi xử lý"
            )
            SyncConflictKind.PART_DELETED -> listOf("Không tự hồi sinh tombstone", "Kiểm tra lịch sử xóa và quyết định thủ công nếu cần tạo linh kiện mới")
            SyncConflictKind.VERSION_REQUIRED -> listOf("Đọc lại snapshot máy chủ", "Không tự suy đoán phiên bản cho bản ghi local cũ")
            SyncConflictKind.OWNERSHIP -> listOf("Dừng thao tác", "Không đổi owner hoặc thử truy cập kho tài khoản khác")
            SyncConflictKind.OPERATION_ID -> listOf("Giữ nguyên operationId và payload đã attempt", "Đối soát sổ idempotency phía máy chủ trước khi tạo giao dịch mới")
            SyncConflictKind.UNKNOWN -> listOf("Giữ nguyên BLOCKED và toàn bộ Outbox", "Cần kiểm tra thủ công; không tự retry hoặc bỏ qua")
        }
        val explanation = when (kind) {
            SyncConflictKind.PART_VERSION_CONFLICT -> if (hasServerSnapshot) "Phiên bản máy chủ khác với phiên bản giao dịch đang giữ." else "Cần đọc snapshot máy chủ trước khi quyết định."
            SyncConflictKind.PART_DELETED -> "Bản ghi có thể đã bị xóa hoặc soft-delete trên máy chủ."
            SyncConflictKind.VERSION_REQUIRED -> "Giao dịch thiếu token phiên bản hợp lệ."
            SyncConflictKind.OWNERSHIP -> "Không xác minh được quyền sở hữu của giao dịch."
            SyncConflictKind.OPERATION_ID -> "Mã giao dịch đã tồn tại với nội dung không khớp."
            SyncConflictKind.UNKNOWN -> "Lỗi chưa được phân loại; giữ an toàn và yêu cầu rà soát thủ công."
        }
        return SyncConflictPlan(kind, explanation, steps, kind == SyncConflictKind.PART_VERSION_CONFLICT && hasServerSnapshot && dependentCount == 0)
    }
}
