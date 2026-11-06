package vn.kholinhkien

import io.github.jan.supabase.auth.auth

/** Creates the restore photo coordinator using the app's authenticated Supabase client. */
internal object RestorePhotoUploadFactory {
    fun create(): RestorePhotoUploadCoordinator {
        val storage = SupabaseRestorePhotoStorage(SupabaseClientProvider.client)
        return RestorePhotoUploadCoordinator(
            uploadObject = { path, bytes, contentType ->
                // The coordinator performs the independent verification immediately
                // after upload through the callback below.
                storage.uploadOnly(path, bytes, contentType)
            },
            verifyObject = { path, expectedSha256, expectedBytes ->
                storage.verify(path, expectedSha256, expectedBytes)
            },
            requireActiveOwner = { ownerId ->
                require(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) {
                    "Tài khoản đã thay đổi hoặc phiên đăng nhập hết hạn; đã dừng tải ảnh khôi phục"
                }
            }
        )
    }
}
