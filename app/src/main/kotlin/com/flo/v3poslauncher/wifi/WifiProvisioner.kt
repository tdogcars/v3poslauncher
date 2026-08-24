package com.flo.v3poslauncher.wifi

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.flo.v3poslauncher.provisioning.ProvisioningLog

/**
 * Saves the company-standard WPA2 network as a persistent, auto-join network, using the
 * Device-Owner-privileged path for the running API level. NEVER logs or returns the password.
 *
 * API-level rationale:
 *  - Android 10 (API 29): apps targeting/ running as Device Owner may still use the classic
 *    WifiManager.addNetwork(WifiConfiguration) path. For ordinary apps this call is neutered
 *    on API 29 (returns -1), but Device Owner / Profile Owner keep full add/modify rights.
 *  - Android 11+ (API 30+): WifiManager.addNetworkPrivileged(WifiConfiguration) is the correct
 *    privileged entry point for a Device Owner. It returns AddNetworkResult with a status code
 *    and netId, and the resulting configuration is owned by us, persistent, and auto-join.
 *    We fall back to addNetwork() if addNetworkPrivileged throws SecurityException on an OEM
 *    build that gates it differently.
 *
 * We deliberately do NOT use WifiNetworkSuggestion: suggestions are advisory (the user can
 * decline them and they are not guaranteed auto-join), which is wrong for an unattended POS.
 */
class WifiProvisioner(context: Context) {
    private val appContext = context.applicationContext
    private val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    sealed class Result(val message: String) {
        class Added(message: String) : Result(message)
        class AlreadyPresent(message: String) : Result(message)
        class Failure(message: String) : Result(message)
    }

    /** Ensures the SSID is saved. Safe to call on every boot ("self-heal"). */
    fun ensureNetwork(ssid: String, password: String): Result {
        return try {
            if (!wifi.isWifiEnabled) {
                // DO can toggle Wi-Fi on API < 29; on 29+ setWifiEnabled is a no-op for apps,
                // but the network is still saved and will connect once Wi-Fi is on.
                runCatching { wifi.setWifiEnabled(true) }
            }

            if (existingNetworkId(ssid) != null) {
                return Result.AlreadyPresent("Network '$ssid' already saved.")
            }

            val config = buildConfig(ssid, password)

            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val res = wifi.addNetworkPrivileged(config)
                    if (res.statusCode == WifiManager.AddNetworkResult.STATUS_SUCCESS) {
                        enableAutoJoin(res.networkId)
                        ProvisioningLog.i(appContext, "WifiProvisioner: added '$ssid' via addNetworkPrivileged netId=${res.networkId}")
                        return Result.Added("Saved '$ssid' (privileged, auto-join).")
                    }
                    ProvisioningLog.w(appContext, "WifiProvisioner: addNetworkPrivileged status=${res.statusCode}; falling back to addNetwork")
                } catch (se: SecurityException) {
                    ProvisioningLog.w(appContext, "WifiProvisioner: addNetworkPrivileged denied on this OEM build; falling back to addNetwork")
                }
            }

            @Suppress("DEPRECATION")
            val netId = wifi.addNetwork(config)
            if (netId < 0) {
                return Result.Failure(
                    "Could not save '$ssid' (addNetwork returned $netId). " +
                        "Verify this app is Device Owner; ordinary apps cannot add networks on Android 10+.",
                )
            }
            enableAutoJoin(netId)
            @Suppress("DEPRECATION")
            runCatching { wifi.saveConfiguration() } // no-op / deprecated on newer levels; harmless
            ProvisioningLog.i(appContext, "WifiProvisioner: added '$ssid' via addNetwork netId=$netId")
            Result.Added("Saved '$ssid' (auto-join).")
        } catch (t: Throwable) {
            ProvisioningLog.e(appContext, "WifiProvisioner: failed to save network", t)
            Result.Failure("Wi-Fi save failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Removes the saved network (revert). */
    fun removeNetwork(ssid: String): Boolean {
        val id = existingNetworkId(ssid) ?: return false
        @Suppress("DEPRECATION")
        val ok = wifi.removeNetwork(id)
        @Suppress("DEPRECATION")
        runCatching { wifi.saveConfiguration() }
        ProvisioningLog.i(appContext, "WifiProvisioner: removeNetwork('$ssid') -> $ok")
        return ok
    }

    private fun enableAutoJoin(netId: Int) {
        @Suppress("DEPRECATION")
        runCatching { wifi.enableNetwork(netId, false) } // false = don't disable others
    }

    private fun existingNetworkId(ssid: String): Int? {
        val quoted = "\"$ssid\""
        return try {
            @Suppress("DEPRECATION")
            wifi.configuredNetworks?.firstOrNull { it.SSID == quoted }?.networkId
        } catch (t: Throwable) {
            // getConfiguredNetworks can throw/return empty for apps that aren't privileged;
            // for a Device Owner it returns our own + system configs.
            ProvisioningLog.w(appContext, "WifiProvisioner: could not read configured networks: ${t.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun buildConfig(ssid: String, password: String): WifiConfiguration =
        WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$password\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            status = WifiConfiguration.Status.ENABLED
            // Persistent auto-join: leaving priority default and status ENABLED is sufficient.
        }
}
