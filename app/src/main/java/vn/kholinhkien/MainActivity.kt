package vn.kholinhkien

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { InventoryApp() } }
    }
}

@Composable
fun InventoryApp() {
    val context = LocalContext.current
    val dao = remember { AppDb.get(context).dao() }
    val scope = rememberCoroutineScope()
    val parts by dao.parts().collectAsState(initial = emptyList())
    val movements by dao.movements().collectAsState(initial = emptyList())
    // Mã được đánh dấu đã sử dụng khi đã có ít nhất một giao dịch xuất kho.
    val usedCodes = remember(movements) { movements.asSequence().filter { it.type == "XUẤT" }.map { it.code }.toSet() }
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Part?>(null) }
    var message by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var prefillCode by remember { mutableStateOf("") }
    var showEdit by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf(false) }
    var pendingPhotoPath by remember { mutableStateOf("") }
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    val cameraCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = captureUri
        if (success && uri != null) {
            try { pendingPhotoPath = copyImageToPrivateStorage(context, uri); message = "Đã chụp và lưu ảnh linh kiện" }
            catch (e: Exception) { message = "Không lưu được ảnh chụp: ${e.message}" }
        }
    }
    val takePhoto: () -> Unit = {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "KhoLinhKien_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KhoLinhKien")
        }
        captureUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        captureUri?.let { cameraCapture.launch(it) }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try { pendingPhotoPath = copyImageToPrivateStorage(context, uri) }
            catch (e: Exception) { message = "Không lưu được ảnh: ${e.message}" }
        }
    }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreUri = uri
    }
    var batchCodes by remember { mutableStateOf("") }
    var showBatch by remember { mutableStateOf(false) }
    val filtered = parts.filter { !(it.trackingMode == "SINGLE" && it.quantity <= 0 && usedCodes.contains(it.code)) && (it.name.contains(query, true) || it.code.contains(query, true) || it.note.contains(query, true)) }
    Scaffold(
        topBar = {
            TopAppBarCompat(title = when(tab) { 0 -> "Kho linh kiện"; 1 -> "Lịch sử giao dịch"; else -> "Công cụ & sao lưu" },
                onScan = { scan = true }, onAdd = { showAdd = true })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Inventory2, null) }, label = { Text("Tồn kho") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.History, null) }, label = { Text("Lịch sử") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Build, null) }, label = { Text("Công cụ") })
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 14.dp)) {
            when(tab) {
                0 -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SummaryCard("Mã linh kiện", parts.size.toString(), Modifier.weight(1f))
                        SummaryCard("Tổng số lượng", parts.sumOf { it.quantity }.toString(), Modifier.weight(1f))
                        SummaryCard("Sắp hết", parts.count { it.quantity <= it.minQuantity }.toString(), Modifier.weight(1f))
                    }
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Tìm tên / mã linh kiện") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
                    if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Chưa có linh kiện. Nhấn + để thêm hoặc quét QR.") }
                    else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filtered, key = { it.id }) { p ->
                            Card(Modifier.fillMaxWidth().clickable { selected = p }) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (usedCodes.contains(p.code)) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = "QR đã sử dụng", tint = androidx.compose.ui.graphics.Color(0xFF2E7D32), modifier = Modifier.size(24.dp))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                                        Text("Mã: ${p.code}", style = MaterialTheme.typography.bodySmall)
                                        Text("Tồn: ${p.quantity} ${p.unit} · Mức tối thiểu: ${p.minQuantity}", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (p.quantity <= p.minQuantity) AssistChip(onClick = { selected = p }, label = { Text("Sắp hết") })
                                    Icon(Icons.Default.ChevronRight, null)
                                }
                            }
                        }
                    }
                }
                1 -> if (movements.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Chưa có giao dịch") }
                    else LazyColumn { items(movements, key = { it.id }) { m ->
                        ListItem(headlineContent = { Text("${m.type} ${m.amount} · ${m.partName}") },
                            supportingContent = { Text("${m.code} | ${m.beforeQty} → ${m.afterQty} | ${m.reason} ${m.machineOrStore}") },
                            trailingContent = { Text(SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(m.time))) })
                        HorizontalDivider()
                    } }
                2 -> ToolsScreen(parts, movements, onBatch = { showBatch = true }, onRestore = { restoreLauncher.launch(arrayOf("application/json", "text/*")) }, onMessage = { message = it })
            }
        }
    }
    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Xác nhận khôi phục") },
            text = { Text("Khôi phục sẽ thay thế toàn bộ danh sách linh kiện và lịch sử trên điện thoại này. Hãy chắc chắn bạn đã sao lưu dữ liệu hiện tại.") },
            confirmButton = {
                TextButton(onClick = {
                    restoreUri = null
                    scope.launch {
                        try {
                            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                                ?: error("Không đọc được tệp")
                            val root = JSONObject(raw)
                            val newParts = mutableListOf<Part>()
                            val pa = root.getJSONArray("parts")
                            for (i in 0 until pa.length()) {
                                val o = pa.getJSONObject(i)
                                newParts.add(Part(code=o.getString("code"), name=o.optString("name"), quantity=o.optInt("quantity"),
                                    minQuantity=o.optInt("minQuantity"), unit=o.optString("unit", "cái"),
                                    photoPath=restorePhotoFromBase64(context, o.optString("photoBase64"), o.optString("photoPath")),
                                    note=o.optString("note"), updatedAt=o.optLong("updatedAt", System.currentTimeMillis()), trackingMode=o.optString("trackingMode", "SINGLE")))
                            }
                            val newMoves = mutableListOf<Movement>()
                            val ma = root.optJSONArray("movements") ?: JSONArray()
                            for (i in 0 until ma.length()) {
                                val o = ma.getJSONObject(i)
                                newMoves.add(Movement(code=o.getString("code"), partName=o.optString("partName"), type=o.optString("type"),
                                    amount=o.optInt("amount"), beforeQty=o.optInt("beforeQty"), afterQty=o.optInt("afterQty"),
                                    reason=o.optString("reason"), machineOrStore=o.optString("machineOrStore"), time=o.optLong("time", System.currentTimeMillis())))
                            }
                            require(newParts.map { it.code }.distinct().size == newParts.size) { "Tệp có mã linh kiện trùng nhau" }
                            dao.restoreAll(newParts, newMoves)
                            message = "Khôi phục xong ${newParts.size} mã và ${newMoves.size} giao dịch."
                        } catch (e: Exception) { message = "Khôi phục thất bại: ${e.message}" }
                    }
                }) { Text("Khôi phục và thay thế") }
            },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Hủy") } }
        )
    }
    if (showAdd) AddPartDialog(initialCode = prefillCode, photoPath = pendingPhotoPath, onChoosePhoto = { photoPicker.launch("image/*") }, onTakePhoto = takePhoto, onDismiss = { showAdd = false; pendingPhotoPath = ""; prefillCode = "" }, onSave = { part ->
        scope.launch {
            try {
                dao.insert(part.copy(photoPath = pendingPhotoPath))
                if (part.quantity > 0) dao.addMovement(Movement(code = part.code, partName = part.name, type = "ĐẦU KỲ",
                    amount = part.quantity, beforeQty = 0, afterQty = part.quantity, reason = "Tồn ban đầu"))
                message = "Đã thêm ${part.code}"
            } catch (_: Exception) { message = "Mã đã tồn tại hoặc dữ liệu không hợp lệ" }
            showAdd = false
            pendingPhotoPath = ""
            prefillCode = ""
        }
    })
    selected?.let { p ->
        PartDialog(p, onDismiss = { selected = null }, onEdit = { pendingPhotoPath = p.photoPath; showEdit = true }, onDelete = {
            scope.launch {
                try {
                    dao.deletePartByCode(p.code)
                    selected = null
                    message = "Đã xóa mã QR khỏi danh mục. Lịch sử giao dịch vẫn được giữ."
                } catch (e: Exception) { message = "Không xóa được mã: ${e.message}" }
            }
        }, onMove = { type, amount, reason, machine ->
            scope.launch {
                try {
                    dao.changeStock(p.code, type, amount, reason, machine)
                    message = "Đã cập nhật tồn kho"
                    if (type == "XUẤT" && p.trackingMode == "SINGLE") selected = null
                }
                catch (e: Exception) { message = e.message ?: "Không thể cập nhật" }
            }
        }, onGenerateQr = {
            val bmp = qrBitmap(p.code, 500)
            saveBitmap(context, bmp, "QR_${p.code}.png")
            message = "Đã lưu ảnh QR vào thư viện ảnh"
        })
    }
    if (showEdit && selected != null) EditPartDialog(part = selected!!, photoPath = pendingPhotoPath,
        onChoosePhoto = { photoPicker.launch("image/*") }, onTakePhoto = takePhoto, onDismiss = { showEdit = false },
        onSave = { updated ->
            scope.launch {
                try { dao.update(updated.copy(photoPath = pendingPhotoPath, updatedAt = System.currentTimeMillis())); selected = updated.copy(photoPath = pendingPhotoPath); message = "Đã cập nhật linh kiện" }
                catch (e: Exception) { message = "Cập nhật thất bại: ${e.message}" }
                showEdit = false
            }
        })
    if (scan) ScanDialog(onDismiss = { scan = false }, onCode = { code ->
        scan = false
        scope.launch {
            selected = dao.byCode(code.trim())
            if (selected == null) {
                prefillCode = code.trim()
                message = "Chưa có mã $code. Nhập tên và số lượng để thêm linh kiện."
                showAdd = true
            }
        }
    })
    if (showBatch) BatchCodeDialog(initial = batchCodes, onDismiss = { showBatch = false }, onImport = { raw ->
        batchCodes = raw
        val supplied = raw.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val codes = supplied.filter { it.length == 10 && it.all(Char::isLetterOrDigit) }
        scope.launch {
            var added = 0
            for (code in codes) {
                if (dao.byCode(code) == null) {
                    try {
                        dao.insert(Part(code = code, name = "Linh kiện $code"))
                        saveBitmap(context, qrBitmap(code, 500), "QR_${code}.png")
                        added++
                    } catch (_: Exception) {}
                }
            }
            val rejected = supplied.size - codes.size
            message = "Đã tạo $added mã QR 10 ký tự. Bỏ qua $rejected mã không đúng định dạng hoặc đã trùng trong danh sách."
            showBatch = false
        }
    }, onOcr = { uri ->
        scope.launch {
            try {
                val image = InputImage.fromFilePath(context, uri)
                val result = withContext(Dispatchers.IO) { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image).awaitResult() }
                batchCodes = result.text.split(Regex("\\s+")).map { it.trim().filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
                    .filter { it.length == 10 }.distinct().joinToString("\n")
                message = "Đã nhận diện các chuỗi 10 ký tự. Hãy kiểm tra danh sách trước khi tạo."
            } catch (e: Exception) { message = "Không đọc được ảnh: ${e.message}" }
        }
    })
    if (message.isNotBlank()) AlertDialog(onDismissRequest = { message = "" }, confirmButton = { TextButton(onClick = { message = "" }) { Text("Đóng") } }, text = { Text(message) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBarCompat(title: String, onScan: () -> Unit, onAdd: () -> Unit) {
    TopAppBar(title = { Text(title) }, actions = {
        IconButton(onClick = onScan) { Icon(Icons.Default.QrCodeScanner, "Quét QR") }
        IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Thêm") }
    })
}

@Composable
fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(10.dp)) { Text(title, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.titleLarge) } }
}

