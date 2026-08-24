# v3poslauncher

QR-provisioned **Device Owner** Android launcher for FLO point-of-sale terminals.

- **applicationId:** `com.flo.v3poslauncher` (separate from v1 so it ships, evaluates, and rolls
  back independently)
- **minSdk 29 (Android 10) · compileSdk/targetSdk 35 (Android 15)**
- True-black home screen showing a small, admin-editable grid of **already-installed** apps
  (default **Chrome** + **Settings**, 125 dp icons). Long-press for the PIN-gated admin panel.

## What it does

A technician scans one printed QR card on a factory-fresh terminal. The device provisions itself:
this app becomes the default Home, the stock launcher/taskbar (and its suggested apps) is hidden,
the `FLO Secure` Wi-Fi is saved, the screen is kept on, and the terminal lands on the launcher's
app grid. **No additional APK is installed** — the launcher only shows apps already on the device.
Reboots come straight back to the grid. Everything is reversible from the admin panel, ending in
`clearDeviceOwnerApp()` — no factory reset.

> **Lock task mode is intentionally not used.** Home and Recents stay functional.

## Build

```bash
./gradlew :app:assembleRelease     # signed if RELEASE_* env vars are set, else debug-signed
./gradlew :app:assembleDebug
```

Requires JDK 17 and the Android SDK (compileSdk 35, build-tools). Gradle 8.9 via the wrapper; AGP
8.7.3.

## Provisioning, keystore, QR, revert, OEM caveats (incl. the MicroTouch taskbar)

See **[PROVISIONING.md](PROVISIONING.md)** — the operational source of truth.

## Layout

```
app/src/main/kotlin/com/flo/v3poslauncher/
  App.kt                       process singleton (provisioning runner)
  admin/                       DeviceAdminReceiver, DevicePolicy, PIN gate, admin panel, log viewer
  boot/BootReceiver.kt         re-assert Wi-Fi + display on boot
  config/                      Constants, AppConfig (prefs), AdminExtras (QR bundle parsing)
  home/                        HomeActivity (app-grid launcher), AppLauncher
  provisioning/                GetProvisioningModeActivity, ProvisioningActivity (status screen),
                               ProvisioningRunner, RevertManager, steps/ (home, hide, wifi, display, apps)
  util/                        PinHasher, Hex
.github/workflows/release.yml  build+sign, verify cert==keystore, publish, generate QR
ci/make_qr.py                  provisioning-qr.png generator
ci/compute_signature_checksum.sh
```
