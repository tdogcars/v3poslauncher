package com.flo.v3poslauncher.provisioning

import android.content.Context
import com.flo.v3poslauncher.admin.DevicePolicy
import com.flo.v3poslauncher.config.AppConfig

/** The first-run steps, in the order they run. No APK is installed by the launcher. */
enum class StepId(val title: String) {
    HOME("Set as default home"),
    HIDE_STOCK("Hide stock launcher / taskbar"),
    WIFI("Save FLO Secure Wi-Fi"),
    DISPLAY("Apply display policy"),
    APPS("Verify home apps"),
    LOCK_TASK("Dedicated terminal mode"),
    LOCKDOWN("Hide non-allowed apps (lab only)");
}

/** Outcome of one step. WARN continues the sequence; FAIL pauses it for Retry / Skip. */
sealed class StepResult(val message: String) {
    class Ok(message: String) : StepResult(message)
    class Warn(message: String) : StepResult(message)
    class Fail(message: String) : StepResult(message)

    val label: String
        get() = when (this) {
            is Ok -> "OK"
            is Warn -> "WARN"
            is Fail -> "FAIL"
        }
}

/** What a step gets to work with. */
class StepContext(
    val context: Context,
    val config: AppConfig,
    val dp: DevicePolicy,
    /** Sequence is the setup-wizard compliance activity (API 30+). Changes how LAUNCH behaves. */
    val complianceMode: Boolean,
    private val progressSink: (String) -> Unit,
    private val cancelled: () -> Boolean,
) {
    fun progress(text: String) = progressSink(text)
    val isCancelled: Boolean get() = cancelled()
    fun log(msg: String) = ProvisioningLog.i(context, msg)
    fun warn(msg: String) = ProvisioningLog.w(context, msg)
    fun err(msg: String, t: Throwable? = null) = ProvisioningLog.e(context, msg, t)
}

interface ProvisioningStep {
    val id: StepId
    /** Must not throw: convert every failure into [StepResult.Fail] with an actionable message. */
    fun run(ctx: StepContext): StepResult
}

/** Convenience: run a block, converting any throwable into a Fail with the exception text. */
inline fun guarded(ctx: StepContext, what: String, block: () -> StepResult): StepResult = try {
    block()
} catch (t: Throwable) {
    ctx.err("$what failed", t)
    StepResult.Fail("$what failed: ${t.javaClass.simpleName}: ${t.message ?: "(no message)"}")
}
