package com.flo.v3poslauncher.provisioning

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.flo.v3poslauncher.admin.DevicePolicy
import com.flo.v3poslauncher.config.AppConfig
import com.flo.v3poslauncher.provisioning.steps.AppsStep
import com.flo.v3poslauncher.provisioning.steps.DisplayPolicyStep
import com.flo.v3poslauncher.provisioning.steps.HideStockLauncherStep
import com.flo.v3poslauncher.provisioning.steps.HomeStep
import com.flo.v3poslauncher.provisioning.steps.LockTaskStep
import com.flo.v3poslauncher.provisioning.steps.LockdownStep
import com.flo.v3poslauncher.provisioning.steps.RemoveAppsStep
import com.flo.v3poslauncher.provisioning.steps.ScreensaverStep
import com.flo.v3poslauncher.provisioning.steps.WifiStep
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes provisioning steps sequentially on a single background thread and publishes
 * per-step state to the UI on the main thread. Lives in [com.flo.v3poslauncher.App] so the
 * status activity can be recreated without losing the run.
 *
 * Failure semantics: a [StepResult.Fail] PAUSES the run (state = PAUSED_ON_FAILURE). The UI
 * offers Retry (re-runs the failed step and continues) or Skip (marks it SKIPPED and continues).
 * Nothing is ever silently skipped.
 */
class ProvisioningRunner(private val appContext: Context) {

    enum class Status { PENDING, RUNNING, OK, WARN, FAIL, SKIPPED }

    class StepState(val id: StepId) {
        @Volatile var status: Status = Status.PENDING
        @Volatile var message: String = ""
        @Volatile var progress: String = ""
    }

    enum class RunState { IDLE, RUNNING, PAUSED_ON_FAILURE, DONE }

    interface Listener {
        fun onStepChanged(state: StepState)
        fun onRunStateChanged(state: RunState)
    }

    val steps: List<StepState> = StepId.active().map { StepState(it) }
    @Volatile var runState: RunState = RunState.IDLE
        private set
    @Volatile var complianceMode: Boolean = false
        private set

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "provisioning").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val cancelFlag = AtomicBoolean(false)
    private val listeners = mutableListOf<Listener>()
    private var queue: ArrayDeque<StepId> = ArrayDeque()

    private val implementations: Map<StepId, ProvisioningStep> = listOf(
        HomeStep(), HideStockLauncherStep(), WifiStep(), ScreensaverStep(), DisplayPolicyStep(),
        AppsStep(), RemoveAppsStep(), LockTaskStep(), LockdownStep(),
    ).associateBy { it.id }

    fun addListener(l: Listener) = synchronized(listeners) { listeners.add(l) }
    fun removeListener(l: Listener) = synchronized(listeners) { listeners.remove(l) }

    fun state(id: StepId): StepState = steps.first { it.id == id }

    /** Starts the full sequence. No-op if a run is already in progress (attach a listener instead). */
    fun startAll(compliance: Boolean) {
        if (runState == RunState.RUNNING || runState == RunState.PAUSED_ON_FAILURE) {
            ProvisioningLog.i(appContext, "Runner: run already active (${runState}); not restarting")
            return
        }
        start(StepId.active(), compliance)
    }

    /** Runs an arbitrary subset (admin panel "re-run step"). Always standalone mode. */
    fun start(ids: List<StepId>, compliance: Boolean) {
        if (runState == RunState.RUNNING) return
        complianceMode = compliance
        cancelFlag.set(false)
        steps.forEach { it.status = Status.PENDING; it.message = ""; it.progress = "" }
        queue = ArrayDeque(ids)
        ProvisioningLog.i(appContext, "Runner: starting ${ids.joinToString { it.name }} compliance=$compliance :: ${DevicePolicy(appContext).describe()}")
        setRunState(RunState.RUNNING)
        executor.execute { drain() }
    }

    /** After a FAIL: re-run the failed step then continue. */
    fun retryFailed() {
        if (runState != RunState.PAUSED_ON_FAILURE) return
        val failed = steps.firstOrNull { it.status == Status.FAIL } ?: return
        queue.addFirst(failed.id)
        failed.status = Status.PENDING; failed.message = ""; failed.progress = ""
        notifyStep(failed)
        setRunState(RunState.RUNNING)
        executor.execute { drain() }
    }

    /** After a FAIL: leave it failed-but-skipped and continue with the rest. */
    fun skipFailed() {
        if (runState != RunState.PAUSED_ON_FAILURE) return
        val failed = steps.firstOrNull { it.status == Status.FAIL } ?: return
        failed.status = Status.SKIPPED
        failed.message = "Skipped by technician: ${failed.message}"
        AppConfig.get(appContext).setStepStatus(failed.id.name, "SKIPPED")
        ProvisioningLog.w(appContext, "Runner: ${failed.id} skipped by technician")
        notifyStep(failed)
        setRunState(RunState.RUNNING)
        executor.execute { drain() }
    }

    fun cancel() {
        cancelFlag.set(true)
    }

    val hasFailure: Boolean get() = steps.any { it.status == Status.FAIL }
    val allTerminal: Boolean get() = steps.all { it.status != Status.PENDING && it.status != Status.RUNNING }

    // ---- internals -----------------------------------------------------------------------

    private fun drain() {
        val config = AppConfig.get(appContext)
        val dp = DevicePolicy(appContext)
        while (true) {
            val id = queue.removeFirstOrNull() ?: break
            val st = state(id)
            st.status = Status.RUNNING; st.message = ""; st.progress = ""
            notifyStep(st)
            val ctx = StepContext(
                context = appContext, config = config, dp = dp, complianceMode = complianceMode,
                progressSink = { p -> st.progress = p; notifyStep(st) },
                cancelled = { cancelFlag.get() },
            )
            ProvisioningLog.i(appContext, "Step ${id.name}: start")
            val result = try {
                implementations.getValue(id).run(ctx)
            } catch (t: Throwable) {
                ProvisioningLog.e(appContext, "Step ${id.name}: uncaught", t)
                StepResult.Fail("Unexpected error: ${t.javaClass.simpleName}: ${t.message}")
            }
            st.message = result.message
            st.progress = ""
            st.status = when (result) {
                is StepResult.Ok -> Status.OK
                is StepResult.Warn -> Status.WARN
                is StepResult.Fail -> Status.FAIL
            }
            config.setStepStatus(id.name, "${result.label}: ${result.message}")
            ProvisioningLog.i(appContext, "Step ${id.name}: ${result.label} — ${result.message}")
            notifyStep(st)
            if (result is StepResult.Fail) {
                setRunState(RunState.PAUSED_ON_FAILURE)
                return
            }
        }
        // Sequence finished (possibly with warnings/skips).
        val fullRun = steps.none { it.status == Status.PENDING }
        if (fullRun && !hasFailure) {
            config.provisioningCompleted = true
        }
        setRunState(RunState.DONE)
    }

    private fun setRunState(s: RunState) {
        runState = s
        main.post { synchronized(listeners) { listeners.toList() }.forEach { it.onRunStateChanged(s) } }
    }

    private fun notifyStep(st: StepState) {
        main.post { synchronized(listeners) { listeners.toList() }.forEach { it.onStepChanged(st) } }
    }
}
