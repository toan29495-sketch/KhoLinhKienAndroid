package vn.kholinhkien

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import androidx.room.withTransaction

object ZipRestore {

    suspend fun restore(
        context: Context,
        uri: Uri,
        dao: InventoryDao
    ): Pair<Int, Int> {
        val stage = File(context.cacheDir, "zip_restore_${UUID.randomUUID()}")
        require(stage.mkdirs()) { "Không tạo được thư mục tạm" }

        try {
            // Giải nén và chặn đường dẫn nguy hiểm trong ZIP.
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Không mở được tệp ZIP")

            input.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name

                        require(
                            !name.startsWith("/") &&
                            !name.contains("\\") &&
                            name.split("/").none { it == ".." }
                        ) { "Tệp ZIP có đường dẫn không an toàn" }

                        if (!entry.isDirectory) {
                            require(
                                name == "manifest.json" ||
                                name == "parts.jsonl" ||
                                name == "movements.jsonl" ||
                                name.startsWith("photos/")
                            ) { "Tệp ZIP chứa thành phần không hợp lệ: $name" }

                            val target = File(stage, name)
                            require(
                                target.canonicalPath.startsWith(
                                    stage.canonicalPath + File.separator
                                )
                            ) { "Đường dẫn giải nén không hợp lệ" }

                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { output ->
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }

            val manifestFile = File(stage, "manifest.json")
            val partsFile = File(stage, "parts.jsonl")
            val movementsFile = File(stage, "movements.jsonl")

            require(manifestFile.isFile && partsFile.isFile && movementsFile.isFile) {
                "ZIP thiếu manifest hoặc dữ liệu linh kiện/lịch sử"
            }

            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            require(manifest.optInt("version") == 2) {
                "Phiên bản tệp ZIP không được hỗ trợ"
            }

            val expectedParts = manifest.optInt("partCount", -1)
            val expectedMovements = manifest.optInt("movementCount", -1)
            require(expectedParts >= 0 && expectedMovements >= 0) {
                "Thông tin số lượng dữ liệu không hợp lệ"
            }

            fun countLines(file: File): Int =
                file.useLines { lines -> lines.count { it.isNotBlank() } }

            require(countLines(partsFile) == expectedParts) {
                "Số lượng linh kiện trong ZIP không khớp"
            }
            require(countLines(movementsFile) == expectedMovements) {
                "Số lượng lịch sử trong ZIP không khớp"
            }

            AppDb.get(context).withTransaction {
            dao.clearMovements()
            dao.clearParts()

        // Chuyển ảnh vào vùng lưu trữ riêng của ứng dụng.
            val photoDir = File(context.filesDir, "part_photos")
            photoDir.mkdirs()
            val photoPaths = mutableMapOf<String, String>()

    fun readPart(line: String): Part {
        val item = JSONObject(line)
        val photoEntry = item.optString("photoEntry", "")
        val restoredPhoto = if (photoEntry.isNotBlank()) {
            photoPaths.getOrPut(photoEntry) {
                val source = File(stage, photoEntry)
                if (source.isFile) {
                    val target = File(photoDir, "${UUID.randomUUID()}.jpg")
                    source.copyTo(target, overwrite = true)
                    target.absolutePath
                } else ""
            }
        } else {
            ""
        }

        return Part(
            code = item.getString("code"),
            name = item.getString("name"),
            quantity = item.optInt("quantity", 0),
            minQuantity = item.optInt("minQuantity", 0),
            unit = item.optString("unit", "cái"),
            photoPath = restoredPhoto,
            note = item.optString("note", ""),
            updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
            trackingMode = item.optString("trackingMode", "SINGLE")
        )
    }

    fun readMovement(line: String): Movement {
        val item = JSONObject(line)
        return Movement(
            code = item.getString("code"),
            partName = item.getString("partName"),
            type = item.getString("type"),
            amount = item.optInt("amount", 0),
            beforeQty = item.optInt("beforeQty", 0),
            afterQty = item.optInt("afterQty", 0),
            reason = item.optString("reason", ""),
            machineOrStore = item.optString("machineOrStore", ""),
            time = item.optLong("time", System.currentTimeMillis())
        )
    }


            val partBatch = ArrayList<Part>(200)

        partsFile.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        partBatch.add(readPart(line))
                        if (partBatch.size == 200) {
                            dao.insertParts(partBatch.toList())
                            partBatch.clear()
                        }
                    }
                }
            }
            if (partBatch.isNotEmpty()) {
                    dao.insertParts(partBatch)
                }

                val movementBatch = ArrayList<Movement>(200)
                movementsFile.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        movementBatch.add(readMovement(line))
                        if (movementBatch.size == 200) {
                            dao.insertMovements(movementBatch.toList())
                            movementBatch.clear()
                        }
                    }
                }
            }
            if (movementBatch.isNotEmpty()) {
                    dao.insertMovements(movementBatch)
                }

            }

        return expectedParts to expectedMovements
        } finally {
            stage.deleteRecursively()
        }
    }
}
