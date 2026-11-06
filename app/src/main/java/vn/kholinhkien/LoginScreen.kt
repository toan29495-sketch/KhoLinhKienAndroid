package vn.kholinhkien

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@Composable
fun LoginScreen(
    onLoginSuccess: (UserProfile) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val repository = remember { AuthRepository() }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "KIỂM SOÁT LINH KIỆN",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Đăng nhập Admin / KTV",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Tên tài khoản") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Mật khẩu") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        if (message.isNotBlank()) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = {
                if (username.isBlank() || password.isBlank()) {
                    message = "Vui lòng nhập tài khoản và mật khẩu"
                    return@Button
                }

                scope.launch {
                    loading = true
                    message = ""

                    try {
                        val profile = withContext(Dispatchers.IO) {
                            repository.signIn(username, password)
                            repository.currentUserId()?.let { SessionExpiryPolicy.markAuthenticated(context, it) }
                            repository.getCurrentProfile()
                        }
                        onLoginSuccess(profile)
                    } catch (e: Exception) {
                        message = e.message ?: "Đăng nhập thất bại"
                    } finally {
                        loading = false
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text("ĐĂNG NHẬP")
            }
        }
    }
}
