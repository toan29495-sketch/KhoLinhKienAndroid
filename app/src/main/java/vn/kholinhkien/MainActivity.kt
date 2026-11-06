package vn.kholinhkien

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.auth.auth
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.CancellationException
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


private const val ADMIN_HISTORY_PAGE_SIZE = 200

private fun buildConflictFieldDiff(localJson: String, serverJson: String): String = runCatching {
    val local = JSONObject(localJson)
    val server = JSONObject(serverJson)
    val fields = listOf("code", "name", "quantity", "min_quantity", "unit", "photo_path", "note", "tracking_mode", "server_version")
    fields.mapNotNull { key ->
        val left = if (local.has(key)) local.optString(key) else "(không có)"
        val right = if (server.has(key)) server.optString(key) else "(không có)"
        if (left != right) "$key: điện thoại=$left | máy chủ=$right" else null
    }.joinToString("\n")
}.getOrDefault("Không thể so sánh payload này theo cấu trúc linh kiện đã hỗ trợ.")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { InventoryApp() } }
    }
}

@Composable
private fun DatabaseStartupFailureScreen(
    error: String?,
    onRetry: () -> Unit,
    onSignOut: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text("Không mở được dữ liệu kho", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            error ?: "Đang kiểm tra và mở cơ sở dữ liệu trên điện thoại…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(12.dp))
        Text("Phiên đăng nhập vẫn được giữ. Ứng dụng không tự xóa dữ liệu, ảnh hoặc lịch sử để thử sửa lỗi.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry, enabled = error != null, modifier = Modifier.fillMaxWidth()) { Text("Thử mở lại dữ liệu") }
        TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Đăng xuất an toàn") }
    }
}

@Composable
fun InventoryApp() {
    val authRepository = remember { AuthRepository() }
    val authScope = rememberCoroutineScope()
    var currentProfile by remember { mutableStateOf<UserProfile?>(null) }
    var checkingSession by remember { mutableStateOf(true) }
    var sessionRestoreError by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val context = LocalContext.current

    // Keep a valid Supabase session on transient failures. Until the server
    // confirms the profile/role, fail closed and do not expose cached Admin UI.
    val restoreSession: suspend () -> Unit = {
        checkingSession = true
        sessionRestoreError = ""
        if (!authRepository.isLoggedIn()) {
            currentProfile = null
        } else {
            // Capture the session owner before profile validation: an inactive
            // profile check signs out inside AuthRepository, so the ID would
            // otherwise be unavailable when its scheduled work must be cancelled.
            val restoringOwnerId = authRepository.currentUserId().orEmpty()
            try {
                if (SessionExpiryPolicy.isExpired(context, restoringOwnerId)) {
                    if (isCanonicalOwnerId(restoringOwnerId)) {
                        SyncWorkScheduler.cancelOwner(context, restoringOwnerId)
                        RestoreRecoveryScheduler.cancelOwner(context, restoringOwnerId)
                        WeeklyBackupWorkScheduler.cancel(context, restoringOwnerId)
                    }
                    authRepository.signOut()
                    check(!authRepository.isLoggedIn()) { "Không thể kết thúc phiên đã quá 30 ngày" }
                    SessionExpiryPolicy.clear(context, restoringOwnerId)
                    currentProfile = null
                    sessionRestoreError = "Phiên đăng nhập đã đủ 30 ngày. Vui lòng đăng nhập lại. Dữ liệu kho trên máy không bị xóa."
                } else {
                    val restored = withContext(Dispatchers.IO) { authRepository.getCurrentProfile() }
                    currentProfile = restored
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (inactive: InactiveProfileException) {
                if (isCanonicalOwnerId(restoringOwnerId)) {
                    SyncWorkScheduler.cancelOwner(context, restoringOwnerId)
                    RestoreRecoveryScheduler.cancelOwner(context, restoringOwnerId)
                    WeeklyBackupWorkScheduler.cancel(context, restoringOwnerId)
                }
                currentProfile = null
                sessionRestoreError = inactive.message ?: "Tài khoản đã bị khóa. Vui lòng liên hệ Admin."
            } catch (error: Exception) {
                // Network/server errors are not proof that the account is invalid.
                // Keep the auth token and offer retry; do not sign out automatically.
                currentProfile = null
                sessionRestoreError = "Chưa xác minh được tài khoản do lỗi kết nối: ${error.message ?: "không rõ nguyên nhân"}. Hãy kiểm tra mạng và thử lại."
            }
        }
        checkingSession = false
    }

    LaunchedEffect(Unit) { restoreSession() }

    val signOut: () -> Unit = {
        authScope.launch {
            val ownerId = currentProfile?.id.orEmpty()
            // Cancel both push and pull work before ending the shared Supabase session.
            // A request already accepted by the server remains safe through operationId idempotency.
            SyncWorkScheduler.cancelOwner(context, ownerId)
            RestoreRecoveryScheduler.cancelOwner(context, ownerId)
            WeeklyBackupWorkScheduler.cancel(context, ownerId)
            try {
                authRepository.signOut()
                check(!authRepository.isLoggedIn()) {
                    "Supabase chưa xác nhận kết thúc phiên đăng nhập"
                }
                SessionExpiryPolicy.clear(context, ownerId)
                currentProfile = null
                message = "Đã đăng xuất an toàn. Giao dịch chưa xác định kết quả vẫn được giữ để đồng bộ lại khi đăng nhập đúng tài khoản."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!authRepository.isLoggedIn()) {
                        SessionExpiryPolicy.clear(context, ownerId)
                        currentProfile = null
                    message = "Đã kết thúc phiên đăng nhập; một số tác vụ nền đã bị hủy."
                } else {
                    // Do not pretend to sign out while the shared client still holds
                    // the old session; restore its scheduled sync and keep the UI signed in.
                    SyncWorkScheduler.schedule(context, ownerId, currentProfile?.role?.equals("admin", ignoreCase = true) == true)
                    message = "Chưa thể đăng xuất: ${error.message ?: "lỗi phiên"}. Phiên hiện tại vẫn được giữ để tránh đồng bộ nhầm tài khoản."
                }
            }
        }
    }

    if (checkingSession) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    if (currentProfile == null) {
        if (sessionRestoreError.isNotBlank() && authRepository.isLoggedIn()) {
            SessionRestoreScreen(
                message = sessionRestoreError,
                onRetry = { authScope.launch { restoreSession() } },
                onSignOut = {
                    authScope.launch {
                        try {
                            // currentProfile is null on this recovery screen; use the
                            // still-authenticated session ID so its owner work is cancelled.
                            val ownerId = authRepository.currentUserId().orEmpty()
                            if (isCanonicalOwnerId(ownerId)) {
                                SyncWorkScheduler.cancelOwner(context, ownerId)
                                RestoreRecoveryScheduler.cancelOwner(context, ownerId)
                                WeeklyBackupWorkScheduler.cancel(context, ownerId)
                            }
                            authRepository.signOut()
                            SessionExpiryPolicy.clear(context, ownerId)
                            currentProfile = null
                            sessionRestoreError = ""
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            sessionRestoreError = "Chưa thể đăng xuất: ${error.message ?: "lỗi phiên"}. Phiên vẫn được giữ an toàn."
                        }
                    }
                }
            )
        } else {
            LoginScreen(onLoginSuccess = { currentProfile = it; sessionRestoreError = "" })
        }
        return
    }

    val profile = currentProfile ?: return

    // Refresh the authoritative role/profile whenever the app returns to foreground.
    // A transient network failure keeps the current offline-first session and cached
    // role; an explicitly inactive profile ends the session and removes privileged UI.
    val lifecycleOwner = LocalLifecycleOwner.current
    var profileRefreshInFlight by remember(profile.id) { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, profile.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && !profileRefreshInFlight) {
                profileRefreshInFlight = true
                authScope.launch {
                    try {
                        if (SessionExpiryPolicy.isExpired(context, profile.id)) {
                            currentProfile = null
                            sessionRestoreError = "Phiên đăng nhập đã đủ 30 ngày. Vui lòng đăng nhập lại. Dữ liệu kho trên máy không bị xóa."
                            SyncWorkScheduler.cancelOwner(context, profile.id)
                            RestoreRecoveryScheduler.cancelOwner(context, profile.id)
                            WeeklyBackupWorkScheduler.cancel(context, profile.id)
                            authRepository.signOut()
                            check(!authRepository.isLoggedIn()) { "Không thể kết thúc phiên đã quá 30 ngày" }
                            SessionExpiryPolicy.clear(context, profile.id)
                            if (currentProfile?.id == profile.id) currentProfile = null
                            message = "Phiên đăng nhập đã đủ 30 ngày. Vui lòng đăng nhập lại. Dữ liệu kho trên máy không bị xóa."
                            return@launch
                        }
                        val refreshed = withContext(Dispatchers.IO) {
                            authRepository.getCurrentProfile()
                        }
                        if (refreshed.id == profile.id && currentProfile?.id == profile.id) {
                            currentProfile = refreshed
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (inactive: InactiveProfileException) {
                        // The repository has already signed out this inactive account.
                        if (currentProfile?.id == profile.id) currentProfile = null
                        message = inactive.message ?: "Tài khoản đã bị khóa"
                    } catch (error: Exception) {
                        // Do not sign out or discard local data for a temporary outage.
                        if (currentProfile?.id == profile.id) {
                            message = "Chưa thể làm mới quyền tài khoản (vẫn giữ dữ liệu ngoại tuyến): ${error.message ?: "lỗi kết nối"}"
                        }
                    } finally {
                        profileRefreshInFlight = false
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (profile.mustChangePassword) {
        ChangePasswordScreen(onPasswordChanged = { currentProfile = it }, onSignOut = signOut)
        return
    }

    // Open Room explicitly on Dispatchers.IO before composing any owner-scoped
    // Flow. A failed/unsupported migration must be shown as a recoverable screen,
    // not escape composition and send the user back to the launcher after login.
    var databaseRetry by remember(profile.id) { mutableIntStateOf(0) }
    var dao by remember(profile.id) { mutableStateOf<InventoryDao?>(null) }
    var databaseError by remember(profile.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(profile.id, databaseRetry) {
        dao = null
        databaseError = null
        try {
            dao = withContext(Dispatchers.IO) {
                val database = AppDb.get(context)
                database.openHelper.writableDatabase // run migrations and Room schema validation here
                database.dao()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            StartupFailureDiagnostics.record(context, "room-open", error)
            databaseError = "${error::class.java.simpleName}: ${error.message ?: "Không rõ lỗi cơ sở dữ liệu"}"
        }
    }
    if (dao == null) {
        DatabaseStartupFailureScreen(
            error = databaseError,
            onRetry = { databaseRetry++ },
            onSignOut = signOut
        )
        return
    }
    val inventoryDao = dao ?: return
    val scope = rememberCoroutineScope()
    val isAdmin = profile.role.equals("admin", ignoreCase = true)

    // WorkManager setup is isolated from Compose. A scheduler failure must not
    // crash the authenticated inventory screen or discard the valid session.
    LaunchedEffect(profile.id, isAdmin) {
        try {
            withContext(Dispatchers.IO) {
                SyncWorkScheduler.schedule(context, profile.id, isAdmin)
                WeeklyBackupWorkScheduler.schedule(context, profile.id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            StartupFailureDiagnostics.record(context, "work-scheduler", error)
            message = "Đã đăng nhập nhưng chưa khởi tạo được tác vụ đồng bộ/sao lưu: ${error.message ?: "lỗi hệ thống"}. Dữ liệu trên máy vẫn được giữ."
        }
    }
    val dao = inventoryDao

    // Sau khi hồ sơ đã xác thực được nạp, phục hồi journal backup của đúng owner.
    // Recovery chỉ kiểm tra/dọn ZIP cục bộ; không gọi Supabase và không đụng owner khác.
    LaunchedEffect(profile.id) {
        try {
            withContext(Dispatchers.IO) {
                WeeklyBackupRecovery.recoverOwner(context, dao, profile.id)
            }
        } catch (error: Exception) {
            message = "Chưa thể phục hồi trạng thái sao lưu tự động: ${error.message ?: "lỗi cục bộ"}"
        }
    }

    // Chỉ Admin được nhận dữ liệu tồn cũ chưa có ownerId; KTV đăng nhập trước
    // không thể vô tình chiếm kho cũ của chủ ứng dụng.
    LaunchedEffect(profile.id, isAdmin) {
        try {
            withContext(Dispatchers.IO) {
                if (isAdmin) {
                    dao.claimLegacyParts(profile.id)
                    dao.claimLegacyMovements(profile.id)
                } else {
                    // A device that loses Admin rights must not retain another KTV's
                    // global-history rows in its local Room cache. Keep this account's
                    // own movements and all server history; reset only the Admin cursor
                    // so a future promotion can safely download the full global feed.
                    dao.clearForeignMovementCache(profile.id)
                    dao.clearSyncCursor("admin-global:${profile.id}")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            StartupFailureDiagnostics.record(context, "owner-cache-isolation", error)
            message = "Chưa hoàn tất khởi tạo dữ liệu riêng của tài khoản: ${error.message ?: "lỗi cơ sở dữ liệu"}. Dữ liệu hiện có chưa bị xóa."
        }
    }
    val parts by dao.partsForOwner(profile.id).collectAsState(initial = emptyList())
    val blockedOperations by dao.syncAttentionOutbox(profile.id).collectAsState(initial = emptyList())
    var selectedBlockedOperation by remember(profile.id) { mutableStateOf<SyncOutbox?>(null) }
    var conflictServerSnapshot by remember(profile.id) { mutableStateOf("") }
    var readingConflictSnapshot by remember(profile.id) { mutableStateOf(false) }
    var queuedBehindCount by remember(profile.id) { mutableStateOf<Int?>(null) }
    var conflictDecision by remember(profile.id) { mutableStateOf<SyncConflictDecision?>(null) }
    var finalServerVersion by remember(profile.id) { mutableStateOf<Long?>(null) }
    var confirmKeepLocal by remember(profile.id) { mutableStateOf(false) }
    var confirmKeepServer by remember(profile.id) { mutableStateOf(false) }
    var pendingDecisionChoice by remember(profile.id) { mutableStateOf<String?>(null) }
    var confirmDecisionChoice by remember(profile.id) { mutableStateOf(false) }
    val ownMovements by dao.movementsForOwner(profile.id).collectAsState(initial = emptyList())
    val movementMaxId by dao.observeMovementMaxId().collectAsState(initial = null)
    // Admin history is fetched in bounded keyset pages; KTV history stays owner-scoped.
    val adminHistoryRows = remember(profile.id) { mutableStateListOf<Movement>() }
    var adminHistoryLoading by remember(profile.id) { mutableStateOf(false) }
    var adminHistoryHasMore by remember(profile.id) { mutableStateOf(false) }
    var adminHistoryError by remember(profile.id) { mutableStateOf("") }
    var adminHistoryRefresh by remember(profile.id) { mutableStateOf(0) }
    var adminHistoryWatermark by remember(profile.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(profile.id, isAdmin, adminHistoryRefresh) {
        if (isAdmin) {
            adminHistoryLoading = true
            try {
                // Read the watermark BEFORE the page. Any movement inserted
                // after this snapshot remains visible as a freshness warning,
                // including delayed events whose event time sorts far below page 1.
                val pageSnapshot = withContext(Dispatchers.IO) {
                    val watermark = dao.currentMovementMaxId()
                    val rows = dao.latestAdminMovementPage(ADMIN_HISTORY_PAGE_SIZE)
                    watermark to rows
                }
                adminHistoryWatermark = pageSnapshot.first
                adminHistoryRows.clear()
                adminHistoryRows.addAll(pageSnapshot.second)
                adminHistoryHasMore = pageSnapshot.second.size == ADMIN_HISTORY_PAGE_SIZE
                adminHistoryError = ""
            } catch (error: Exception) {
                if (error !is CancellationException) {
                    adminHistoryError = error.message ?: "Không tải được lịch sử toàn hệ thống"
                } else throw error
            } finally {
                adminHistoryLoading = false
            }
        } else {
            adminHistoryRows.clear()
            adminHistoryHasMore = false
            adminHistoryWatermark = null
            adminHistoryError = ""
        }
    }
    val movements = if (isAdmin) adminHistoryRows.toList() else ownMovements
    val loadMoreAdminHistory: () -> Unit = {
        if (isAdmin && !adminHistoryLoading && adminHistoryHasMore) {
            scope.launch {
                val oldest = adminHistoryRows.lastOrNull()
                if (oldest != null) {
                    adminHistoryLoading = true
                    try {
                        val nextPage = withContext(Dispatchers.IO) {
                            dao.olderAdminMovementPage(oldest.time, oldest.id, ADMIN_HISTORY_PAGE_SIZE)
                        }
                        val knownIds = adminHistoryRows.mapTo(HashSet()) { it.syncId }
                        nextPage.forEach { row -> if (knownIds.add(row.syncId)) adminHistoryRows.add(row) }
                        adminHistoryHasMore = nextPage.size == ADMIN_HISTORY_PAGE_SIZE
                        adminHistoryError = ""
                    } catch (error: Exception) {
                        if (error !is CancellationException) {
                            adminHistoryError = error.message ?: "Không tải thêm được lịch sử"
                        } else throw error
                    } finally {
                        adminHistoryLoading = false
                    }
                } else {
                    adminHistoryHasMore = false
                }
            }
        }
    }
    // Chỉ dùng giao dịch của chính chủ kho để đánh dấu QR đã xuất.
    val usedCodes = remember(ownMovements) {
        ownMovements.asSequence().filter { it.type == "XUẤT" }.map { it.code }.toSet()
    }
    var tab by remember(profile.id) { mutableStateOf(0) }
    // If an account loses Admin rights while the Admin tab is selected, move
    // immediately to the KTV inventory screen instead of leaving an empty tab.
    LaunchedEffect(profile.id, isAdmin) {
        if (!isAdmin && tab == 3) tab = 0
    }
    var query by remember(profile.id) { mutableStateOf("") }
    var selected by remember(profile.id) { mutableStateOf<Part?>(null) }
    var showAdd by remember(profile.id) { mutableStateOf(false) }
    var prefillCode by remember(profile.id) { mutableStateOf("") }
    var showEdit by remember(profile.id) { mutableStateOf(false) }
    var scan by remember(profile.id) { mutableStateOf(false) }
    var machineHistoryCode by remember(profile.id) { mutableStateOf<String?>(null) }
    // Raw QR codes are shared by legacy part labels and device labels. An unknown
    // code must not be silently classified as a device or silently create a part.
    var pendingUnknownQrCode by remember(profile.id) { mutableStateOf<String?>(null) }
    var machineHistoryMovements by remember(profile.id) { mutableStateOf<List<Movement>>(emptyList()) }
    LaunchedEffect(machineHistoryCode, profile.id, isAdmin) {
        val code = machineHistoryCode
        machineHistoryMovements = if (code == null) emptyList() else try {
            withContext(Dispatchers.IO) {
                dao.machineHistoryMovements(if (isAdmin) null else profile.id, code)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
    var pendingPhotoPath by remember(profile.id) { mutableStateOf("") }
    // Camera preview trả Bitmap trực tiếp; không tạo ảnh trong thư viện MediaStore.
    val cameraCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            try {
                val dir = ownerPendingPhotoDirectory(context, profile.id).apply { mkdirs() }
                val file = File(dir, "pending_${java.util.UUID.randomUUID()}.jpg")
                FileOutputStream(file).use { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) }
                pendingPhotoPath = file.absolutePath
                message = "Đã chụp ảnh, đang chờ đồng bộ cloud"
            } catch (e: Exception) { message = "Không lưu được ảnh chờ đồng bộ: ${e.message}" }
        }
    }
    val takePhoto: () -> Unit = { cameraCapture.launch(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try { pendingPhotoPath = copyImageToPrivateStorage(context, uri, profile.id) }
            catch (e: Exception) { message = "Không lưu được ảnh: ${e.message}" }
        }
    }
    var restoreUri by remember(profile.id) { mutableStateOf<Uri?>(null) }
    var restorePreview by remember(profile.id) { mutableStateOf<BackupArchivePreview?>(null) }
    var restorePreviewBusy by remember(profile.id) { mutableStateOf(false) }
    var restorePreviewError by remember(profile.id) { mutableStateOf("") }
    val restoreJournals by remember(profile.id) {
        dao.observeRestoreJournals(profile.id)
    }.collectAsState(initial = emptyList())
    var restoreBusy by remember(profile.id) { mutableStateOf(false) }
    // A journal survives process death: keep inventory mutations locked until the
    // exact restore session reaches COMPLETE or ABORTED. Recheck Room at write time
    // as well, so a stale Compose state cannot authorize a mutation.
    val restoreLocked = restoreBusy || restoreJournals.any { it.status !in listOf("COMPLETE", "ABORTED") }
    suspend fun requireNoActiveRestore() {
        check(dao.activeRestore(profile.id) == null) {
            "Đang có phiên khôi phục chưa hoàn tất. Hãy tiếp tục phiên đó trước khi sửa kho."
        }
    }
    LaunchedEffect(profile.id) {
        try {
            withContext(Dispatchers.IO) {
                RestoreRecoveryScheduler.schedulePending(context, dao, profile.id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            message = "Chưa thể xếp hàng tiếp tục phiên khôi phục: ${error.message ?: "lỗi WorkManager"}"
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            restoreUri = uri
            restorePreview = null
            restorePreviewError = ""
            restorePreviewBusy = true
            scope.launch {
                try {
                    restorePreview = withContext(Dispatchers.IO) {
                        BackupArchiveInspector.inspect(context, uri, profile.id)
                    }
                } catch (e: Exception) {
                    restorePreviewError = e.message ?: "Không đọc được thông tin bản sao lưu"
                } finally {
                    restorePreviewBusy = false
                }
            }
        }
    }
    var batchCodes by remember(profile.id) { mutableStateOf("") }
    var showBatch by remember(profile.id) { mutableStateOf(false) }
    val filtered = parts.filter { !(it.trackingMode == "SINGLE" && it.quantity <= 0 && usedCodes.contains(it.code)) && (it.name.contains(query, true) || it.code.contains(query, true) || it.note.contains(query, true)) }
    Scaffold(
        topBar = {
            TopAppBarCompat(title = when(tab) {
                0 -> "Kho linh kiện"
                1 -> if (isAdmin) "Lịch sử toàn hệ thống" else "Lịch sử giao dịch"
                2 -> "Công cụ & sao lưu"
                else -> "Quản trị KTV"
            }, onScan = { if (tab != 3 && !restoreLocked) scan = true },
                onAdd = { if (tab == 0 && !restoreLocked) showAdd = true },
                onSignOut = signOut, showInventoryActions = tab != 3)
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Inventory2, null) }, label = { Text("Tồn kho") })
                NavigationBarItem(selected = tab == 1, onClick = { if (isAdmin && tab != 1) adminHistoryRefresh++; tab = 1 }, icon = { Icon(Icons.Default.History, null) }, label = { Text("Lịch sử") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Build, null) }, label = { Text("Công cụ") })
                if (isAdmin) NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(Icons.Default.AdminPanelSettings, null) }, label = { Text("Quản trị") })
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
                    if (blockedOperations.isNotEmpty()) {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Đồng bộ đang tạm dừng: ${blockedOperations.size} thao tác cần đối soát", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text("Dữ liệu gốc được giữ lại. Ứng dụng không tự xóa, ghi đè hoặc gửi lại các thao tác này.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                blockedOperations.take(3).forEach { blocked ->
                                    TextButton(onClick = { selectedBlockedOperation = blocked; conflictServerSnapshot = ""; queuedBehindCount = null; conflictDecision = null; finalServerVersion = null; pendingDecisionChoice = null; confirmDecisionChoice = false }) {
                                        Text("Chi tiết ${blocked.operationType} · ${blocked.entityId.take(8)}")
                                    }
                                }
                            }
                        }
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
                1 -> {
                    if (isAdmin) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Đã tải ${movements.size} giao dịch", style = MaterialTheme.typography.bodySmall)
                                if (!adminHistoryLoading && movementMaxId != adminHistoryWatermark) {
                                    Text("Lịch sử đã thay đổi. Hãy làm mới để cập nhật.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            TextButton(onClick = { if (!adminHistoryLoading) adminHistoryRefresh++ }, enabled = !adminHistoryLoading) {
                                Text("Làm mới")
                            }
                        }
                        if (adminHistoryError.isNotBlank()) {
                            Text(adminHistoryError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (movements.isEmpty() && adminHistoryLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else if (movements.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Chưa có giao dịch") }
                    } else LazyColumn {
                        items(movements, key = { it.id }) { m ->
                            ListItem(headlineContent = { Text("${m.type} ${m.amount} · ${m.partName}") },
                                supportingContent = { Text("${m.ownerName.ifBlank { m.ownerId.take(8) }} · ${m.code} | ${m.beforeQty} → ${m.afterQty} | ${m.reason} ${m.machineOrStore}") },
                                trailingContent = { Text(SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(m.time))) })
                            HorizontalDivider()
                        }
                        if (isAdmin && adminHistoryHasMore) {
                            item(key = "admin-history-load-more") {
                                TextButton(onClick = loadMoreAdminHistory, enabled = !adminHistoryLoading, modifier = Modifier.fillMaxWidth()) {
                                    if (adminHistoryLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                    else Text("Tải thêm $ADMIN_HISTORY_PAGE_SIZE giao dịch")
                                }
                            }
                        } else if (isAdmin && !adminHistoryLoading) {
                            item(key = "admin-history-end") {
                                Text("Đã tải hết lịch sử", modifier = Modifier.fillMaxWidth().padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                2 -> ToolsScreen(
                    parts, ownMovements, profile.id, profile.username,
                    restoreJournals = restoreJournals,
                    restoreBusy = restoreBusy,
                    restoreLocked = restoreLocked,
                    onBatch = { if (!restoreLocked) showBatch = true },
                    onRestore = { restoreLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                    onRestoreInbox = { archive ->
                        if (!restoreBusy) scope.launch {
                            try {
                                require(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == profile.id) { "Tài khoản đã thay đổi; dừng khôi phục" }
                                require(dao.activeRestore(profile.id) == null) { "Đang có phiên khôi phục chưa hoàn tất. Hãy tiếp tục phiên đó trước." }
                                restoreBusy = true
                                try {
                                    val result = RestoreCloudCoordinator(context, dao).importAndRestore(archive, profile.id)
                                    message = "Khôi phục backup Cloud hoàn tất: ${result.partCount} linh kiện, ${result.movementCount} giao dịch. ${if (result.localCleanupComplete) "Đã dọn dữ liệu tạm." else "Cần thử dọn dữ liệu tạm lại."}"
                                } finally { restoreBusy = false }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { message = "Khôi phục backup Cloud thất bại: ${error.message}" }
                        }
                    },
                    onResumeRestore = { restoreId ->
                        val journal = restoreJournals.firstOrNull { it.restoreId == restoreId && it.ownerId == profile.id }
                        if (journal != null && !restoreBusy) {
                            if (journal.sourceType != "REMOTE_FEED" && journal.status in setOf("STAGING", "FAILED")) {
                                // A process death during ZIP extraction cannot resume the
                                // original stream. Ask for the same archive again; ZipRestore
                                // verifies its manifest-derived restoreId before rebuilding
                                // the durable staging workspace. Live inventory stays untouched.
                                message = "Hãy chọn lại đúng tệp ZIP đã dùng trước đó. Ứng dụng sẽ đối chiếu mã SHA-256 và dựng lại staging; kho hiện tại không bị thay đổi."
                                restoreLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                            } else scope.launch {
                                restoreBusy = true
                                try {
                                    val result = RestoreCloudCoordinator(context, dao).resume(profile.id, journal.restoreId)
                                    message = if (journal.status == "COMPLETE") {
                                        if (result.localCleanupComplete) "Đã kiểm tra và dọn dữ liệu tạm của phiên khôi phục." else "Kho đã khôi phục từ trước; vẫn còn dữ liệu tạm chưa dọn hết. Có thể thử lại."
                                    } else {
                                        "Khôi phục hoàn tất: ${result.partCount} linh kiện, ${result.movementCount} giao dịch. ${if (result.localCleanupComplete) "Đã dọn dữ liệu tạm." else "Cần thử dọn dữ liệu tạm lại."}"
                                    }
                                } catch (e: Exception) {
                                    message = "Chưa thể tiếp tục khôi phục (${journal.status}): ${e.message}"
                                } finally {
                                    restoreBusy = false
                                }
                            }
                        }
                    },
                    onMessage = { message = it }
                )
                3 -> if (isAdmin) AdminScreen(onMessage = { message = it }, onSignOut = signOut)
            }
        }
    }
    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null; restorePreview = null; restorePreviewError = "" },
            title = { Text("Kiểm tra bản sao lưu") },
            text = {
                when {
                    restorePreviewBusy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp)); Text("Đang đọc thông tin bản sao lưu…")
                    }
                    restorePreviewError.isNotBlank() -> Text(restorePreviewError, color = MaterialTheme.colorScheme.error)
                    restorePreview != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val preview = restorePreview!!
                        Text("Tệp: ${preview.fileName}")
                        Text("Tài khoản tạo: ${preview.username}")
                        if (preview.createdAt > 0L) {
                            Text("Thời điểm: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(preview.createdAt))}")
                        } else {
                            Text("Bản sao lưu cũ: chưa có thông tin thời điểm trong manifest")
                        }
                        Text("${preview.partCount} linh kiện · ${preview.movementCount} giao dịch")
                        Text("Đúng chủ kho đang đăng nhập. Khôi phục sẽ thay thế kho và lịch sử của tài khoản này; KTV khác không bị ảnh hưởng.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Ứng dụng vẫn sẽ kiểm tra toàn bộ ZIP và SHA-256 trước khi cập nhật kho.", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Text("Chưa có thông tin để xác nhận bản sao lưu.")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !restorePreviewBusy && restorePreview != null && restorePreviewError.isBlank(),
                    onClick = {
                        val selectedUri = restoreUri
                        restoreUri = null
                        restorePreview = null
                        restorePreviewError = ""
                        if (selectedUri != null) scope.launch {
                            try {
                                val activeRestore = dao.activeRestore(profile.id)
                                require(activeRestore == null ||
                                    (activeRestore.sourceType != "REMOTE_FEED" && activeRestore.status in setOf("STAGING", "FAILED"))) {
                                    "Tài khoản đang có phiên khôi phục chưa hoàn tất. Hãy tiếp tục đúng phiên đó trước."
                                }
                                // For STAGING/FAILED, ZipRestore additionally compares the
                                // manifest-derived restoreId. A different archive is rejected.
                                restoreBusy = true
                                try {
                                    val result = RestoreCloudCoordinator(context, dao).importAndRestore(selectedUri, profile.id)
                                    message = "Khôi phục hoàn tất: ${result.partCount} linh kiện, ${result.movementCount} giao dịch. ${if (result.localCleanupComplete) "Đã dọn dữ liệu tạm." else "Cần thử dọn dữ liệu tạm lại."}"
                                } finally {
                                    restoreBusy = false
                                }
                            } catch (e: Exception) {
                                message = "Khôi phục thất bại: ${e.message}"
                            }
                        }
                    }
                ) { Text("Khôi phục và thay thế") }
            },
            dismissButton = { TextButton(onClick = { restoreUri = null; restorePreview = null; restorePreviewError = "" }) { Text("Hủy") } }
        )
    }
    if (showAdd) AddPartDialog(initialCode = prefillCode, photoPath = pendingPhotoPath, onChoosePhoto = { photoPicker.launch("image/*") }, onTakePhoto = takePhoto, onDismiss = { showAdd = false; pendingPhotoPath = ""; prefillCode = "" }, onSave = { part ->
        scope.launch {
            try {
                requireNoActiveRestore()
                dao.insertPartWithOpeningMovement(
                    part.copy(photoPath = pendingPhotoPath, ownerId = profile.id),
                    profile.displayName.ifBlank { profile.username }
                )
                SyncWorkScheduler.schedule(context, profile.id, isAdmin)
                message = "Đã thêm ${part.code}; sẽ đồng bộ khi có mạng"
            } catch (_: Exception) { message = "Mã đã tồn tại hoặc dữ liệu không hợp lệ" }
            showAdd = false
            pendingPhotoPath = ""
            prefillCode = ""
        }
    })
    selected?.let { p ->
        PartDialog(p, profile.id, onDismiss = { selected = null }, onEdit = { pendingPhotoPath = p.photoPath; showEdit = true }, onDelete = {
            scope.launch {
                try {
                    requireNoActiveRestore()
                    dao.deletePartByCode(profile.id, p.code)
                    selected = null
                    SyncWorkScheduler.schedule(context, profile.id, isAdmin)
                    message = "Đã xóa mã QR khỏi danh mục. Lịch sử giao dịch vẫn được giữ."
                } catch (e: Exception) { message = "Không xóa được mã: ${e.message}" }
            }
        }, onMove = { type, amount, reason, machine ->
            scope.launch {
                try {
                    requireNoActiveRestore()
                    dao.changeStock(profile.id, profile.displayName.ifBlank { profile.username }, p.code, type, amount, reason, machine)
                    SyncWorkScheduler.schedule(context, profile.id, isAdmin)
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
                try {
                    requireNoActiveRestore()
                    dao.update(profile.id, updated.copy(photoPath = pendingPhotoPath, updatedAt = System.currentTimeMillis()))
                    SyncWorkScheduler.schedule(context, profile.id, isAdmin)
                    selected = updated.copy(photoPath = pendingPhotoPath)
                    message = "Đã cập nhật linh kiện"
                }
                catch (e: Exception) { message = "Cập nhật thất bại: ${e.message}" }
                showEdit = false
            }
        })
    if (scan) ScanDialog(onDismiss = { scan = false }, onCode = { code ->
        scan = false
        scope.launch {
            val scanned = code.trim()
            val part = dao.byCode(profile.id, scanned)
            if (part != null) {
                selected = part
            } else {
                // Legacy QR payloads are plain codes, so an unknown code alone
                // cannot distinguish a new part from a device. Ask explicitly.
                pendingUnknownQrCode = scanned
            }
        }
    })
    pendingUnknownQrCode?.let { scannedCode ->
        AlertDialog(
            onDismissRequest = { pendingUnknownQrCode = null },
            title = { Text("Chưa tìm thấy mã trong kho") },
            text = {
                Text("Mã QR: $scannedCode\n\nBạn muốn thêm mã này thành linh kiện hay tra cứu lịch sử của thiết bị? Nếu linh kiện đã có trên máy chủ nhưng chưa tải về thiết bị, hãy đồng bộ kho trước khi tạo mới.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingUnknownQrCode = null
                    prefillCode = scannedCode
                    showAdd = true
                }) { Text("Thêm linh kiện") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingUnknownQrCode = null
                        machineHistoryCode = scannedCode
                    }) { Text("Lịch sử thiết bị") }
                    TextButton(onClick = { pendingUnknownQrCode = null }) { Text("Hủy") }
                }
            }
        )
    }
    machineHistoryCode?.let { machineCode ->
        MachineHistoryDialog(
            machineCode = machineCode,
            movements = machineHistoryMovements,
            showOwner = isAdmin,
            onDismiss = { machineHistoryCode = null }
        )
    }
    if (showBatch) BatchCodeDialog(initial = batchCodes, onDismiss = { showBatch = false }, onImport = { raw ->
        batchCodes = raw
        val supplied = raw.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val codes = supplied.filter { it.length == 10 && it.all(Char::isLetterOrDigit) }
        scope.launch {
            var added = 0
            if (dao.activeRestore(profile.id) != null) {
                message = "Đang có phiên khôi phục chưa hoàn tất. Hãy tiếp tục phiên đó trước."
                showBatch = false
                return@launch
            }
            for (code in codes) {
                if (dao.byCode(profile.id, code) == null) {
                    try {
                        requireNoActiveRestore()
                        dao.insertPartWithOpeningMovement(Part(code = code, name = "Linh kiện $code", ownerId = profile.id), profile.displayName.ifBlank { profile.username })
                        saveBitmap(context, qrBitmap(code, 500), "QR_${code}.png")
                        added++
                    } catch (_: Exception) {}
                }
            }
            if (added > 0) SyncWorkScheduler.schedule(context, profile.id, isAdmin)
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
    selectedBlockedOperation?.let { blocked ->
        AlertDialog(
            onDismissRequest = { selectedBlockedOperation = null },
            title = { Text("Thao tác đồng bộ cần đối soát") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Loại: ${blocked.operationType}")
                    Text("Mã giao dịch: ${blocked.operationId}")
                    Text("Mã linh kiện: ${blocked.entityId}")
                    Text("Số lần gửi: ${blocked.attemptCount}")
                    Text("Lỗi máy chủ: ${blocked.lastError}")
                    Text("Payload cục bộ đã đóng băng:")
                    Text(blocked.payloadJson, style = MaterialTheme.typography.bodySmall, maxLines = 8)
                    Text("Payload gốc vẫn được giữ nguyên. Hàng đợi tài khoản tiếp tục bị khóa để tránh thao tác phía sau vượt lên.")
                    if (blocked.status == "IN_FLIGHT") {
                        Text("Giao dịch IN_FLIGHT cũ không có thời điểm claim. Ứng dụng không tự reset hoặc lấy quyền gửi lại; cần đối soát thủ công.")
                    }
                    val resolutionPlan = remember(blocked.lastError, conflictServerSnapshot, queuedBehindCount) {
                        SyncConflictResolutionPlanner.plan(blocked.lastError, conflictServerSnapshot.startsWith("{"), queuedBehindCount ?: -1)
                    }
                    Text("Đánh giá: ${resolutionPlan.explanation}")
                    resolutionPlan.nextSteps.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    if (blocked.operationType in listOf("PART_UPSERT", "PART_DELETE", "STOCK_CHANGE")) {
                        OutlinedButton(
                            enabled = !readingConflictSnapshot,
                            onClick = {
                                readingConflictSnapshot = true
                                scope.launch {
                                    conflictServerSnapshot = try {
                                        val snapshot = withContext(Dispatchers.IO) {
                                            SyncConflictSnapshotReader().read(profile.id, blocked.entityId)
                                        }
                                        snapshot ?: "Không tìm thấy linh kiện trong kho hiện tại hoặc tài khoản không có quyền xem."
                                    } catch (e: Exception) {
                                        "Không đọc được snapshot máy chủ: ${e.message ?: "Lỗi không xác định"}"
                                    }
                                    readingConflictSnapshot = false
                                }
                            }
                        ) { Text(if (readingConflictSnapshot) "Đang đọc…" else "Đọc bản máy chủ") }
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            queuedBehindCount = withContext(Dispatchers.IO) {
                                dao.countQueuedBehindBlocked(profile.id, blocked.operationId)
                            }
                        }
                    }) { Text("Kiểm tra thao tác phía sau") }
                    queuedBehindCount?.let { Text("Có $it thao tác đang nằm phía sau giao dịch bị chặn.") }
                    Text("Lưu quyết định (chưa thay đổi kho):")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val allowedDecisions = SyncConflictResolutionPlanner.allowedDecisions(blocked.operationType)
                        TextButton(enabled = blocked.status == "BLOCKED" && "KEEP_LOCAL" in allowedDecisions, onClick = { pendingDecisionChoice = "KEEP_LOCAL"; confirmDecisionChoice = true }) { Text("Ưu tiên điện thoại") }
                        TextButton(enabled = blocked.status == "BLOCKED" && "KEEP_SERVER" in allowedDecisions, onClick = { pendingDecisionChoice = "KEEP_SERVER"; confirmDecisionChoice = true }) { Text("Ưu tiên máy chủ") }
                        TextButton(enabled = blocked.status == "BLOCKED" && "MANUAL_REVIEW" in allowedDecisions, onClick = { pendingDecisionChoice = "MANUAL_REVIEW"; confirmDecisionChoice = true }) { Text("Thủ công") }
                        if (blocked.operationType == "PART_DELETE" || blocked.operationType == "STOCK_CHANGE") {
                            Text("Với thao tác xóa hoặc nhập/xuất kho, ứng dụng chỉ cho phép ghi nhận cần xử lý thủ công. Không tự hồi sinh linh kiện, tạo lại giao dịch hoặc bỏ lịch sử.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    conflictDecision?.let { decision ->
                        Text("Quyết định: ${decision.decisionKind} · ${decision.status}")
                        if (decision.status == "PREPARED" && decision.decisionKind != "MANUAL_REVIEW") {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    try {
                                        val ready = withContext(Dispatchers.IO) { dao.validatePreparedConflictDecision(profile.id, blocked.operationId) }
                                        conflictDecision = ready
                                        val preflight = withContext(Dispatchers.IO) { dao.inspectBlockedResolution(profile.id, blocked.operationId) }
                                        queuedBehindCount = preflight?.dependentOperations?.size ?: -1
                                        val snapshot = withContext(Dispatchers.IO) {
                                            SyncConflictSnapshotReader().read(profile.id, blocked.entityId)
                                        } ?: error("Không tìm thấy linh kiện trên máy chủ")
                                        val version = JSONObject(snapshot).optLong("server_version", 0L)
                                        require(version > 0L) { "Máy chủ không trả về phiên bản hợp lệ" }
                                        conflictServerSnapshot = snapshot
                                        finalServerVersion = version
                                    } catch (e: Exception) {
                                        finalServerVersion = null
                                        message = "Chưa đủ điều kiện đối soát: ${e.message}"
                                    }
                                }
                            }) { Text("Kiểm tra máy chủ lần cuối") }
                        }
                        if (decision.decisionKind == "KEEP_LOCAL" && decision.status == "READY_FOR_SERVER_RECHECK" && (finalServerVersion ?: 0L) > 0L && blocked.operationType == "PART_UPSERT" && queuedBehindCount == 0) {
                            Text("Máy chủ hiện ở phiên bản $finalServerVersion. Chỉ hỗ trợ thay thế PART_UPSERT không có thao tác phía sau.")
                            OutlinedButton(onClick = { confirmKeepLocal = true }) { Text("Thực hiện ưu tiên điện thoại") }
                        } else if (decision.decisionKind == "KEEP_SERVER" && decision.status == "READY_FOR_SERVER_RECHECK" && blocked.operationType == "PART_UPSERT" && queuedBehindCount == 0 && conflictServerSnapshot.startsWith("{")) {
                            Text("Chỉ áp dụng được khi PART_UPSERT độc lập, không có thao tác phía sau. Snapshot sẽ thay thế bản local của linh kiện này.")
                            OutlinedButton(onClick = { confirmKeepServer = true }) { Text("Áp dụng dữ liệu máy chủ") }
                        } else if (decision.decisionKind == "KEEP_SERVER") {
                            Text("Ưu tiên máy chủ chưa đủ điều kiện áp dụng. Giao dịch và hàng đợi vẫn được giữ nguyên.")
                        } else if (decision.decisionKind == "MANUAL_REVIEW") {
                            Text("Đã đánh dấu cần xử lý thủ công. Hàng đợi tiếp tục bị khóa.")
                        }
                    }
                    if (conflictServerSnapshot.isNotBlank()) {
                        Text("Snapshot máy chủ (chỉ đọc):")
                        Text(conflictServerSnapshot, style = MaterialTheme.typography.bodySmall)
                        val diff = remember(blocked.payloadJson, conflictServerSnapshot) {
                            buildConflictFieldDiff(blocked.payloadJson, conflictServerSnapshot)
                        }
                        if (diff.isNotBlank()) {
                            Text("Các trường khác nhau:")
                            Text(diff, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedBlockedOperation = null }) { Text("Đóng") } }
        )
    }
    if (confirmDecisionChoice && pendingDecisionChoice != null) {
        val choice = pendingDecisionChoice ?: "MANUAL_REVIEW"
        AlertDialog(
            onDismissRequest = { confirmDecisionChoice = false; pendingDecisionChoice = null },
            title = { Text("Xác nhận lưu quyết định?") },
            text = { Text("Lựa chọn $choice sẽ được lưu cố định cho giao dịch này. Việc lưu quyết định chưa thay đổi tồn kho, chưa xóa Outbox và chưa mở khóa hàng đợi. Các lựa chọn chưa có quy trình thực thi sẽ tiếp tục cần xử lý thủ công.") },
            dismissButton = { TextButton(onClick = { confirmDecisionChoice = false; pendingDecisionChoice = null }) { Text("Hủy") } },
            confirmButton = {
                TextButton(onClick = {
                    confirmDecisionChoice = false
                    val blocked = selectedBlockedOperation
                    if (blocked != null) scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { dao.prepareConflictDecision(profile.id, blocked.operationId, choice) }
                        }.onSuccess {
                            conflictDecision = it
                            message = "Đã lưu quyết định $choice ở trạng thái ${it.status}. Kho và hàng đợi chưa thay đổi."
                        }.onFailure { message = "Không lưu được quyết định: ${it.message}" }
                    }
                    pendingDecisionChoice = null
                }) { Text("Xác nhận lưu") }
            }
        )
    }
    if (confirmKeepLocal) {
        AlertDialog(
            onDismissRequest = { confirmKeepLocal = false },
            title = { Text("Xác nhận ưu tiên dữ liệu điện thoại?") },
            text = { Text("Ứng dụng sẽ tạo một giao dịch PART_UPSERT mới với mã mới và phiên bản máy chủ vừa đọc. Chỉ thao tác độc lập mới được phép; nếu điều kiện thay đổi, thao tác sẽ bị từ chối. Giao dịch cũ được giữ lại trong lịch sử trạng thái.") },
            dismissButton = { TextButton(onClick = { confirmKeepLocal = false }) { Text("Hủy") } },
            confirmButton = {
                TextButton(onClick = {
                    confirmKeepLocal = false
                    val blocked = selectedBlockedOperation
                    if (blocked != null) scope.launch {
                        try {
                            val replacement = withContext(Dispatchers.IO) {
                                SyncConflictKeepLocalResolver(dao).resolve(profile.id, blocked.operationId)
                            }
                            message = "Đã xếp giao dịch thay thế ${replacement.operationId.take(8)} vào hàng đợi. Máy chủ vẫn sẽ kiểm tra phiên bản bằng CAS khi nhận."
                            selectedBlockedOperation = null
                        } catch (e: Exception) {
                            message = "Không thể thực hiện; hàng đợi vẫn được bảo vệ: ${e.message}"
                        }
                    }
                }) { Text("Xác nhận") }
            }
        )
    }
    if (confirmKeepServer) {
        AlertDialog(
            onDismissRequest = { confirmKeepServer = false },
            title = { Text("Áp dụng dữ liệu máy chủ?") },
            text = { Text("Chỉ linh kiện này được thay bằng snapshot mới đọc từ máy chủ. Điều kiện: PART_UPSERT độc lập, không có giao dịch phía sau, cùng chủ sở hữu và chưa bị xóa. Giao dịch xung đột được giữ ở trạng thái đã xử lý; không xóa lịch sử nhập/xuất.") },
            dismissButton = { TextButton(onClick = { confirmKeepServer = false }) { Text("Hủy") } },
            confirmButton = {
                TextButton(onClick = {
                    confirmKeepServer = false
                    val blocked = selectedBlockedOperation
                    if (blocked != null) scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                SyncConflictKeepServerResolver(dao).resolve(profile.id, blocked.operationId)
                            }
                            message = "Đã áp dụng snapshot máy chủ cho linh kiện. Hàng đợi có thể tiếp tục đồng bộ."
                            selectedBlockedOperation = null
                        } catch (e: Exception) {
                            message = "Không thể áp dụng; dữ liệu và hàng đợi vẫn được bảo vệ: ${e.message}"
                        }
                    }
                }) { Text("Xác nhận áp dụng") }
            }
        )
    }
    if (message.isNotBlank()) AlertDialog(onDismissRequest = { message = "" }, confirmButton = { TextButton(onClick = { message = "" }) { Text("Đóng") } }, text = { Text(message) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBarCompat(
    title: String,
    onScan: () -> Unit,
    onAdd: () -> Unit,
    onSignOut: () -> Unit,
    showInventoryActions: Boolean = true
) {
    TopAppBar(title = { Text(title) }, actions = {
        if (showInventoryActions) {
            IconButton(onClick = onScan) { Icon(Icons.Default.QrCodeScanner, "Quét QR") }
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Thêm") }
        }
        IconButton(onClick = onSignOut) { Icon(Icons.Default.Logout, "Đăng xuất") }
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
private fun PartDialog(
    part: Part,
    ownerId: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: (type: String, amount: Int, reason: String, machine: String) -> Unit,
    onGenerateQr: () -> Unit
) {
    var movementType by remember(part.syncId) { mutableStateOf("XUẤT") }
    var amount by remember(part.syncId) { mutableStateOf("1") }
    var reason by remember(part.syncId) { mutableStateOf("") }
    var machine by remember(part.syncId) { mutableStateOf("") }
    val parsedAmount = amount.toIntOrNull()
    val validAmount = parsedAmount != null && parsedAmount >= 0 &&
        (movementType == "KIỂM KÊ" || parsedAmount > 0) &&
        (movementType != "XUẤT" || part.trackingMode != "SINGLE" || parsedAmount == 1)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(part.name) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PartPhotoPreview(part.photoPath, ownerId)
                Text("Mã QR: ${part.code}", style = MaterialTheme.typography.titleSmall)
                Text("Tồn kho: ${part.quantity} ${part.unit}")
                Text("Ngưỡng cảnh báo: ${part.minQuantity} ${part.unit}")
                if (part.note.isNotBlank()) Text("Ghi chú: ${part.note}")
                Text("Cập nhật tồn kho", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("NHẬP", "XUẤT", "KIỂM KÊ").forEach { type ->
                        FilterChip(
                            selected = movementType == type,
                            onClick = { movementType = type; amount = if (type == "XUẤT" && part.trackingMode == "SINGLE") "1" else amount },
                            label = { Text(type) }
                        )
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter(Char::isDigit) },
                    label = { Text(if (movementType == "KIỂM KÊ") "Số lượng thực tế" else "Số lượng") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(reason, { reason = it }, label = { Text("Lý do (không bắt buộc)") }, singleLine = true)
                OutlinedTextField(machine, { machine = it }, label = { Text("Máy / cửa hàng (không bắt buộc)") }, singleLine = true)
                if (movementType == "XUẤT" && part.trackingMode == "SINGLE") {
                    Text("Mã này quản lý từng món riêng: mỗi lần xuất chỉ được 1 món.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = validAmount, onClick = { onMove(movementType, parsedAmount!!, reason.trim(), machine.trim()) }) {
                Text("Lưu giao dịch")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onGenerateQr) { Text("QR") }
                TextButton(onClick = onEdit) { Text("Sửa") }
                TextButton(onClick = onDelete) { Text("Xóa") }
                TextButton(onClick = onDismiss) { Text("Đóng") }
            }
        }
    )
}

@Composable
private fun PartPhotoPreview(photoPath: String, ownerId: String) {
    val context = LocalContext.current
    var bitmap by remember(photoPath, ownerId) { mutableStateOf<Bitmap?>(null) }
    var status by remember(photoPath, ownerId) { mutableStateOf("Đang tải ảnh…") }
    LaunchedEffect(photoPath, ownerId) {
        bitmap = null
        if (photoPath.isBlank()) return@LaunchedEffect
        try {
            val decoded = withContext(Dispatchers.IO) {
                val imageBytes = if (File(photoPath).isAbsolute) {
                    val source = File(photoPath)
                    val canonicalSource = source.canonicalFile
                    val privateRoot = context.filesDir.canonicalFile
                    require(canonicalSource.path.startsWith(privateRoot.path + File.separator)) { "Ảnh cục bộ nằm ngoài vùng dữ liệu ứng dụng" }
                    val pendingRoot = File(privateRoot, "pending_part_photos").canonicalFile
                    if (canonicalSource.path.startsWith(pendingRoot.path + File.separator)) {
                        require(canonicalSource.parentFile == File(pendingRoot, ownerId).canonicalFile) { "Ảnh chờ đồng bộ không thuộc tài khoản đang đăng nhập" }
                    }
                    require(source.isFile) { "Ảnh cục bộ không còn tồn tại" }
                    readPhotoBytesBounded(source)
                } else {
                    require(!photoPath.startsWith('/') && !photoPath.contains('\\')) { "Đường dẫn ảnh không hợp lệ" }
                    val segments = photoPath.split('/')
                    require(segments.size >= 2 && segments.first() == ownerId && segments.none { it.isBlank() || it == "." || it == ".." }) { "Ảnh không thuộc tài khoản đang đăng nhập" }
                    SupabaseClientProvider.client.storage.from("part-images").downloadAuthenticated(photoPath)
                        .also { require(it.size in 1..MAX_PART_PHOTO_BYTES) { "Ảnh cloud rỗng hoặc vượt giới hạn 20 MB" } }
                }
                try {
                    require(imageBytes.size in 1..MAX_PART_PHOTO_BYTES) { "Ảnh rỗng hoặc vượt giới hạn 20 MB" }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Tệp ảnh không đọc được" }
                    var sample = 1
                    while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                } finally { imageBytes.fill(0) }
            }
            bitmap = decoded
            status = if (decoded == null) "Không giải mã được ảnh" else ""
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            status = "Không tải được ảnh: ${error.message ?: "lỗi không xác định"}"
        }
    }
    if (bitmap != null) {
        Image(bitmap!!.asImageBitmap(), contentDescription = "Ảnh linh kiện ${photoPath.substringAfterLast('/').take(40)}", modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp))
    } else if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
}

private const val MAX_PART_PHOTO_BYTES = 20 * 1024 * 1024

private fun readPhotoBytesBounded(file: File): ByteArray {
    require(file.length() in 1..MAX_PART_PHOTO_BYTES.toLong()) { "Ảnh cục bộ rỗng hoặc vượt giới hạn 20 MB" }
    return file.inputStream().use { input ->
        val output = java.io.ByteArrayOutputStream(minOf(file.length().toInt(), MAX_PART_PHOTO_BYTES))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_PART_PHOTO_BYTES) { "Ảnh cục bộ vượt giới hạn 20 MB" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
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

private fun restoreStatusTitle(status: String): String = when (status) {
    "STAGING" -> "Đang đọc và kiểm tra tệp ZIP · có thể cần chọn lại ZIP"
    "FAILED" -> "Lần staging ZIP trước bị gián đoạn hoặc gặp lỗi"
    "STAGED" -> "Đã kiểm tra ZIP · chờ chuẩn bị dữ liệu"
    "PHOTOS_UPLOADED" -> "Đã tải ảnh lên cloud"
    "PAYLOAD_READY" -> "Đã chuẩn bị dữ liệu gửi cloud"
    "CLOUD_STAGING" -> "Đang gửi dữ liệu lên cloud"
    "CLOUD_STAGED" -> "Cloud đã nhận đủ dữ liệu · chờ xác nhận"
    "COMMIT_UNKNOWN" -> "Chưa rõ kết quả xác nhận cloud · cần kiểm tra lại"
    "COMMIT_CONFIRMED" -> "Cloud đã xác nhận · đang chờ tải snapshot"
    "SNAPSHOT_DOWNLOADING" -> "Đang tải snapshot đã xác nhận"
    "SNAPSHOT_DOWNLOADED" -> "Đã tải snapshot · chờ cập nhật kho trên máy"
    "REMOTE_COMMIT_CONFIRMED" -> "Kho cloud được thay đổi từ thiết bị khác · đang đồng bộ snapshot"
    else -> "Phiên khôi phục: $status"
}

private fun restoreStatusDescription(status: String): String = when (status) {
    "STAGING", "FAILED" -> "ZIP chưa được staging hoàn chỉnh. Nếu ứng dụng bị đóng hoặc hết pin, hãy chọn lại đúng tệp ZIP cũ; ứng dụng đối chiếu mã SHA-256 trước khi dựng lại staging. Kho hiện tại chưa bị thay đổi."
    "STAGED", "PHOTOS_UPLOADED", "PAYLOAD_READY", "CLOUD_STAGING", "CLOUD_STAGED" -> "Có thể tiếp tục đúng phiên này. Không xóa dữ liệu ứng dụng hoặc tệp staging trong lúc khôi phục."
    "COMMIT_UNKNOWN" -> "Ứng dụng sẽ kiểm tra lại cùng mã phiên; không tạo phiên mới để tránh gửi/commit trùng."
    "COMMIT_CONFIRMED", "SNAPSHOT_DOWNLOADING", "SNAPSHOT_DOWNLOADED", "REMOTE_COMMIT_CONFIRMED" -> "Kho hiện tại chỉ được thay sau khi snapshot cloud được xác minh và Room transaction hoàn tất."
    else -> "Tiếp tục sẽ chạy lại giai đoạn phù hợp với trạng thái đã lưu."
}

@Composable
fun ToolsScreen(
    parts: List<Part>, movements: List<Movement>, ownerId: String, username: String,
    restoreJournals: List<RestoreJournal>, restoreBusy: Boolean, restoreLocked: Boolean,
    onBatch: () -> Unit, onRestore: () -> Unit,
    onResumeRestore: (String) -> Unit, onRestoreInbox: (File) -> Unit, onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) try {
            context.contentResolver.openOutputStream(uri)?.use { writeXlsx(it, parts, movements) }
            onMessage("Đã xuất tệp Excel XLSX gồm 2 trang: Tồn kho và Lịch sử.")
        } catch (e: Exception) { onMessage("Xuất Excel thất bại: ${e.message}") }
    }
    val scope = rememberCoroutineScope()
    var backupBusy by remember { mutableStateOf(false) }
    // Key every catalog state to the authenticated owner. A newly selected
    // account never inherits the previous account's list or error message.
    var cloudCatalogEntries by remember(ownerId) { mutableStateOf<List<WeeklyBackupCloudCatalog.Entry>>(emptyList()) }
    var cloudCatalogLoading by remember(ownerId) { mutableStateOf(false) }
    var cloudCatalogLoaded by remember(ownerId) { mutableStateOf(false) }
    var cloudCatalogError by remember(ownerId) { mutableStateOf("") }
    var cloudDownloadBusyId by remember(ownerId) { mutableStateOf<String?>(null) }
    var cloudDownloadError by remember(ownerId) { mutableStateOf("") }
    var cloudVerifiedFile by remember(ownerId) { mutableStateOf<WeeklyBackupCloudDownloader.VerifiedFile?>(null) }
    var inboxItems by remember(ownerId) { mutableStateOf<List<WeeklyBackupRestoreInbox.StoredItem>>(emptyList()) }
    var inboxLoading by remember(ownerId) { mutableStateOf(false) }
    var inboxError by remember(ownerId) { mutableStateOf("") }
    var selectedInboxItem by remember(ownerId) { mutableStateOf<WeeklyBackupRestoreInbox.StoredItem?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Công cụ", style = MaterialTheme.typography.titleLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bản sao lưu Cloud", style = MaterialTheme.typography.titleMedium)
                Text("Danh sách được tải theo tài khoản đang đăng nhập. Chỉ backup VERIFIED mới đủ điều kiện để xem xét khôi phục; PENDING/FAILED/DELETED không được dùng để khôi phục.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        if (!backupBusy) scope.launch {
                            backupBusy = true
                            try {
                                require(isCanonicalOwnerId(ownerId)) { "Tài khoản chủ kho không hợp lệ" }
                                require(SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId) {
                                    "Tài khoản đã thay đổi hoặc phiên đăng nhập hết hạn; không đưa backup vào hàng đợi"
                                }
                                WeeklyBackupWorkScheduler.enqueueNow(context, ownerId)
                                onMessage("Đã đưa yêu cầu sao lưu Cloud vào hàng đợi. Ứng dụng sẽ chờ có mạng; hãy tải lại danh sách Cloud để kiểm tra trạng thái VERIFIED sau khi hoàn tất.")
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                onMessage("Không thể bắt đầu sao lưu Cloud: ${error.message}")
                            } finally {
                                backupBusy = false
                            }
                        }
                    },
                    enabled = !backupBusy && !restoreLocked,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (backupBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.CloudUpload, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (backupBusy) "Đang đưa vào hàng đợi…" else "Sao lưu Cloud ngay")
                }
                Text("Thao tác này chạy một lần theo yêu cầu. Bản sao lưu tự động được lập lịch 30 ngày một lần khi tài khoản đã xác minh.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = { if (!inboxLoading) scope.launch {
                        inboxLoading = true; inboxError = ""; inboxItems = emptyList()
                        try { inboxItems = withContext(Dispatchers.IO) { WeeklyBackupRestoreInbox(context).listVerified(ownerId) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { inboxError = error.message ?: "Không đọc được backup đã lưu" }
                        finally { inboxLoading = false }
                    } }, enabled = !inboxLoading && !restoreLocked, modifier = Modifier.fillMaxWidth()
                ) {
                    if (inboxLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp)); Text(if (inboxLoading) "Đang kiểm tra backup đã lưu…" else "Tìm backup đã tải và lưu trên máy")
                }
                if (inboxError.isNotBlank()) Text("Không thể đọc inbox backup: $inboxError", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                inboxItems.forEach { item ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${item.backupKind} · ${item.partCount} linh kiện · ${item.movementCount} giao dịch", style = MaterialTheme.typography.titleSmall)
                        Text("Đã lưu: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(item.savedAt))} · ${(item.sizeBytes / (1024.0 * 1024.0)).let { String.format(Locale.getDefault(), "%.2f", it) }} MiB", style = MaterialTheme.typography.bodySmall)
                        Text("SHA-256 và cấu trúc ZIP đã được kiểm tra lại.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Button(onClick = { selectedInboxItem = item }, enabled = !restoreLocked && !restoreBusy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("Chọn backup này để khôi phục") }
                    } }
                }
                OutlinedButton(
                    onClick = {
                        if (!cloudCatalogLoading) scope.launch {
                            cloudCatalogLoading = true
                            cloudCatalogError = ""
                            cloudCatalogLoaded = false
                            cloudCatalogEntries = emptyList()
                            try {
                                val entries = withContext(Dispatchers.IO) {
                                    WeeklyBackupCloudCatalog().listForOwner(ownerId)
                                }
                                cloudCatalogEntries = entries
                                cloudCatalogLoaded = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                cloudCatalogEntries = emptyList()
                                cloudCatalogError = error.message?.take(240)
                                    ?.ifBlank { "Không thể tải danh sách backup Cloud." }
                                    ?: "Không thể tải danh sách backup Cloud. Kiểm tra mạng, phiên đăng nhập và quyền RPC."
                            } finally {
                                cloudCatalogLoading = false
                            }
                        }
                    },
                    enabled = !cloudCatalogLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (cloudCatalogLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.CloudDownload, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (cloudCatalogLoading) "Đang tải danh sách…" else "Tải danh sách backup Cloud")
                }
                if (cloudDownloadError.isNotBlank()) {
                    Text("Tải/xác minh backup thất bại: $cloudDownloadError", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (cloudCatalogError.isNotBlank()) {
                    Text("Không tải được danh sách Cloud: $cloudCatalogError", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("Đây là lỗi truy cập/dịch vụ, không có nghĩa là tài khoản chưa từng sao lưu.", style = MaterialTheme.typography.bodySmall)
                } else if (cloudCatalogLoaded && cloudCatalogEntries.isEmpty()) {
                    Text("Tài khoản hiện chưa có bản sao lưu Cloud nào.", style = MaterialTheme.typography.bodySmall)
                } else if (cloudCatalogLoaded) {
                    Text("Tìm thấy ${cloudCatalogEntries.size} bản sao lưu · hiển thị tối đa 30 bản mới nhất.", style = MaterialTheme.typography.bodySmall)
                    cloudCatalogEntries.take(30).forEach { entry ->
                        HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(when (entry.status) {
                                    WeeklyBackupCloudCatalog.Status.VERIFIED -> "Đã xác minh"
                                    WeeklyBackupCloudCatalog.Status.PENDING -> "Đang chờ hoàn tất"
                                    WeeklyBackupCloudCatalog.Status.FAILED -> "Thất bại"
                                    WeeklyBackupCloudCatalog.Status.DELETED -> "Đã xóa"
                                }, style = MaterialTheme.typography.titleSmall,
                                    color = if (entry.isRestoreCandidate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.weight(1f))
                                Text(entry.backupKind, style = MaterialTheme.typography.labelSmall)
                            }
                            Text("${entry.partCount} linh kiện · ${entry.movementCount} giao dịch · ${(entry.archiveSizeBytes / (1024.0 * 1024.0)).let { String.format(Locale.getDefault(), "%.2f", it) }} MiB", style = MaterialTheme.typography.bodySmall)
                            Text("Tạo lúc: ${entry.createdAt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (entry.isRestoreCandidate) {
                                Text("Backup VERIFIED · cần tải, xác minh và lưu vào vùng staging riêng tư trước khi khôi phục.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                OutlinedButton(
                                    onClick = {
                                        if (cloudDownloadBusyId == null) scope.launch {
                                            cloudDownloadBusyId = entry.backupId
                                            cloudDownloadError = ""
                                            cloudVerifiedFile = null
                                            try {
                                                val verified = withContext(Dispatchers.IO) {
                                                    WeeklyBackupCloudDownloader(context).downloadAndVerify(ownerId, entry)
                                                }
                                                val stored = withContext(Dispatchers.IO) {
                                                    WeeklyBackupRestoreInbox(context).persist(ownerId, entry, verified)
                                                }
                                                cloudVerifiedFile = verified.copy(file = stored.file, sha256 = stored.sha256)
                                                onMessage("Đã tải, xác minh và lưu backup Cloud ${entry.backupId} vào vùng staging riêng tư bền vững. Chưa khôi phục và chưa thay đổi dữ liệu kho.")
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                cloudDownloadError = error.message?.take(240)
                                                    ?.ifBlank { "Không thể tải hoặc xác minh backup Cloud." }
                                                    ?: "Không thể tải hoặc xác minh backup Cloud."
                                            } finally {
                                                cloudDownloadBusyId = null
                                            }
                                        }
                                    },
                                    enabled = cloudDownloadBusyId == null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (cloudDownloadBusyId == entry.backupId) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Default.CloudDownload, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (cloudDownloadBusyId == entry.backupId) "Đang tải và xác minh…" else "Tải và xác minh backup này")
                                }
                                if (cloudVerifiedFile?.backupId == entry.backupId) {
                                    Text("Đã xác minh SHA-256, cấu trúc ZIP, owner và metadata; bản ZIP đã được lưu trong vùng staging riêng tư bền vững của ứng dụng. Chưa khôi phục.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                            } else if (entry.status == WeeklyBackupCloudCatalog.Status.PENDING) {
                                Text("Chưa hoàn tất xác minh; không thể khôi phục.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onBatch, enabled = !restoreLocked, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCode, null); Spacer(Modifier.width(8.dp)); Text("Tạo mã linh kiện hàng loạt / đọc từ ảnh") }
        OutlinedButton(onClick = { exportLauncher.launch("bao_cao_kho.xlsx") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Xuất Excel XLSX (tồn kho + lịch sử)") }
        OutlinedButton(
            onClick = {
                if (!backupBusy) {
                    backupBusy = true
                    scope.launch {
                        try {
                            val savedAt = BackupManager.saveBackup(context, username, ownerId, parts, movements)
                            val savedName = savedAt.substringAfterLast('/')
                            onMessage("Đã sao lưu ZIP thành công: $savedName\n$savedAt\n${parts.size} linh kiện · ${movements.size} giao dịch.")
                        } catch (e: Exception) {
                            onMessage("Sao lưu ZIP thất bại: ${e.message}")
                        } finally { backupBusy = false }
                    }
                }
            },
            enabled = !restoreLocked && !backupBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (backupBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Backup, null)
            Spacer(Modifier.width(8.dp)); Text(if (backupBusy) "Đang sao lưu…" else "Sao lưu ZIP (kèm ảnh)")
        }
        Text("Tên tệp được tạo cố định theo tài khoản và thời điểm sao lưu: SL + tên KTV + ngày/tháng + giờ/phút (ví dụ: SLKTVA03111219.zip). Không thể đổi tên trong ứng dụng.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onRestore, enabled = !restoreBusy && restoreJournals.none { it.status !in listOf("COMPLETE", "ABORTED") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("Khôi phục từ ZIP (qua cloud xác minh)") }
        restoreJournals.firstOrNull { it.status !in listOf("COMPLETE", "ABORTED") }?.let { journal ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Có phiên khôi phục chưa hoàn tất", style = MaterialTheme.typography.titleSmall)
                    Text(restoreStatusTitle(journal.status), style = MaterialTheme.typography.bodyMedium)
                    Text("${journal.partCount} linh kiện · ${journal.movementCount} giao dịch · cập nhật ${SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(journal.updatedAt))}", style = MaterialTheme.typography.bodySmall)
                    Text(restoreStatusDescription(journal.status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (journal.lastError.isNotBlank()) {
                        Text("Lỗi gần nhất: ${journal.lastError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (journal.sourceType == "REMOTE_FEED") {
                        Text("Phiên này được đồng bộ tự động từ cloud; không cần chọn lại tệp ZIP.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Button(onClick = { onResumeRestore(journal.restoreId) }, enabled = !restoreBusy, modifier = Modifier.fillMaxWidth()) {
                            if (restoreBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(8.dp)); Text(if (journal.status in setOf("STAGING", "FAILED")) "Chọn lại ZIP để dựng lại staging" else "Tiếp tục khôi phục")
                        }
                    }
                }
            }
        }
        restoreJournals.firstOrNull { it.status == "COMPLETE" }?.let { journal ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Phiên gần nhất đã cập nhật kho thành công", style = MaterialTheme.typography.titleSmall)
                    Text("${journal.partCount} linh kiện · ${journal.movementCount} giao dịch · cursor ${journal.snapshotCursor}")
                    Text("Có thể chạy lại bước dọn staging cục bộ nếu lần trước ứng dụng bị đóng giữa chừng. Thao tác này không thay đổi kho hoặc lịch sử đã khôi phục.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onResumeRestore(journal.restoreId) }, enabled = !restoreBusy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Thử dọn dữ liệu tạm")
                    }
                }
            }
        }
        if (restoreBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (restoreLocked) {
            Text("Kho đang khóa thao tác thêm/sửa/xóa và sao lưu cho đến khi phiên khôi phục hoàn tất. Hãy tiếp tục phiên đang hiển thị bên trên.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text("Tệp JSON cũ tạm thời không hỗ trợ khôi phục trực tiếp. Hãy dùng bản sao lưu ZIP.", style = MaterialTheme.typography.bodySmall)
        Text("Tổng mã: ${parts.size} · Tổng tồn: ${parts.sumOf { it.quantity }} · Giao dịch: ${movements.size}")
        Text("Cảnh báo: khôi phục sẽ thay thế dữ liệu hiện tại. Hãy sao lưu trước khi khôi phục.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    selectedInboxItem?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedInboxItem = null },
            title = { Text("Xác nhận khôi phục backup Cloud") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${item.partCount} linh kiện · ${item.movementCount} giao dịch · ${item.backupKind}")
                Text("Ứng dụng sẽ gửi dữ liệu backup lên quy trình khôi phục Cloud và thay thế kho + lịch sử hiện tại của tài khoản này.", color = MaterialTheme.colorScheme.error)
                Text("Tài khoản KTV khác không bị ảnh hưởng. Hãy chắc chắn đã đồng bộ hết thao tác đang chờ và đã sao lưu dữ liệu hiện tại.")
                Text("Đây là thao tác có ảnh hưởng dữ liệu. Không thể hoàn tác tự động.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = { selectedInboxItem = null; onRestoreInbox(item.file) }, enabled = !restoreBusy && !restoreLocked) { Text("Xác nhận thay thế kho") } },
            dismissButton = { TextButton(onClick = { selectedInboxItem = null }) { Text("Hủy") } }
        )
    }
}

@Composable
fun MachineHistoryDialog(
    machineCode: String,
    movements: List<Movement>,
    showOwner: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lịch sử linh kiện · $machineCode") },
        text = {
            if (movements.isEmpty()) {
                Text("Chưa có giao dịch xuất linh kiện nào được ghi nhận cho thiết bị này.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(movements) { movement ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text("${movement.code} · ${movement.partName}", style = MaterialTheme.typography.titleSmall)
                                Text("Số lượng: ${movement.amount}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(movement.time)),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (showOwner && movement.ownerName.isNotBlank()) {
                                    Text("KTV: ${movement.ownerName}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
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



@Composable
private fun SessionRestoreScreen(
    message: String,
    onRetry: () -> Unit,
    onSignOut: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text("Chưa xác minh được phiên đăng nhập", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Thử kết nối lại") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Đăng xuất") }
        Spacer(Modifier.height(12.dp))
        Text("Dữ liệu trên điện thoại vẫn được giữ nguyên. Kho chỉ mở sau khi xác minh được tài khoản.", style = MaterialTheme.typography.bodySmall)
    }
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

private fun ownerPendingPhotoDirectory(context: android.content.Context, ownerId: String): File {
    require(Regex("^[0-9a-fA-F-]{36}$").matches(ownerId)) { "Mã chủ kho không hợp lệ để lưu ảnh" }
    return File(File(context.filesDir, "pending_part_photos"), ownerId).canonicalFile
}

fun copyImageToPrivateStorage(context: android.content.Context, uri: Uri, ownerId: String): String {
    // Ảnh chờ upload được tách theo chủ kho; không dùng chung thư mục giữa các KTV.
    val dir = ownerPendingPhotoDirectory(context, ownerId).apply { mkdirs() }
    val dest = File(dir, "pending_${java.util.UUID.randomUUID()}.jpg")
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
