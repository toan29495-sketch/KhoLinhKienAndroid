package vn.kholinhkien

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.auth.auth
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Owner-only destructive reset. The cloud Edge Function removes this owner's
 * Storage objects and calls the owner-bound SQL RPC before Room is cleared.
 * User-downloaded ZIP files and private restore-inbox archives are intentionally
 * not touched, so a previously saved archive can be imported again.
 */
@Composable
internal fun WarehouseWipeSection(
    ownerId: String,
    username: String,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { AuthRepository() }
    var showWarning by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Làm mới kho", style = MaterialTheme.typography.titleMedium)
            Text(
                "Xóa toàn bộ linh kiện, tồn kho, lịch sử, hàng đợi đồng bộ, dữ liệu khôi phục và backup Cloud của chính tài khoản đang đăng nhập. Không xóa tài khoản/mật khẩu và không xóa các tệp ZIP đã tải, lưu trên điện thoại.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { showWarning = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Xóa toàn bộ dữ liệu kho") }
        }
    }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = { if (!busy) showWarning = false },
            title = { Text("Xóa sạch kho của tài khoản này?") },
            text = {
                Text(
                    "Thao tác này xóa dữ liệu kho trên điện thoại và Cloud, bao gồm ảnh linh kiện và mọi bản backup Cloud. Không thể hoàn tác. Kho của KTV khác và tài khoản đăng nhập không bị ảnh hưởng. Các ZIP đã lưu trên máy được giữ lại."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showWarning = false
                    password = ""
                    showPassword = true
                }) { Text("Tiếp tục") }
            },
            dismissButton = { TextButton(onClick = { showWarning = false }) { Text("Hủy") } }
        )
    }

    if (showPassword) {
        AlertDialog(
            onDismissRequest = { if (!busy) { showPassword = false; password = "" } },
            title = { Text("Xác nhận bằng mật khẩu") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nhập mật khẩu đăng nhập của @$username để xác thực lại trước khi xóa.")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Mật khẩu đăng nhập") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !busy
                    )
                    Text(
                        "Chỉ xóa kho của tài khoản hiện tại. Không thể dùng màn hình Admin để xóa kho của KTV khác.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = password.isNotBlank() && !busy,
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        scope.launch {
                            try {
                                require(isCanonicalOwnerId(ownerId)) { "Không xác định được chủ kho hợp lệ" }
                                require(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) {
                                    "Phiên đăng nhập đã thay đổi; dừng thao tác xóa"
                                }
                                repository.reauthenticateForWarehouseWipe(username, password, ownerId)
                                // Prevent local restore/backup workers from racing the destructive operation.
                                RestoreOperationGate.mutex.withLock {
                                    WeeklyBackupExecutionLock.withOwner(ownerId) {
                                        SyncWorkScheduler.cancelOwner(context, ownerId)
                                        RestoreRecoveryScheduler.cancelOwner(context, ownerId)
                                        WeeklyBackupWorkScheduler.cancel(context, ownerId)
                                        repository.purgeCloudWarehouseAfterReauthentication(ownerId)
                                        require(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) {
                                            "Phiên đăng nhập thay đổi; không xóa dữ liệu cục bộ"
                                        }
                                        AppDb.get(context).dao().clearEntireOwnerWarehouse(ownerId)
                                    }
                                }
                                onMessage("Đã xóa kho Cloud và dữ liệu kho trên điện thoại của @$username. Tài khoản và các tệp ZIP đã lưu trên máy được giữ nguyên. Hãy nhập lại ZIP để kiểm tra khôi phục; sau khi khôi phục thành công ứng dụng sẽ tạo backup Cloud mới.")
                                showPassword = false
                                password = ""
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                onMessage("Chưa thể xác nhận xóa sạch kho: ${error.message ?: "lỗi không xác định"}. Dữ liệu cục bộ được giữ nếu máy chủ chưa xác nhận hoàn tất.")
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text(if (busy) "Đang xóa…" else "Xác thực và xóa sạch") }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) { showPassword = false; password = "" } }, enabled = !busy) {
                    Text("Hủy")
                }
            }
        )
    }
}
