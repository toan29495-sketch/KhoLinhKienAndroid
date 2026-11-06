package vn.kholinhkien

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.jan.supabase.postgrest.from

@Composable
fun SupabaseConnectionTest() {
    var status by remember {
        mutableStateOf("Đang kiểm tra Supabase...")
    }

    LaunchedEffect(Unit) {
        status = withContext(Dispatchers.IO) {
            try {
                SupabaseClientProvider.client
                    .from("profiles")
                    .select()
                "KẾT NỐI SUPABASE THÀNH CÔNG"
            } catch (e: Exception) {
                "KẾT NỐI THẤT BẠI: ${e.message}"
            }
        }
    }

    Text(text = status)
}
