from pathlib import Path

p = Path("app/src/main/java/vn/kholinhkien/MainActivity.kt")
s = p.read_text(encoding="utf-8")

start = s.find("    val scope = rememberCoroutineScope()\n    val backupLauncher")
end = s.find("    Column(Modifier.fillMaxSize()", start)

if start < 0 or end < 0 or end <= start:
    raise SystemExit("Khong tim thay doan backup. Khong sua file.")

new = r'''    val scope = rememberCoroutineScope()
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val temp = File(context.cacheDir, "backup_${System.currentTimeMillis()}.json")
                try {
                    withContext(Dispatchers.IO) {
                        FileOutputStream(temp).bufferedWriter(Charsets.UTF_8).use { w ->
                            w.write("{\"version\":1,\"parts\":[")
                            parts.forEachIndexed { i, p ->
                                if (i > 0) w.write(",")
                                w.write("{")
                                w.write("\"code\":${JSONObject.quote(p.code)},")
                                w.write("\"name\":${JSONObject.quote(p.name)},")
                                w.write("\"quantity\":${p.quantity},")
                                w.write("\"minQuantity\":${p.minQuantity},")
                                w.write("\"unit\":${JSONObject.quote(p.unit)},")
                                w.write("\"photoPath\":${JSONObject.quote(p.photoPath)},")
                                w.write("\"photoBase64\":\"")
                                val f = File(p.photoPath)
                                if (p.photoPath.isNotBlank() && f.isFile) {
                                    java.io.FileInputStream(f).use { input ->
                                        val buf = ByteArray(12 * 1024)
                                        var carry = 0
                                        while (true) {
                                            val n = input.read(buf, carry, buf.size - carry)
                                            if (n < 0) {
                                                if (carry > 0) w.write(android.util.Base64.encodeToString(buf, 0, carry, android.util.Base64.NO_WRAP))
                                                break
                                            }
                                            if (n == 0) continue
                                            val total = carry + n
                                            val count = total - total % 3
                                            if (count > 0) w.write(android.util.Base64.encodeToString(buf, 0, count, android.util.Base64.NO_WRAP))
                                            carry = total - count
                                            if (carry > 0) System.arraycopy(buf, count, buf, 0, carry)
                                        }
                                    }
                                }
                                w.write("\",")
                                w.write("\"note\":${JSONObject.quote(p.note)},")
                                w.write("\"updatedAt\":${p.updatedAt},")
                                w.write("\"trackingMode\":${JSONObject.quote(p.trackingMode)}")
                                w.write("}")
                            }
                            w.write("],\"movements\":[")
                            movements.forEachIndexed { i, m ->
                                if (i > 0) w.write(",")
                                w.write("{")
                                w.write("\"code\":${JSONObject.quote(m.code)},")
                                w.write("\"partName\":${JSONObject.quote(m.partName)},")
                                w.write("\"type\":${JSONObject.quote(m.type)},")
                                w.write("\"amount\":${m.amount},")
                                w.write("\"beforeQty\":${m.beforeQty},")
                                w.write("\"afterQty\":${m.afterQty},")
                                w.write("\"reason\":${JSONObject.quote(m.reason)},")
                                w.write("\"machineOrStore\":${JSONObject.quote(m.machineOrStore)},")
                                w.write("\"time\":${m.time}")
                                w.write("}")
                            }
                            w.write("]}")
                        }
                        check(temp.isFile && temp.length() > 0L) { "Tep sao luu tam rong" }
                        val out = context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("Khong mo duoc tep de ghi")
                        out.use { dst ->
                            java.io.FileInputStream(temp).use { it.copyTo(dst, 65536) }
                            dst.flush()
                        }
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Khong doc lai duoc tep")
                        val size = input.use { stream ->
                            val b = ByteArray(65536)
                            var total = 0L
                            while (true) {
                                val n = stream.read(b)
                                if (n < 0) break
                                total += n
                            }
                            total
                        }
                        check(size > 0L && size == temp.length()) { "Kich thuoc tep sau khi ghi khong khop" }
                    }
                    onMessage("Sao luu thanh cong: ${parts.size} ma, ${movements.size} giao dich.")
                } catch (e: Exception) {
                    onMessage("Sao luu that bai: ${e.message ?: "Loi khong xac dinh"}")
                } finally {
                    withContext(Dispatchers.IO) { temp.delete() }
                }
            }
        }
    }
'''

p.write_text(s[:start] + new + s[end:], encoding="utf-8")
print("DA SUA DOAN SAO LUU JSON")
