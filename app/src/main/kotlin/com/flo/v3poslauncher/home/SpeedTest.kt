package com.flo.v3poslauncher.home

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * A small download speed test for the home screen's "Run speed test" button: measures latency
 * (time to first response) and sustained download throughput against Cloudflare's public speed
 * endpoint for up to [MAX_SECONDS]. HTTPS only (see network_security_config.xml).
 *
 * This is the ONLY network traffic the launcher itself ever generates, and only when a person
 * taps the button.
 */
object SpeedTest {

    data class Result(val mbps: Double, val latencyMs: Long, val bytes: Long, val error: String? = null) {
        val ok: Boolean get() = error == null
        fun summary(): String =
            if (ok) String.format("%.1f Mbps down  ·  %d ms", mbps, latencyMs) else "Failed: $error"
    }

    private const val HOST = "https://speed.cloudflare.com"
    private const val DOWNLOAD_BYTES = 60_000_000L
    private const val MAX_SECONDS = 6.0

    @Volatile private var running = false
    val isRunning: Boolean get() = running

    /** Runs on a background thread; [onProgress] and [onDone] are delivered on the main thread. */
    fun run(onProgress: (String) -> Unit, onDone: (Result) -> Unit) {
        if (running) return
        running = true
        val main = Handler(Looper.getMainLooper())
        thread(name = "speed-test") {
            val result = try {
                main.post { onProgress("Measuring latency…") }
                val latency = measureLatency()
                main.post { onProgress("Downloading…") }
                val (bytes, seconds) = download { mbpsSoFar ->
                    main.post { onProgress(String.format("%.1f Mbps…", mbpsSoFar)) }
                }
                val mbps = if (seconds > 0) bytes * 8.0 / seconds / 1_000_000.0 else 0.0
                Result(mbps, latency, bytes)
            } catch (t: Throwable) {
                Result(0.0, 0, 0, t.message ?: t.javaClass.simpleName)
            } finally {
                running = false
            }
            main.post { onDone(result) }
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 8_000
            setRequestProperty("Cache-Control", "no-cache")
            instanceFollowRedirects = true
        }

    private fun measureLatency(): Long {
        var best = Long.MAX_VALUE
        repeat(3) {
            val c = open("$HOST/__down?bytes=0")
            val t0 = SystemClock.elapsedRealtime()
            try {
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                c.inputStream.use { it.read() }
            } finally { c.disconnect() }
            best = minOf(best, SystemClock.elapsedRealtime() - t0)
        }
        return best
    }

    private fun download(progress: (Double) -> Unit): Pair<Long, Double> {
        val c = open("$HOST/__down?bytes=$DOWNLOAD_BYTES")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        var lastReport = 0L
        val t0 = SystemClock.elapsedRealtime()
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    val elapsed = (SystemClock.elapsedRealtime() - t0) / 1000.0
                    if (elapsed >= MAX_SECONDS) break
                    if (SystemClock.elapsedRealtime() - lastReport > 500 && elapsed > 0.5) {
                        lastReport = SystemClock.elapsedRealtime()
                        progress(total * 8.0 / elapsed / 1_000_000.0)
                    }
                }
            }
        } finally { c.disconnect() }
        val seconds = (SystemClock.elapsedRealtime() - t0) / 1000.0
        return total to seconds
    }
}
