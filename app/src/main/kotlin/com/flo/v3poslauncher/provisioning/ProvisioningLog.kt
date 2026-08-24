package com.flo.v3poslauncher.provisioning

import android.content.Context
import android.util.Log
import com.flo.v3poslauncher.config.Constants
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only on-device log, viewable from the admin panel. Rotates at [Constants.LOG_MAX_BYTES].
 * Mirrors to logcat. Callers are responsible for never passing secrets (PIN, Wi-Fi PSK).
 */
object ProvisioningLog {
    private const val TAG = "V3PosLauncher"
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun i(context: Context, msg: String) = write(context, "I", msg).also { Log.i(TAG, msg) }
    fun w(context: Context, msg: String) = write(context, "W", msg).also { Log.w(TAG, msg) }
    fun e(context: Context, msg: String, t: Throwable? = null) {
        val full = if (t != null) "$msg :: ${t.javaClass.simpleName}: ${t.message}" else msg
        write(context, "E", full)
        Log.e(TAG, msg, t)
    }

    fun read(context: Context): String = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) "(log is empty)" else runCatching { f.readText() }.getOrElse { "(could not read log: $it)" }
    }

    fun clear(context: Context) = synchronized(lock) { file(context).delete(); Unit }

    private fun file(context: Context) = File(context.applicationContext.filesDir, Constants.LOG_FILE_NAME)

    private fun write(context: Context, level: String, msg: String) {
        synchronized(lock) {
            try {
                val f = file(context)
                if (f.exists() && f.length() > Constants.LOG_MAX_BYTES) {
                    // Keep the newest half.
                    val text = f.readText()
                    f.writeText("…(rotated)…\n" + text.substring(text.length / 2))
                }
                f.appendText("${fmt.format(Date())} $level $msg\n")
            } catch (_: Throwable) {
                // Logging must never crash the launcher.
            }
        }
    }
}
