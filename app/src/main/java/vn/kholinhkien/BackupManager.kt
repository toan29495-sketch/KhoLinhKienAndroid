package vn.kholinhkien

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BackupManager {

    fun createZip(
        context: Context,
        uri: Uri,
        parts: List<Part>,
        movements: List<Movement>
    ) {
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("Không mở được tệp ZIP")

        ZipOutputStream(output.buffered()).use { zip ->
            val photos = linkedMapOf<String, String>()

            parts.forEach { part ->
                val path = part.photoPath
                if (path.isNotBlank() && java.io.File(path).isFile) {
                    photos.getOrPut(path) {
                        "photos/${photos.size + 1}.jpg"
                    }
                }
            }

            val manifest = JSONObject()
                .put("version", 2)
                .put("partCount", parts.size)
                .put("movementCount", movements.size)

            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("parts.jsonl"))
            BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8)).use { writer ->
                parts.forEach { p ->
                    val item = JSONObject()
                        .put("code", p.code)
                        .put("name", p.name)
                        .put("quantity", p.quantity)
                        .put("minQuantity", p.minQuantity)
                        .put("unit", p.unit)
                        .put("photoPath", p.photoPath)
                        .put("photoEntry", photos[p.photoPath] ?: "")
                        .put("note", p.note)
                        .put("updatedAt", p.updatedAt)
                        .put("trackingMode", p.trackingMode)

                    writer.write(item.toString())
                    writer.newLine()
                }
                writer.flush()
            }

            zip.putNextEntry(ZipEntry("movements.jsonl"))
            BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8)).use { writer ->
                movements.forEach { m ->
                    val item = JSONObject()
                        .put("code", m.code)
                        .put("partName", m.partName)
                        .put("type", m.type)
                        .put("amount", m.amount)
                        .put("beforeQty", m.beforeQty)
                        .put("afterQty", m.afterQty)
                        .put("reason", m.reason)
                        .put("machineOrStore", m.machineOrStore)
                        .put("time", m.time)

                    writer.write(item.toString())
                    writer.newLine()
                }
                writer.flush()
            }

            photos.forEach { (source, entryName) ->
                val file = java.io.File(source)
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().buffered().use { input ->
                    input.copyTo(zip, 64 * 1024)
                }
                zip.closeEntry()
            }
        }
    }
}