@Composable
fun AddPartDialog(initialCode: String, photoPath: String, onChoosePhoto: () -> Unit, onTakePhoto: () -> Unit, onDismiss: () -> Unit, onSave: (Part) -> Unit) {
    var code by remember(initialCode) { mutableStateOf(initialCode) }; var name by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }; var min by remember { mutableStateOf("0") }
    var unit by remember { mutableStateOf("cái") }; var note by remember { mutableStateOf("") }
    var trackingMode by remember { mutableStateOf("SINGLE") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Thêm linh kiện") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(code, { code = it.trim() }, label = { Text("Mã QR / mã linh kiện *") }, singleLine = true)
            OutlinedTextField(name, { name = it }, label = { Text("Tên linh kiện *") }, singleLine = true)
            Text("Cách quản lý mã QR", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = trackingMode == "SINGLE", onClick = { trackingMode = "SINGLE"; if ((qty.toIntOrNull() ?: 0) > 1) qty = "1" }, label = { Text("Linh kiện lẻ (1 mã/1 món)") })
                FilterChip(selected = trackingMode == "BULK", onClick = { trackingMode = "BULK" }, label = { Text("Nhiều món/1 mã") })
            }
            OutlinedTextField(qty, { qty = it.filter(Char::isDigit) }, label = { Text("Số lượng ban đầu") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(min, { min = it.filter(Char::isDigit) }, label = { Text("Ngưỡng cảnh báo tồn") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(unit, { unit = it }, label = { Text("Đơn vị (cái, mét...)") }, singleLine = true)
            OutlinedTextField(note, { note = it }, label = { Text("Ghi chú") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTakePhoto, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CameraAlt, null); Text("Chụp ảnh") }
                OutlinedButton(onClick = onChoosePhoto, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Image, null); Text("Chọn ảnh") }
            }
            if (photoPath.isNotBlank()) Text("Đã lưu ảnh linh kiện", color = MaterialTheme.colorScheme.primary)
        } },
        confirmButton = { TextButton(enabled = code.isNotBlank() && name.isNotBlank(), onClick = { onSave(Part(code = code, name = name, quantity = qty.toIntOrNull() ?: 0, minQuantity = min.toIntOrNull() ?: 0, unit = unit.ifBlank { "cái" }, note = note, trackingMode = trackingMode)) }) { Text("Lưu") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } })
}

