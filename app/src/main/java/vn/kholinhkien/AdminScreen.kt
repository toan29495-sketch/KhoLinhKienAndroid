package vn.kholinhkien

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AdminScreen(
    ownerId: String,
    username: String,
    onMessage: (String) -> Unit,
    onSignOut: () -> Unit
) {
    val repository = remember { AuthRepository() }
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<Pair<UserProfile, String>?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var showAudit by remember { mutableStateOf(false) }
    var auditEvents by remember { mutableStateOf<List<AdminAuditEvent>>(emptyList()) }
    var auditLoading by remember { mutableStateOf(false) }
    var auditError by remember { mutableStateOf("") }

    suspend fun loadUsers() {
        loading = true
        error = ""
        try {
            users = repository.getKtvUsers()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            error = e.message ?: "Không tải được danh sách KTV"
        } finally {
            loading = false
        }
    }

    fun refreshUsers() {
        if (loading || actionBusy) return
        scope.launch { loadUsers() }
    }

    fun refreshAudit() {
        if (auditLoading || actionBusy) return
        scope.launch {
            auditLoading = true
            auditError = ""
            try {
                auditEvents = repository.getAdminAuditEvents(100)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                auditError = e.message ?: "Không tải được nhật ký quản trị"
            } finally {
                auditLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadUsers() }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Quản lý tài khoản KTV", style = MaterialTheme.typography.titleLarge)
                Text("Tổng số: ${users.size} tài khoản", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Kho của Admin vẫn là kho riêng theo tài khoản đang đăng nhập; danh sách này chỉ quản lý tài khoản KTV, không gộp linh kiện giữa các kho.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { refreshUsers() }, enabled = !loading && !actionBusy) {
                Icon(Icons.Default.Refresh, contentDescription = "Làm mới")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    showAudit = !showAudit
                    if (showAudit && auditEvents.isEmpty() && auditError.isBlank()) refreshAudit()
                },
                enabled = !actionBusy,
                modifier = Modifier.weight(1f)
            ) { Text(if (showAudit) "Ẩn nhật ký" else "Nhật ký quản trị") }
            Button(
                onClick = { showCreate = true },
                enabled = !loading && !actionBusy,
                modifier = Modifier.weight(1f)
            ) { Text("+ Tạo tài khoản KTV") }
            OutlinedButton(onClick = onSignOut, enabled = !actionBusy) { Text("Đăng xuất") }
        }
        WarehouseWipeSection(ownerId = ownerId, username = username, onMessage = onMessage)
        if (showAudit) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Nhật ký thao tác Admin", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { refreshAudit() }, enabled = !auditLoading && !actionBusy) { Text("Tải lại") }
                    }
                    Text("Hiển thị tối đa 100 sự kiện mới nhất.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (auditError.isNotBlank()) Text(auditError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (auditLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!auditLoading && auditEvents.isEmpty() && auditError.isBlank()) {
                        Text("Chưa có sự kiện quản trị.", style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(auditEvents, key = { it.id }) { event ->
                            HorizontalDivider()
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(event.action, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    Text(event.status, color = when (event.status) {
                                        "SUCCEEDED" -> MaterialTheme.colorScheme.primary
                                        "FAILED" -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }, style = MaterialTheme.typography.labelMedium)
                                }
                                Text("Thực hiện: ${event.actorUsername} · Đối tượng: ${event.targetUsername ?: "—"}", style = MaterialTheme.typography.bodySmall)
                                Text("Bắt đầu: ${event.createdAt}", style = MaterialTheme.typography.bodySmall)
                                event.finishedAt?.let { Text("Kết thúc: $it", style = MaterialTheme.typography.bodySmall) }
                                event.errorCode?.takeIf { it.isNotBlank() }?.let {
                                    Text("Mã lỗi: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (loading && users.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (users.isEmpty() && error.isBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Chưa có tài khoản KTV") }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(users, key = { it.id }) { user ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(user.displayName.ifBlank { user.username }, style = MaterialTheme.typography.titleMedium)
                                    Text("@${user.username}", style = MaterialTheme.typography.bodySmall)
                                }
                                AssistChip(
                                    onClick = {},
                                    label = { Text(if (user.isActive) "Đang hoạt động" else "Đã khóa") },
                                    colors = AssistChipDefaults.assistChipColors(
                                        labelColor = if (user.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                )
                            }
                            if (user.mustChangePassword) {
                                Text("Chưa đổi mật khẩu mặc định", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { pendingAction = user to "reset_password" },
                                    enabled = !actionBusy,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Đặt lại mật khẩu") }
                                OutlinedButton(
                                    onClick = { pendingAction = user to if (user.isActive) "lock" else "unlock" },
                                    enabled = !actionBusy,
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (user.isActive) "Khóa" else "Mở khóa") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        var username by remember { mutableStateOf("") }
        var displayName by remember { mutableStateOf("") }
        var saving by remember { mutableStateOf(false) }
        val normalizedUsername = username.trim().lowercase()
        val validUsername = Regex("^[a-z0-9_-]{3,32}$").matches(normalizedUsername)
        val normalizedDisplayName = displayName.trim()
        val validDisplayName = normalizedDisplayName.isNotEmpty() && normalizedDisplayName.length <= 80
        AlertDialog(
            onDismissRequest = { if (!saving && !actionBusy) showCreate = false },
            title = { Text("Tạo tài khoản KTV") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(username, { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }, label = { Text("Tên đăng nhập") }, singleLine = true)
                    OutlinedTextField(displayName, { displayName = it }, label = { Text("Tên hiển thị") }, singleLine = true)
                    if (username.isNotBlank() && !validUsername) {
                        Text("Tên đăng nhập phải có 3–32 ký tự a-z, 0-9, _ hoặc -.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (displayName.isNotBlank() && !validDisplayName) {
                        Text("Tên hiển thị tối đa 80 ký tự và không được để trống.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Mật khẩu ban đầu: 000000. KTV phải đổi mật khẩu khi đăng nhập lần đầu.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = !saving && !actionBusy && validUsername && validDisplayName, onClick = {
                    scope.launch {
                        saving = true
                        actionBusy = true
                        try {
                            repository.createKtv(normalizedUsername, normalizedDisplayName)
                            showCreate = false
                            onMessage("Đã tạo tài khoản KTV $normalizedUsername. Mật khẩu ban đầu: 000000.")
                            loadUsers()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            onMessage(e.message ?: "Không tạo được tài khoản")
                        } finally {
                            actionBusy = false
                            saving = false
                        }
                    }
                }) { Text(if (saving) "Đang tạo…" else "Tạo") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }, enabled = !saving && !actionBusy) { Text("Hủy") } }
        )
    }

    pendingAction?.let { (user, action) ->
        val title = when (action) {
            "reset_password" -> "Đặt lại mật khẩu?"
            "lock" -> "Khóa tài khoản?"
            else -> "Mở khóa tài khoản?"
        }
        val description = when (action) {
            "reset_password" -> "Mật khẩu của ${user.username} sẽ được đặt lại thành 000000 và buộc đổi khi đăng nhập tiếp theo."
            "lock" -> "${user.username} sẽ không thể đăng nhập cho đến khi được mở khóa."
            else -> "Cho phép ${user.username} đăng nhập trở lại."
        }
        AlertDialog(
            onDismissRequest = { if (!actionBusy) pendingAction = null },
            title = { Text(title) },
            text = { Text(description) },
            confirmButton = {
                TextButton(onClick = {
                    if (!actionBusy) scope.launch {
                        actionBusy = true
                        try {
                            when (action) {
                                "reset_password" -> repository.resetKtvPassword(user.username)
                                "lock" -> repository.lockKtv(user.username)
                                else -> repository.unlockKtv(user.username)
                            }
                            pendingAction = null
                            onMessage("Đã cập nhật tài khoản ${user.username}.")
                            loadUsers()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            onMessage(e.message ?: "Thao tác thất bại")
                        } finally {
                            actionBusy = false
                        }
                    }
                }, enabled = !actionBusy) { Text(if (actionBusy) "Đang xử lý…" else "Xác nhận") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }, enabled = !actionBusy) { Text("Hủy") } }
        )
    }
}
