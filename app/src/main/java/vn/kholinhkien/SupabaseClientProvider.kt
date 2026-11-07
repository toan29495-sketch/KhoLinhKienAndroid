package vn.kholinhkien

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

object SupabaseClientProvider {
    val client = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    ) {
        install(Auth) {
            // Manage session reload explicitly to avoid SDK/Activity ON_START races
            // when returning from camera, picker, or Android multitasking.
            autoLoadFromStorage = true
            autoSaveToStorage = true
            enableLifecycleCallbacks = false
        }
        install(Postgrest)
        install(Storage)
        install(Functions)
    }
}
