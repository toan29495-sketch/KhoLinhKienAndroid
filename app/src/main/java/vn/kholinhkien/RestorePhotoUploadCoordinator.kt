package vn.kholinhkien

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Resumable, owner-scoped photo uploader for ZIP restore.
 * Remote object names are deterministic (owner + restore + SHA-256), so a retry
 * after process death can verify an object that was uploaded just before the crash.
 * The local checkpoint is advisory only: Storage is always re-verified before skip.
 */
internal class RestorePhotoUploadCoordinator(
    private val uploadObject: suspend (remotePath: String, bytes: ByteArray, contentType: String) -> Unit,
    private val verifyObject: suspend (remotePath: String, expectedSha256: String, expectedBytes: Long) -> Boolean,
    private val requireActiveOwner: suspend (ownerId: String) -> Unit
) {
    data class UploadedPhoto(
        val entryName: String,
        val remotePath: String,
        val sha256: String,
        val sizeBytes: Long
    )

    suspend fun uploadAndVerify(
        ownerId: String,
        restoreId: String,
        photoEntries: Collection<String>,
        stagingDirectory: File
    ): Map<String, String> {
        UUID.fromString(ownerId)
        UUID.fromString(restoreId)
        requireActiveOwner(ownerId)
        require(stagingDirectory.isDirectory) { "Không tìm thấy thư mục ảnh staging" }
        val root = stagingDirectory.canonicalFile
        val checkpoint = File(root, CHECKPOINT_NAME)
        require(checkpoint.canonicalFile.parentFile == root) { "Đường dẫn checkpoint không an toàn" }
        val checkpointData = readCheckpoint(checkpoint, ownerId, restoreId)
        val result = LinkedHashMap<String, String>(photoEntries.size)
        val seenPaths = HashSet<String>()

        for (entry in photoEntries.sorted()) {
            requireActiveOwner(ownerId)
            require(entry.startsWith("photos/")) { "Mục ảnh ZIP không hợp lệ: $entry" }
            val fileName = entry.removePrefix("photos/")
            require(fileName.matches(Regex("[A-Za-z0-9._-]{1,180}"))) { "Tên ảnh không hợp lệ: $entry" }
            val source = File(root, entry)
            require(source.canonicalPath.startsWith(root.path + File.separator)) {
                "Đường dẫn ảnh vượt khỏi thư mục staging"
            }
            require(source.isFile && source.length() in 1..MAX_PHOTO_BYTES) {
                "Ảnh thiếu, rỗng hoặc vượt giới hạn 20 MB: $entry"
            }

            val extension = source.extension.lowercase().takeIf { it in ALLOWED_EXTENSIONS }
                ?: error("Định dạng ảnh không được hỗ trợ: $entry")
            val contentType = when (extension) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            val size = source.length()
            val digest = sha256(source)
            val remotePath = "$ownerId/restore-$restoreId/$digest.$extension"
            require(seenPaths.add(remotePath)) { "Hai mục ảnh tạo cùng đường dẫn Storage" }

            val saved = checkpointData.optJSONObject(entry)
            val checkpointMatches = saved != null &&
                saved.optString("remotePath") == remotePath &&
                saved.optString("sha256") == digest && saved.optLong("sizeBytes", -1L) == size

            // Also handles a crash after successful upload but before checkpoint save.
            val alreadyPresent = checkpointMatches && verifyObject(remotePath, digest, size)
            requireActiveOwner(ownerId)
            val presentAfterCrash = !checkpointMatches && verifyObject(remotePath, digest, size)
            requireActiveOwner(ownerId)
            if (!alreadyPresent && !presentAfterCrash) {
                // SDK 3.0.1 upload API consumes ByteArray; memory is bounded to one <=20 MiB photo.
                val bytes = source.readBytes()
                check(bytes.size.toLong() == size && sha256(bytes).equals(digest, ignoreCase = true)) {
                    "Ảnh staging thay đổi trong lúc chuẩn bị upload: $entry"
                }
                requireActiveOwner(ownerId)
                uploadObject(remotePath, bytes, contentType)
                requireActiveOwner(ownerId)
                check(verifyObject(remotePath, digest, size)) {
                    "Ảnh chưa xác minh được trên Storage; giữ nguyên ảnh tạm: $entry"
                }
                requireActiveOwner(ownerId)
            }

            requireActiveOwner(ownerId)
            checkpointData.put(entry, JSONObject()
                .put("remotePath", remotePath)
                .put("sha256", digest)
                .put("sizeBytes", size))
            writeCheckpointAtomically(checkpoint, JSONObject()
                .put("ownerId", ownerId)
                .put("restoreId", restoreId)
                .put("photos", checkpointData))
            result[entry] = remotePath
        }
        return result
    }

    private fun readCheckpoint(file: File, ownerId: String, restoreId: String): JSONObject {
        if (!file.exists()) return JSONObject()
        val root = JSONObject(file.readText(Charsets.UTF_8))
        require(root.optString("ownerId") == ownerId && root.optString("restoreId") == restoreId) {
            "Checkpoint ảnh không thuộc phiên khôi phục hiện tại"
        }
        return root.optJSONObject("photos") ?: JSONObject()
    }

    private fun writeCheckpointAtomically(target: File, value: JSONObject) {
        val temp = File(target.parentFile, "$CHECKPOINT_NAME.tmp")
        temp.writeText(value.toString(), Charsets.UTF_8)
        // API 26+ (project minSdk) supports an atomic same-filesystem replacement.
        try {
            Files.move(
                temp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (error: Exception) {
            temp.delete()
            throw IllegalStateException("Không lưu nguyên tử được checkpoint ảnh khôi phục", error)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val CHECKPOINT_NAME = "photos_uploaded.json"
        const val MAX_PHOTO_BYTES = 20L * 1024L * 1024L
        val ALLOWED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
