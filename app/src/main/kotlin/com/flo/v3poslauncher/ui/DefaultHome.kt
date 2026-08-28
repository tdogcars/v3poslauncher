package com.flo.v3poslauncher.ui

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.flo.v3poslauncher.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** True when this app currently holds the HOME role / is the default launcher. */
fun isDefaultLauncher(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
            return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        }
    }
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    @Suppress("DEPRECATION")
    val resolved = context.packageManager
        .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
    return resolved?.activityInfo?.packageName == context.packageName
}

/**
 * Shown instead of the home screen while this app is not the default launcher.
 * On first appearance it fires the system prompt automatically: the HOME role
 * request dialog on Android 10+, or the Home settings page on 7.0–9.
 */
@Composable
fun DefaultHomePrompt(
    autoRequest: Boolean,
    onAutoRequested: () -> Unit,
    onRecheck: () -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val manualHint = stringResource(R.string.default_home_manual_hint)
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onRecheck() }

    fun openHomeSettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            // Some OEM ROMs hide this settings page entirely.
            Toast.makeText(context, manualHint, Toast.LENGTH_LONG).show()
        }
    }

    fun requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        openHomeSettings()
    }

    if (autoRequest) {
        LaunchedEffect(Unit) {
            onAutoRequested()
            requestDefaultHome()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 560.dp)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.default_home_title),
                color = Color.White,
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.default_home_body),
                color = Color(0xFF999999),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(28.dp))
            Button(onClick = { requestDefaultHome() }) {
                Text(text = stringResource(R.string.default_home_set))
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Escape hatch when the role dialog is suppressed (declined twice,
            // or an OEM skin blocks it): go straight to the settings page.
            TextButton(onClick = { openHomeSettings() }) {
                Text(text = stringResource(R.string.default_home_open_settings))
            }
            TextButton(onClick = onContinue) {
                Text(
                    text = stringResource(R.string.default_home_continue),
                    color = Color(0xFF777777),
                )
            }
        }
    }
}
