package vn.kholinhkien

import android.content.Context
import io.github.jan.supabase.auth.auth
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** Moves an already verified Cloud archive into durable, owner-scoped private app storage.
 * This is only a restore inbox: it does not create a restore journal or mutate Room/Cloud.
 */
internal class WeeklyBackupRestoreInbox(
    private val context: Context
) {
    data class StoredArchive(val backupId: String, val file: File, val sha256: String)


    data class StoredItem(
        val backupId: String, val file: File, val sha256: String,
        val backupKind: String, val snapshotCursor: Long,
        val partCount: Int, val movementCount: Int, val savedAt: Long, val sizeBytes: Long
    )

    /** Re-discover only complete, owner-scoped inbox items; corrupt or partial entries fail closed. */
    fun listVerified(ownerId: String): List<StoredItem> {
        require(isCanonicalOwnerId(ownerId)) { "Tài khoản chủ backup không hợp lệ" }
        require(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đã thay đổi; không thể đọc backup staging"
        }
        val root = File(context.filesDir, "cloud_restore_inbox").canonicalFile
        val dir = File(root, ownerId).canonicalFile
        if (!dir.exists()) return emptyList()
        require(dir.parentFile == root && dir.name == ownerId && dir.isDirectory) { "Thư mục inbox không an toàn" }
        val items = dir.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.mapNotNull { metaFile ->
            val json = JSONObject(metaFile.readText(Charsets.UTF_8))
            val id = json.getString("backupId")
            require(runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false)) { "backupId inbox không hợp lệ" }
            require(metaFile.name == "$id.json" && json.getString("ownerId") == ownerId) { "Metadata inbox không thuộc tài khoản hiện tại" }
            val archive = File(dir, "$id.zip").canonicalFile
            require(archive.parentFile == dir && archive.isFile) { "Thiếu tệp ZIP tương ứng trong inbox" }
            val size = json.getLong("archiveSizeBytes")
            require(size in 1..(256L * 1024L * 1024L) && archive.length() == size) { "Kích thước backup staging không hợp lệ" }
            val sha = json.getString("sha256")
            require(sha.matches(Regex("[a-f0-9]{64}")) && sha256(archive).equals(sha, true)) { "SHA-256 backup staging không khớp" }
            require(json.getString("storagePath") == "$ownerId/$id.zip") { "Đường dẫn backup staging không hợp lệ" }
            require(json.getString("backupKind") in setOf("WEEKLY_AUTO", "MONTHLY_AUTO", "PRE_UPDATE")) { "Loại backup staging không hợp lệ" }
            FileInputStream(archive).use { BackupArchiveInspector.verify(it, ownerId) }
            StoredItem(id, archive, sha, json.getString("backupKind"), json.getLong("snapshotCursor"),
                json.getInt("partCount"), json.getInt("movementCount"), json.getLong("savedAt"), size)
        }.sortedByDescending { it.savedAt }
        require(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đã thay đổi trong khi đọc backup staging"
        }
        return items
    }

    fun persist(ownerId: String, entry: WeeklyBackupCloudCatalog.Entry,
                verified: WeeklyBackupCloudDownloader.VerifiedFile): StoredArchive {
        require(isCanonicalOwnerId(ownerId)) { "Tài khoản chủ backup không hợp lệ" }
        require(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đã thay đổi; không thể lưu backup vào vùng staging"
        }
        require(entry.isRestoreCandidate && entry.backupId == verified.backupId) {
            "Backup không còn đủ điều kiện hoặc không khớp tệp đã xác minh"
        }
        require(verified.file.isFile && verified.file.length() == entry.archiveSizeBytes) {
            "Tệp đã xác minh không còn nguyên vẹn trong cache"
        }
        val root = File(context.filesDir, "cloud_restore_inbox").canonicalFile
        val ownerDir = File(root, ownerId).canonicalFile
        require(ownerDir.parentFile == root && ownerDir.name == ownerId) { "Đường dẫn inbox không an toàn" }
        require(ownerDir.mkdirs() || ownerDir.isDirectory) { "Không tạo được vùng lưu backup bền vững" }

        val target = File(ownerDir, "${entry.backupId}.zip")
        val metadata = File(ownerDir, "${entry.backupId}.json")
        val partial = File(ownerDir, ".${entry.backupId}.zip.partial")
        require(target.canonicalFile.parentFile == ownerDir && metadata.canonicalFile.parentFile == ownerDir) {
            "Đường dẫn tệp backup không hợp lệ"
        }
        if (partial.exists()) require(partial.delete()) { "Không dọn được tệp staging dở dang" }
        try {
            FileInputStream(verified.file).use { input ->
                FileOutputStream(partial).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
            require(partial.length() == entry.archiveSizeBytes) { "Kích thước bản lưu staging không khớp" }
            val digest = sha256(partial)
            require(digest.equals(entry.sha256, true) && digest.equals(verified.sha256, true)) {
                "SHA-256 thay đổi khi chuyển sang vùng staging bền vững"
            }
            FileInputStream(partial).use { BackupArchiveInspector.verify(it, ownerId) }
            require(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) {
                "Tài khoản đã thay đổi trong khi lưu backup"
            }
            // Never overwrite an existing inbox item with different bytes.
            if (target.exists()) {
                require(target.length() == entry.archiveSizeBytes && sha256(target).equals(entry.sha256, true)) {
                    "Đã tồn tại backup cùng ID nhưng nội dung khác; giữ nguyên tệp hiện có"
                }
                partial.delete()
            } else {
                require(partial.renameTo(target)) { "Không thể hoàn tất lưu backup bền vững" }
            }
            val record = JSONObject()
                .put("ownerId", ownerId)
                .put("backupId", entry.backupId)
                .put("storagePath", entry.storagePath)
                .put("archiveSizeBytes", entry.archiveSizeBytes)
                .put("sha256", entry.sha256.lowercase())
                .put("backupKind", entry.backupKind)
                .put("snapshotCursor", entry.snapshotCursor)
                .put("partCount", entry.partCount)
                .put("movementCount", entry.movementCount)
                .put("savedAt", System.currentTimeMillis())
            val metaPartial = File(ownerDir, ".${entry.backupId}.json.partial")
            FileOutputStream(metaPartial).use { out ->
                out.write(record.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (metadata.exists()) require(metadata.delete()) { "Không cập nhật được metadata inbox cũ" }
            require(metaPartial.renameTo(metadata)) { "Không thể lưu metadata inbox" }
            verified.file.delete()
            return StoredArchive(entry.backupId, target, digest)
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