@Composable
fun PartDialog(p: Part, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onMove: (String, Int, String, String) -> Unit, onGenerateQr: () -> Unit) {
    var amount by remember { mutableStateOf("1") }; var reason by remember { mutableStateOf("Bảo trì") }; var machine by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("XUẤT") }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(p.name) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mã: ${p.code}")
            Text("Chế độ: ${if (p.trackingMode == "SINGLE") "Linh kiện lẻ · 1 mã/1 món" else "Nhiều món dùng chung 1 mã"}")
            Text("Tồn hiện tại: ${p.quantity} ${p.unit}")
            if (p.photoPath.isNotBlank() && File(p.photoPath).exists()) {
                val bmp = remember(p.photoPath) { BitmapFactory.decodeFile(p.photoPath) }
                if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "Ảnh linh kiện", modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp))
            }
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("Sửa thông tin / ảnh / chế độ QR") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = type == "XUẤT", onClick = { type = "XUẤT"; amount = "1" }, label = { Text("Xuất dùng") })
                FilterChip(selected = type == "NHẬP", onClick = { type = "NHẬP" }, label = { Text("Nhập kho") })
                FilterChip(selected = type == "KIỂM KÊ", onClick = { type = "KIỂM KÊ" }, label = { Text("Kiểm kê") })
            }
            if (type == "XUẤT" && p.trackingMode == "SINGLE") Text("Linh kiện lẻ: xuất 1 lần sẽ được ẩn khỏi danh sách tồn kho. Lịch sử vẫn được giữ.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text(if (type == "KIỂM KÊ") "Số lượng thực tế (có thể bằng 0)" else "Số lượng") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !(type == "XUẤT" && p.trackingMode == "SINGLE"))
            OutlinedTextField(reason, { reason = it }, label = { Text("Lý do") }, singleLine = true)
            OutlinedTextField(machine, { machine = it }, label = { Text("Mã máy / cửa hàng") }, singleLine = true)
            OutlinedButton(onClick = onGenerateQr, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCode, null); Spacer(Modifier.width(8.dp)); Text("Tạo / lưu ảnh QR cho mã này") }
            HorizontalDivider()
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("Xóa mã QR khỏi danh mục") }
        } },
        confirmButton = { TextButton(enabled = (amount.toIntOrNull() ?: 0) > 0 || type == "KIỂM KÊ", onClick = { onMove(type, amount.toIntOrNull() ?: 0, reason, machine) }) { Text("Xác nhận $type") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Xóa mã QR?") },
        text = { Text("Mã ${p.code} sẽ bị xóa khỏi danh sách linh kiện. Lịch sử giao dịch sẽ được giữ lại, nhưng mã này không còn dùng để quét nhập/xuất cho đến khi được thêm lại.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Xóa mã") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Hủy") } })
}

@Composable
fun EditPartDialog(part: Part, photoPath: String, onChoosePhoto: () -> Unit, onTakePhoto: () -> Unit, onDismiss: () -> Unit, onSave: (Part) -> Unit) {
    var name by remember(part.id) { mutableStateOf(part.name) }
    var min by remember(part.id) { mutableStateOf(part.minQuantity.toString()) }
    var unit by remember(part.id) { mutableStateOf(part.unit) }
    var note by remember(part.id) { mutableStateOf(part.note) }
    var trackingMode by remember(part.id) { mutableStateOf(part.trackingMode) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Sửa linh kiện") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Mã QR: ${part.code} (không thay đổi mã)")
            OutlinedTextField(name, { name = it }, label = { Text("Tên linh kiện") }, singleLine = true)
            Text("Cách quản lý mã QR", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = trackingMode == "SINGLE", onClick = { trackingMode = "SINGLE" }, label = { Text("Linh kiện lẻ") })
                FilterChip(selected = trackingMode == "BULK", onClick = { trackingMode = "BULK" }, label = { Text("Nhiều món/1 mã") })
            }
            OutlinedTextField(min, { min = it.filter(Char::isDigit) }, label = { Text("Ngưỡng cảnh báo") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(unit, { unit = it }, label = { Text("Đơn vị") }, singleLine = true)
            OutlinedTextField(note, { note = it }, label = { Text("Ghi chú") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTakePhoto, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CameraAlt, null); Text("Chụp ảnh") }
                OutlinedButton(onClick = onChoosePhoto, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Image, null); Text("Chọn ảnh") }
            }
            if (photoPath.isNotBlank()) Text("Đã lưu ảnh linh kiện", color = MaterialTheme.colorScheme.primary)
            Text("Dùng nút Kiểm kê trong màn hình linh kiện để cập nhật số lượng thực tế và lưu lịch sử chênh lệch.", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(part.copy(name=name, minQuantity=min.toIntOrNull() ?: 0, unit=unit.ifBlank { "cái" }, note=note, trackingMode=trackingMode)) }) { Text("Lưu") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } })
}

