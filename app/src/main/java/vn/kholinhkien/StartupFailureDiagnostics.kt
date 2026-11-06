package vn.kholinhkien

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stores non-secret startup failures so a future support report has the real exception. */
internal object StartupFailureDiagnostics {
    fun record(context: Context, stage: String, error: Throwable) {
        runCatching {
            val directory = File(context.applicationContext.filesDir, "diagnostics").apply { mkdirs() }
            val target = File(directory, "startup-failures.log")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            if (target.exists() && target.length() > 512L * 1024L) target.writeText("", Charsets.UTF_8)
            target.appendText("\n[$timestamp] stage=$stage\n${error.stackTraceToString()}\n", Charsets.UTF_8)
        }
    }
}
