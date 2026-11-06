package vn.kholinhkien

import android.content.Context
import android.os.StatFs
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.storage.downloadAuthenticatedTo
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Downloads one owner-scoped Cloud backup to private cache and verifies every layer. */
internal class WeeklyBackupCloudDownloader(
    private val context: Context,
    private val client: SupabaseClient = SupabaseClientProvider.client
) {
    data class VerifiedFile(val backupId: String, val file: File, val sha256: String)

    suspend fun downloadAndVerify(
        ownerId: String,
        entry: WeeklyBackupCloudCatalog.Entry
    ): VerifiedFile {
        require(isCanonicalOwnerId(ownerId)) { "Tài khoản chủ backup không hợp lệ" }
        require(entry.isRestoreCandidate) { "Chỉ backup VERIFIED mới được tải để kiểm tra" }
        require(entry.storagePath == "$ownerId/${entry.backupId}.zip") {
            "Đường dẫn backup không thuộc tài khoản hiện tại"
        }
        requireOwner(ownerId)
        val available = StatFs(context.cacheDir.absolutePath).availableBytes
        require(available >= entry.archiveSizeBytes + MIN_FREE_BYTES) {
            "Không đủ dung lượng tạm để tải backup; cần thêm ${entry.archiveSizeBytes + MIN_FREE_BYTES - available} byte"
        }

        val finalFile = File(context.cacheDir, "verified_cloud_backup_${ownerId}_${entry.backupId}.zip")
        val partial = File(context.cacheDir, ".${finalFile.name}.partial")
        if (partial.exists()) require(partial.delete()) { "Không dọn được tệp tải dở dang" }
        try {
            currentCoroutineContext().ensureActive()
            requireOwner(ownerId)
            client.storage.from(BUCKET).downloadAuthenticatedTo(entry.storagePath, partial)
            currentCoroutineContext().ensureActive()
            requireOwner(ownerId)
            require(partial.isFile && partial.length() == entry.archiveSizeBytes) {
                "Kích thước tệp tải về không khớp metadata Cloud"
            }
            val digest = sha256(partial, ownerId)
            require(digest.equals(entry.sha256, ignoreCase = true)) {
                "SHA-256 tệp tải về không khớp metadata Cloud"
            }
            FileInputStream(partial).use { input -> BackupArchiveInspector.verify(input, ownerId) }
            verifyManifest(partial, ownerId, entry)
            currentCoroutineContext().ensureActive()
            requireOwner(ownerId)
            if (finalFile.exists()) require(finalFile.delete()) { "Không thay được bản cache cũ" }
            require(partial.renameTo(finalFile)) { "Không thể hoàn tất tệp backup đã xác minh" }
            requireOwner(ownerId)
            return VerifiedFile(entry.backupId, finalFile, digest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    private fun verifyManifest(file: File, ownerId: String, entry: WeeklyBackupCloudCatalog.Entry) {
        val manifestBytes = FileInputStream(file).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var found: ByteArray? = null
                while (true) {
                    val item = zip.nextEntry ?: break
                    if (item.name == "manifest.json") {
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_MANIFEST_BYTES) { "Manifest backup vượt giới hạn" }
                            output.write(buffer, 0, count)
                        }
                        found = output.toByteArray()
                        break
                    }
                    zip.closeEntry()
                }
                found ?: error("ZIP backup thiếu manifest.json")
            }
        }
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        require(manifest.optString("ownerId") == ownerId) { "Manifest backup không thuộc tài khoản hiện tại" }
        require(manifest.optInt("partCount", -1) == entry.partCount &&
            manifest.optInt("movementCount", -1) == entry.movementCount) {
            "Số lượng dữ liệu trong manifest không khớp metadata Cloud"
        }
        require(manifest.optString("backupKind") == entry.backupKind) {
            "Loại backup trong manifest không khớp metadata Cloud"
        }
        require(manifest.optLong("snapshotCursor", -1L) == entry.snapshotCursor) {
            "Snapshot cursor trong manifest không khớp metadata Cloud"
        }
    }

    private fun requireOwner(ownerId: String) {
        require(client.auth.currentUserOrNull()?.id == ownerId) {
            "Tài khoản đã thay đổi hoặc phiên đăng nhập hết hạn; đã dừng tải backup"
        }
    }

    private suspend fun sha256(file: File, ownerId: String): String {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        requireOwner(ownerId)
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                requireOwner(ownerId)
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        coroutineContext.ensureActive()
        requireOwner(ownerId)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUCKET = "inventory-backups"
        const val MIN_FREE_BYTES = 16L * 1024L * 1024L
        const val MAX_MANIFEST_BYTES = 1024L * 1024L
    }
}