@Composable
fun BatchCodeDialog(initial: String, onDismiss: () -> Unit, onImport: (String) -> Unit, onOcr: (android.net.Uri) -> Unit) {
    var codes by remember { mutableStateOf(initial) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(onOcr) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Tạo mã QR hàng loạt") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Dán danh sách mã, mỗi mã một dòng. Có thể chọn ảnh chụp/in danh sách mã để nhận diện OCR. Các mã 10 ký tự sẽ được lọc để kiểm tra.")
            OutlinedButton(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Image, null); Text("Đọc mã từ ảnh") }
            OutlinedTextField(codes, { codes = it }, modifier = Modifier.fillMaxWidth().height(170.dp), label = { Text("Danh sách mã") })
        } },
        confirmButton = { TextButton(onClick = { onImport(codes) }) { Text("Tạo linh kiện") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } })
}

@Composable
fun ToolsScreen(parts: List<Part>, movements: List<Movement>, onBatch: () -> Unit, onRestore: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) try {
            context.contentResolver.openOutputStream(uri)?.use { writeXlsx(it, parts, movements) }
            onMessage("Đã xuất tệp Excel XLSX gồm 2 trang: Tồn kho và Lịch sử.")
        } catch (e: Exception) { onMessage("Xuất Excel thất bại: ${e.message}") }
    }
    val scope = rememberCoroutineScope()
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
                        val out = context.contentResolver.openOutputStream(uri)
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Công cụ", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = onBatch, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCode, null); Spacer(Modifier.width(8.dp)); Text("Tạo mã linh kiện hàng loạt / đọc từ ảnh") }
        OutlinedButton(onClick = { exportLauncher.launch("bao_cao_kho.xlsx") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Xuất Excel XLSX (tồn kho + lịch sử)") }
        OutlinedButton(onClick = { backupLauncher.launch("sao_luu_kho.json") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Backup, null); Spacer(Modifier.width(8.dp)); Text("Sao lưu dữ liệu JSON") }
        OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("Khôi phục từ tệp JSON (thay dữ liệu hiện tại)") }
        Text("Tổng mã: ${parts.size} · Tổng tồn: ${parts.sumOf { it.quantity }} · Giao dịch: ${movements.size}")
        Text("Cảnh báo: khôi phục sẽ thay thế dữ liệu hiện tại. Hãy sao lưu trước khi khôi phục.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun ScanDialog(onDismiss: () -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Quét mã QR") },
        text = { if (!granted) Text("Cần cấp quyền camera để quét QR.") else {
            AndroidView(factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    val scanner = BarcodeScanning.getClient()
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                        val media = proxy.image
                        if (media != null) {
                            scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                                .addOnSuccessListener { barcodes -> barcodes.firstOrNull()?.rawValue?.let(onCode) }
                                .addOnCompleteListener { proxy.close() }
                        } else proxy.close()
                    }
                    try { provider.unbindAll(); provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }, modifier = Modifier.fillMaxWidth().height(320.dp))
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } })
}

fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
    return bmp
}
fun saveBitmap(context: android.content.Context, bitmap: Bitmap, name: String) {
    val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KhoLinhKien") }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
    context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}



fun encodePhotoBase64(path: String): String {
    if (path.isBlank()) return ""
    val file = File(path)
    if (!file.exists()) return ""
    return try { android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP) } catch (_: Exception) { "" }
}

fun restorePhotoFromBase64(context: android.content.Context, encoded: String, oldPath: String): String {
    if (encoded.isBlank()) return if (oldPath.isNotBlank() && File(oldPath).exists()) oldPath else ""
    val dir = File(context.filesDir, "part_photos").apply { mkdirs() }
    val file = File(dir, "restored_${System.currentTimeMillis()}_${(1000..9999).random()}.jpg")
    return try {
        file.writeBytes(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
        file.absolutePath
    } catch (_: Exception) { "" }
}

fun copyImageToPrivateStorage(context: android.content.Context, uri: Uri): String {
    val dir = File(context.filesDir, "part_photos").apply { mkdirs() }
    val dest = File(dir, "part_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri).use { input ->
        val stream = requireNotNull(input) { "Không mở được ảnh đã chọn" }
        FileOutputStream(dest).use { output -> stream.copyTo(output) }
    }
    return dest.absolutePath
}

private fun xmlEscape(value: String): String = value
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&apos;")

private fun colName(number: Int): String {
    var n = number
    var out = ""
    while (n > 0) {
        val r = (n - 1) % 26
        out = ('A'.code + r).toChar() + out
        n = (n - 1) / 26
    }
    return out
}

private fun sheetXml(rows: List<List<String>>): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
    rows.forEachIndexed { ri, row ->
        append("<row r=\"${ri + 1}\">")
        row.forEachIndexed { ci, value ->
            val ref = "${colName(ci + 1)}${ri + 1}"
            append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlEscape(value)}</t></is></c>")
        }
        append("</row>")
    }
    append("</sheetData></worksheet>")
}

