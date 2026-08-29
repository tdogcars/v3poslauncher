package com.flo.v3poslauncher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.DateFormat as AndroidDateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import com.flo.v3poslauncher.R
import com.flo.v3poslauncher.admin.Screensaver
import com.flo.v3poslauncher.config.AppConfig
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** How long a finger must stay down on empty background to open configuration. */
private const val CONFIGURE_HOLD_MILLIS = 2000L

private sealed interface HomeApps {
    data object Loading : HomeApps
    data class Loaded(val apps: List<InstalledApp>) : HomeApps

    /** None of the configured packages resolved to a launchable app. */
    data object Empty : HomeApps
}

/**
 * Opens [onHold] after an uninterrupted [CONFIGURE_HOLD_MILLIS] press. A short
 * tap does nothing: releasing (or the gesture being consumed/cancelled) before
 * the timeout simply ends the gesture.
 */
private fun Modifier.holdToConfigure(onHold: () -> Unit): Modifier =
    pointerInput(onHold) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            try {
                withTimeout(CONFIGURE_HOLD_MILLIS) { waitForUpOrCancellation() }
            } catch (e: PointerEventTimeoutCancellationException) {
                onHold()
            }
        }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(config: LauncherConfig, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val iconSizePx = with(LocalDensity.current) { config.iconSizeDp.dp.roundToPx() }

    // Bumped on every resume so installing/removing configured apps is picked
    // up immediately, and after a failed launch so the tile set re-resolves.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }

    val lookup by produceState<HomeApps>(
        initialValue = HomeApps.Loading,
        config.posPackages, iconSizePx, refresh,
    ) {
        val resolved = config.posPackages.mapNotNull { resolvePosApp(context, it, iconSizePx) }
        value = if (resolved.isEmpty()) HomeApps.Empty else HomeApps.Loaded(resolved)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Background layer: any press on empty space lands here. Content is
        // stacked on top, so presses on the tiles never reach this gesture.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .holdToConfigure {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenSettings()
                }
        )

        ClockDisplay(
            refreshKey = refresh,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .systemBarsPadding()
                .padding(24.dp),
        )

        // Network status pill and speed test, top-left. On first launch while
        // on Wi-Fi, asks once for location, which Android requires before it
        // will reveal the SSID; denial just leaves the generic "Wi-Fi" label.
        var permissionTick by remember { mutableIntStateOf(0) }
        var askedForLocation by remember { mutableStateOf(false) }
        val netStatus = rememberNetworkStatus(refresh, permissionTick)
        val locationPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { permissionTick++ }

        LaunchedEffect(netStatus) {
            val needsLocation = netStatus.type == NetType.WIFI &&
                netStatus.name == null &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            if (needsLocation && !askedForLocation) {
                askedForLocation = true
                locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .systemBarsPadding()
                .padding(16.dp),
        ) {
            NetworkStatusWidget(status = netStatus)
            SpeedTestInline(modifier = Modifier.padding(start = 14.dp, top = 2.dp))
        }

        ScreensaverWarning(
            refreshKey = refresh,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .padding(16.dp),
        )

        when (val state = lookup) {
            HomeApps.Loading -> Unit // stays pure black while resolving

            is HomeApps.Loaded -> FlowRow(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                state.apps.forEach { app ->
                    AppTile(
                        app = app,
                        iconSizeDp = config.iconSizeDp,
                        onLaunchFailed = { refresh++ },
                    )
                }
            }

            HomeApps.Empty -> MissingApps(
                onOpenSettings = onOpenSettings,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * Device date and time, updated on the minute. Formats follow the device's
 * locale and 12/24-hour setting. Not clickable: touches over it fall through
 * to the hold-to-configure background layer.
 */
/**
 * Advisory at the foot of the home screen when the screen saver was asked for but is not actually
 * running on this terminal. Same intent as the default-launcher prompt, minus the blocking: that
 * one replaces the whole screen, which a POS terminal cannot afford, so this never covers a tile.
 *
 * It shows in exactly one state — wanted, not active — so it is silent both on a finished terminal
 * and on a fleet that does not want a screen saver. Deliberately not dismissible: the two ways to
 * clear it are to finish the one-time grant or to switch the screen saver off in Advanced device
 * management, and a warning you can wave away is one nobody ever acts on. It re-checks every few
 * seconds, so it disappears on its own moments after the grant lands, with nothing to tap.
 */
@Composable
private fun ScreensaverWarning(refreshKey: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val needed by produceState(initialValue = false, refreshKey) {
        while (true) {
            val cfg = AppConfig.get(context)
            value = cfg.screensaverEnabled && !cfg.screensaverActive
            delay(3000)
        }
    }
    if (!needed) return

    Column(
        modifier = modifier
            .widthIn(max = 720.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF3A2E10))
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Screen saver is not active on this terminal",
            color = Color(0xFFE8B93B),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        if (!expanded) {
            Text(
                text = "Tap for the one-time setup command",
                color = Color(0xFFB59B62),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Android only allows this to be switched on over USB. With the terminal " +
                    "connected to a technician machine, run:",
                color = Color(0xFFB59B62),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = Screensaver.GRANT_COMMAND,
                color = Color(0xFFE8B93B),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This notice clears itself a few seconds after the grant. If this fleet " +
                    "does not want a screen saver, switch it off in Advanced device management " +
                    "and the notice stops for good.",
                color = Color(0xFFB59B62),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ClockDisplay(refreshKey: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Re-keyed on resume so the clock snaps to the correct time immediately
    // after the screen wakes instead of waiting out a stale minute delay.
    LaunchedEffect(refreshKey) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - (now % 60_000L) + 50L)
        }
    }

    val timeFormat = remember(context) { AndroidDateFormat.getTimeFormat(context) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.FULL) }

    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        Text(
            text = timeFormat.format(Date(now)),
            color = Color.White,
            fontSize = 32.sp,
        )
        Text(
            text = dateFormat.format(Date(now)),
            color = Color(0xFF999999),
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun AppTile(
    app: InstalledApp,
    iconSizeDp: Int,
    onLaunchFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val failedMessage = stringResource(R.string.launch_failed, app.packageName)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .clickable {
                if (!launchApp(context, app.packageName)) {
                    Toast.makeText(context, failedMessage, Toast.LENGTH_LONG).show()
                    onLaunchFailed()
                }
            }
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            modifier = Modifier.size(iconSizeDp.dp),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = app.label,
            color = Color.White,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MissingApps(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 560.dp)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.missing_title),
            color = Color.White,
            fontSize = 24.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.missing_hint),
            color = Color(0xFF888888),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(28.dp))
        OutlinedButton(onClick = onOpenSettings) {
            Text(text = stringResource(R.string.missing_open_settings))
        }
    }
}
