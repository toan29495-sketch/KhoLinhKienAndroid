package vn.kholinhkien

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun ChangePasswordScreen(
    onPasswordChanged: (UserProfile) -> Unit,
    onSignOut: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val repository = remember { AuthRepository() }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("ĐỔI MẬT KHẨU", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("Bạn cần tạo mật khẩu mới trước khi tiếp tục sử dụng ứng dụng.")
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Mật khẩu mới (ít nhất 6 ký tự)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = { Text("Nhập lại mật khẩu mới") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (message.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                when {
                    password.length < 6 -> message = "Mật khẩu mới phải có ít nhất 6 ký tự"
                    password != confirmPassword -> message = "Hai mật khẩu không trùng khớp"
                    else -> scope.launch {
                        loading = true
                        message = ""
                        try {
                            onPasswordChanged(repository.changePassword(password))
                        } catch (e: Exception) {
                            message = e.message ?: "Không đổi được mật khẩu"
                        } finally {
                            loading = false
                        }
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("LƯU MẬT KHẨU MỚI")
        }
        TextButton(onClick = onSignOut, enabled = !loading) { Text("Đăng xuất") }
    }
}