fun writeXlsx(output: java.io.OutputStream, parts: List<Part>, movements: List<Movement>) {
    val inventoryRows = mutableListOf(
        listOf("Mã linh kiện", "Tên linh kiện", "Tồn kho", "Ngưỡng tối thiểu", "Đơn vị", "Ghi chú", "Mã QR"),
    )
    parts.forEach { p -> inventoryRows.add(listOf(p.code, p.name, p.quantity.toString(), p.minQuantity.toString(), p.unit, p.note, p.code)) }
    val movementRows = mutableListOf(
        listOf("Thời gian", "Mã linh kiện", "Tên linh kiện", "Loại", "Số lượng", "Tồn trước", "Tồn sau", "Lý do", "Máy / cửa hàng"),
    )
    val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    movements.forEach { m -> movementRows.add(listOf(df.format(Date(m.time)), m.code, m.partName, m.type,
        m.amount.toString(), m.beforeQty.toString(), m.afterQty.toString(), m.reason, m.machineOrStore)) }
    ZipOutputStream(output).use { zip ->
        fun entry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
        entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Tồn kho" sheetId="1" r:id="rId1"/><sheet name="Lịch sử" sheetId="2" r:id="rId2"/></sheets></workbook>""")
        entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/></Relationships>""")
        entry("xl/worksheets/sheet1.xml", sheetXml(inventoryRows))
        entry("xl/worksheets/sheet2.xml", sheetXml(movementRows))
    }
}

fun csvCell(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWith(Result.failure(it)) }
        addOnCanceledListener { cont.cancel() }
    }
