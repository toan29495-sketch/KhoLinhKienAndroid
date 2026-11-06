package vn.kholinhkien

import java.util.UUID

/** Resolves the persisted owner for owner-scoped and Admin-global movement feeds. */
object MovementOwnerResolver {
    fun resolve(expectedOwnerId: String, payloadOwnerId: String?, allowForeignOwner: Boolean): String {
        require(expectedOwnerId.isNotBlank()) { "Thiếu tài khoản đang đăng nhập" }
        if (!allowForeignOwner) {
            require(payloadOwnerId == null || payloadOwnerId == expectedOwnerId) {
                "Snapshot MOVEMENT không thuộc tài khoản hiện tại"
            }
            return expectedOwnerId
        }
        val supplied = payloadOwnerId?.takeIf { it.isNotBlank() }
            ?: error("Admin global MOVEMENT thiếu owner_id")
        val canonicalOwner = UUID.fromString(supplied).toString()
        require(canonicalOwner == supplied.lowercase()) {
            "Admin global MOVEMENT có owner_id không chuẩn"
        }
        return canonicalOwner
    }
}
