# v3poslauncher — Provisioning & Operations

QR-provisioned Device Owner launcher for FLO point-of-sale terminals.

`applicationId` **`com.flo.v3poslauncher`** · admin component
`com.flo.v3poslauncher/com.flo.v3poslauncher.admin.PosDeviceAdminReceiver`

This launcher is deployed as an Android **Device Owner** via QR code on a factory-fresh device.
Once provisioned it shows a true-black home screen with a small grid of **already-installed** apps
(by default **Chrome** and **Settings**), hides the stock launcher/taskbar, and keeps the screen
on. **It installs no other APK.** Every policy it applies is reversible from the admin panel
without a factory reset.

---

## 0. How Device Owner privilege flows from the QR scan (one paragraph)

A factory-fresh device (zero accounts) tapped 6× on the setup-wizard welcome screen opens the QR
reader. The QR encodes a JSON provisioning payload naming our admin component, the HTTPS URL of the
signed launcher APK, and the **signing-certificate checksum**. The setup wizard connects to the
baked-in `FLO Secure` Wi-Fi, downloads the launcher APK, verifies its signature against that
checksum, installs it, and grants it **Device Owner**. On Android 11+ the wizard first calls our
`GetProvisioningModeActivity` (we answer "fully managed device"), fires `onProfileProvisioningComplete`,
then launches our `ProvisioningActivity` as the `ADMIN_POLICY_COMPLIANCE` screen; on Android 10 it
fires `onProfileProvisioningComplete` and launches `ProvisioningActivity` via
`PROVISIONING_SUCCESSFUL`. From that true-black status screen the app — now Device Owner — sets
itself as persistent HOME, hides the stock launcher/taskbar, saves the Wi-Fi network, applies
display policy, and verifies the home apps exist. When it finishes, the terminal lands on the
launcher's app grid (Chrome + Settings). Every subsequent boot comes straight back to that grid,
and the whole thing is reversible from the PIN-gated admin panel, ending in `clearDeviceOwnerApp()`.

---

## 1. One-time setup (do this once, ever)

### 1.1 Create the release keystore

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias flo-pos-launcher \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass '<STORE_PASSWORD>' -keypass '<KEY_PASSWORD>' \
  -dname "CN=FLO POS Launcher, O=FLO, C=US"
```

> **⚠ The keystore must never change once devices are provisioned.**
> The printed QR contains `PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` — the SHA-256 of *this*
> certificate. Android will only install a launcher APK whose signature matches. If you lose this
> keystore or sign a future release with a different one, **every printed QR card becomes
> unscannable** and every already-provisioned device can no longer receive launcher updates
> (Android rejects an update signed by a different key). Back it up in at least two secure
> locations. It is the master key to the fleet.

### 1.2 Derive the signature checksum

```bash
./ci/compute_signature_checksum.sh release.keystore flo-pos-launcher
# prints e.g.  gJ8xN2qP...   (base64url, no padding)
```

Stable forever unless the keystore changes. CI recomputes it from the same keystore on every run
and **fails the build if the signed APK does not match it** (acceptance criterion #7), so the QR
can never drift from the APK.

### 1.3 Add GitHub secrets and variables

Repository → Settings → Secrets and variables → Actions.

Secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `RELEASE_KEYSTORE_PASSWORD` | store password |
| `RELEASE_KEY_ALIAS` | `flo-pos-launcher` |
| `RELEASE_KEY_PASSWORD` | key password |
| `WIFI_PASSWORD` | install-site Wi-Fi password (**required** — build fails if unset; never committed to source) |

Variables (all optional):

| Variable | Purpose | Default |
| --- | --- | --- |
| `HOME_APPS` | comma-separated home app packages, in order | `com.android.chrome,com.android.settings` |
| `WIFI_SSID` | install-site SSID | `FLO Secure` |
| `ADMIN_PIN` | 4-digit PIN override | none → app uses `5913` |

There is **no POS APK URL or checksum** — this build installs nothing.

### 1.4 Cut the first release

```bash
git tag v3.0.0 && git push origin v3.0.0
```

The workflow builds and signs the APK, verifies the certificate, publishes it to the release at the
**stable** URL
`https://github.com/<owner>/<repo>/releases/latest/download/v3poslauncher-release.apk`, and attaches
`provisioning-qr.png` to both the workflow run (artifact `provisioning`) and the release.

### 1.5 Print the QR card

Download `provisioning-qr.png` and print it ≥ 4 cm square.

> **The QR contents are plaintext** — anyone who scans it sees the Wi-Fi SSID **and password** (and
> the admin PIN, if you set one). **Treat the printed card exactly like a Wi-Fi password card.**

---

## 2. Per-device runbook (the technician)

**Precondition: the device must be factory-fresh with ZERO accounts.** Device Owner can only be
established during initial setup, before any account is added. A device already through setup must
be factory-reset first.

1. Connect power (these are countertop AIO units; keep them plugged in).
2. On the setup-wizard **welcome** screen, tap the screen **6 times** in the same spot. The QR
   scanner opens.
3. **Scan the printed QR card.**
4. The wizard connects to `FLO Secure`, downloads and installs the launcher, and grants Device
   Owner. **This is the longest wait** — 1–4 minutes on good Wi-Fi.
5. The **true-black status screen** shows each step live:

   | Step | ✓ means | Typical time |
   | --- | --- | --- |
   | Set as default home | Home chooser will never appear | instant |
   | Hide stock launcher / taskbar | No taskbar/app-drawer/suggested apps, now or after reboot | 1–2 s |
   | Save FLO Secure Wi-Fi | Network saved as persistent auto-join | instant |
   | Apply display policy | Screen stays on while powered | instant |
   | Verify home apps | Chrome & Settings confirmed present on the device | instant |

6. When the last step is green, the status screen hands back to the wizard, which exits to the
   launcher — the black home screen showing **Chrome** and **Settings**. Done — zero touches after
   the scan.

**Total expected time:** ~2–5 minutes, dominated by the launcher download. No app is auto-opened;
the terminal rests on the two-tile home until staff tap one.

### Failure states (what each means, what to do)

The status screen never hangs silently. A red **✕** pauses the sequence with the exact reason plus
**Retry** and **Skip step** buttons. Long-press the home screen any time → PIN (default **5913**) →
admin panel.

| You see | Meaning | Do |
| --- | --- | --- |
| **Hide stock launcher — could not hide any launcher package** | OEM protects its launcher (see §6) | Tap **Skip** to finish; the persistent-HOME setting still suppresses the chooser and keeps us as home. Then see OEM caveats. |
| **Verify home apps — NOT installed: com.android.chrome** | Chrome isn't on this device image | Not fatal. The tile shows "not installed". In the admin panel, remove it or point it at the browser that *is* installed (e.g. a vendor browser or WebView-based kiosk browser). We do **not** install Chrome (no APK downloads). |
| **Save FLO Secure Wi-Fi — failed** | Not Device Owner, or Wi-Fi hardware issue | Confirm the device provisioned as full Device Owner (admin panel header shows `deviceOwner=true`). If false, factory-reset and re-scan. |
| **not Device Owner** | The flow ran as a work profile, not full DO | The device was **not** factory-fresh, or an account existed. Factory-reset and start over. |

---

## 3. Revert runbook

### Revert ONE device (admin panel)

Long-press home → PIN → **Revert (rollback)**:

1. **Unhide stock launcher** — un-hides exactly the packages we hid.
2. **Clear default-home lock** — removes our persistent HOME preference.
3. **Restore screen timeout** — restores the original display settings.
4. **Release Device Owner** — `clearDeviceOwnerApp()`; the app becomes a normal, uninstallable app.

Or **⚠ Undo everything → stock device** runs 1 → 2 → 3 → 4 in order, stopping before releasing
Device Owner if an earlier step fails. After a clean run the device behaves stock and
v3poslauncher can be uninstalled normally — **no factory reset.** "Forget FLO Secure Wi-Fi" is a
separate action (not in "Undo everything") so a revert can't strand the device mid-way.

### Revert the ROLLOUT

- **Stop scanning** the v3 QR card — new devices simply aren't provisioned with it.
- **Optionally revert already-provisioned units** with "Undo everything".
- v3 never touches v1; the two don't coexist on one device.

---

## 4. Updating over time

### Change which apps appear on the home screen

Admin panel → **Home apps**: add by package name or pick from the installed-app list, remove any,
set icon size (default **125 dp**). No rebuild needed. To change the fleet default for *new*
provisions, set the `HOME_APPS` variable and reprint the QR (or just adjust each device in the
panel — the QR only seeds the initial list).

Because nothing is installed by the launcher, an app can only be shown if it is already on the
device. To put a new app on the fleet, install it by your normal means (vendor image, MDM, `adb`)
and then add its package to the home list.

### Update the launcher itself

Cut a new tag (`v3.0.1`, …). CI builds and signs it **with the same keystore** and publishes it at
the same stable URL. Reinstall from that URL (`adb install -r`, or your MDM). Because the signature
matches, Android treats it as an in-place update. **The printed QR never changes** — it references
the certificate, not the build.

---

## 5. API levels & the min-SDK floor

`minSdk = 29` (Android 10), `compileSdk = targetSdk = 35` (Android 15).

| Concern | API 29 (Android 10) | API 30+ (Android 11+) |
| --- | --- | --- |
| Modern QR provisioning | Supported | Supported |
| `GET_PROVISIONING_MODE` / `ADMIN_POLICY_COMPLIANCE` handoff | **Not used** — wizard drives `PROFILE_PROVISIONING_COMPLETE` → `PROVISIONING_SUCCESSFUL` | Used — the modern, reliable handoff |
| Wi-Fi add by DO | `WifiManager.addNetwork(WifiConfiguration)` (DO retains full rights) | `WifiManager.addNetworkPrivileged(...)` preferred, `addNetwork` fallback |
| Hide launcher (`setApplicationHidden`) | Supported | Supported |

**Why 29 is the floor.** The modern compliance flow only exists on Android 11+, but QR provisioning
and full DO privilege exist on Android 10, so the app supports both and uses the older
`PROVISIONING_SUCCESSFUL` handoff on 10. Below Android 10 the DPC must launch its own UI from the
completion broadcast, background-activity-start rules differ, and the Wi-Fi path diverges — a
fragile, poorly-testable extra path. Typical 15.6" AIO POS hardware (MicroTouch, Sunmi, Elo,
whitebox) ships Android 10/11/13, so a 29 floor costs no real devices. Android 8/9 units must be
provisioned by another method (e.g. `adb dpm set-device-owner`).

### One honest framework caveat

`clearDeviceOwnerApp()` is marked deprecated in the SDK, but it is still the **only** API by which a
Device Owner can voluntarily relinquish itself, and it still works. There is no non-deprecated
replacement; the "Release Device Owner" action uses it deliberately.

---

## 6. OEM caveats (MicroTouch / Sunmi / Elo / whitebox)

Real-world variations, not hypotheticals:

- **Suggested apps in the taskbar — use dedicated terminal mode.** Being default HOME removes the
  stock home surface and app drawer, but on Android 12L+ a taskbar still appears *inside other
  apps*, showing pinned and predicted apps. The supported fix, and the default since v3.5, is
  **lock task ("dedicated terminal") mode**: the launcher allowlists itself plus the configured
  home apps and enables `LOCK_TASK_FEATURE_HOME`, `OVERVIEW`, `GLOBAL_ACTIONS`, `NOTIFICATIONS`,
  `SYSTEM_INFO` and `KEYGUARD`. Android then suppresses the taskbar and its suggestions itself,
  while Home and Recents keep working so staff can leave any app, and the status bar and power
  button behave normally. Users can move freely between the allowlisted apps and nothing else.
  Nothing is hidden or disabled, so the stock launcher cannot be destabilised.

  Turn it off with `dedicatedTerminal=false` (QR extra), the `NO_DEDICATED_TERMINAL` repo
  variable, or the Advanced screen. Editing the app list in Launcher configuration re-applies the
  allowlist immediately, and boot re-asserts it. Revert: "Leave dedicated terminal mode", also
  part of Undo everything.

- **DO NOT hide apps to clean up the taskbar.** `setApplicationHidden` on an app that the stock
  launcher still has pinned makes Quickstep throw when it loads its hotseat — observed on an
  Android 15 Pixel Tablet as a *"Pixel Launcher keeps stopping"* crash loop that made the device
  unusable (long-pressing a taskbar icon triggered it, but it can fire unprompted). The
  `hideOtherApps` / `disableAppSuggestions` switches therefore default to **off** and are marked
  lab-only in the admin panel. Recovery, if one is ever turned on and a device crash-loops:
  `adb shell pm list packages -d` then `adb shell pm unhide <pkg>` for each, or "Unhide
  non-allowed apps" from the admin panel.

  There is also no way for a Device Owner to enable an accessibility service or a screen overlay
  automatically, so a custom Back/Home bar of our own would need a manual toggle in Settings on
  every device — which is why lock task, not a drawn bar, is the answer here.

- **Hiding the stock launcher is OFF by default (since v3.0.2) — it hung MicroTouch units at
  boot.** The v3.0.1 hide step hid *every* package that resolved HOME. That included
  `com.android.settings` (its `FallbackHome` activity is what Android shows during early boot)
  and the Quickstep launcher, which on Android 10+ is also SystemUI's recents/overview provider.
  With those hidden, the unit provisioned fine but sat on the MicroTouch logo forever after the
  first reboot. Recovery for an affected unit: `adb shell pm unhide com.android.settings` (and the
  launcher package) if adb is reachable; otherwise factory-reset from recovery and re-provision.

  Being the **persistent default HOME** (step 1) removes the stock home surface and the app
  drawer. It does **not** remove the large-screen taskbar (with "suggested apps") that Android
  12L+ draws *inside other apps*: that is rendered by the Quickstep launcher process, which is also
  SystemUI's Recents provider. To remove it, set `HIDE_TASKBAR=true` (repo variable) or the QR
  extra `hideTaskbar=true`, or use "Hide taskbar now" on the Advanced screen. The trade-off is
  fixed by Android: the Recents button becomes inert while Quickstep is hidden. Prove it on a
  disposable unit — hide, open Chrome (no taskbar?), **reboot** (comes back?) — before any store.
  `HIDE_STOCK_LAUNCHER=true` / `hideStockLauncher=true` additionally hides any other launcher
  packages. Neither switch ever touches Settings, SystemUI, or the setup wizard.

- **6-tap entry differs or is disabled.** Some modified setup wizards move or disable QR entry.
  Workarounds: look for an explicit "Set up device for work / Scan QR" option; check the vendor's
  provisioning doc for the tap count; or, if QR entry is disabled, fall back to
  `adb shell dpm set-device-owner com.flo.v3poslauncher/.admin.PosDeviceAdminReceiver` on a
  factory-fresh device, then open the app (it runs the same provisioning sequence).

- **Quickstep / launcher package names vary.** We do **not** hardcode `com.android.launcher3`. When
  hiding is enabled, the step enumerates packages that resolve HOME (except us and the protected
  system packages above). What it found, skipped, and hid is logged.

- **Chrome may be absent.** AOSP-based POS images often ship WebView but not Chrome. The launcher
  detects this (the "Verify home apps" step warns, the tile shows "not installed") and the list is
  editable. We never install Chrome — point the tile at the browser that's actually on the device.

- **Gesture vs 3-button nav.** 3-button nav shows the system nav bar; that's not the stock launcher
  and is expected. The detected navigation mode is logged.

---

## 7. Security notes

- The admin PIN gates **only** this launcher's settings screen. It is unrelated to any Android lock
  screen / keyguard, and the app applies **no** keyguard or password device policy.
- The PIN is stored salted-and-hashed on-device; the default (`5913`) lives in code but is never
  logged or displayed. A 4-digit PIN is brute-forceable by design — the attempt lockout, not the
  hash, is the real guard.
- The Wi-Fi password lives as a constant / QR field and is **never** written to the log or shown on
  screen. The QR card itself is plaintext (see §1.5).
- No analytics, no telemetry, and the launcher makes **no** network calls at all (it doesn't even
  request the INTERNET permission). The launcher APK download is done by the system's
  ManagedProvisioning, not by the app.
- The Wi-Fi password is **not** stored in committed source. It is injected at build time from the
  `WIFI_PASSWORD` CI secret (into `BuildConfig`) and supplied per-device by the QR at provision
  time, so the repository can be public without leaking it. The default admin PIN (`5913`) is a
  documented, changeable default that only gates the launcher's own settings — override it per
  fleet via the QR `adminPin` or the admin panel.

---

## 8. Bringing up a NEW unit: staged rollout

Read this before provisioning a MicroTouch you cannot afford to lose. Unit #1 was bricked by an early build and could not be recovered, because a factory reset wipes Developer options and USB debugging, and QR provisioning starts on the setup-wizard welcome screen. There is therefore no adb lifeline going INTO a QR provision. There is one available immediately after it, and taking it is the whole point of this section.

Stage one, provision with lock task OFF. Set the NO_DEDICATED_TERMINAL repository variable to true and cut a tag. The generated QR then carries dedicatedTerminal=false, so the launcher does not enter lock task on first boot and Settings stays reachable. Confirm FLO Secure is up, factory reset the unit, and scan the QR from the Vercel page rather than any previously printed card.

Stage two, take the lifeline before the first reboot. As soon as the launcher home screen appears, open Settings, tap About, tap the build number seven times, then turn on USB debugging under Developer options. Plug the unit into the technician machine and accept the RSA fingerprint prompt. Do not continue until adb devices reports the unit as device rather than unauthorized. Device Owner does not disable debugging here, and this session survives reboots.

Stage two and a half, grant the screen saver permission while the cable is still attached. Run `adb shell pm grant com.flo.v3poslauncher android.permission.WRITE_SECURE_SETTINGS`. This is the one thing the QR genuinely cannot do: the screen saver lives in Settings.Secure keys that are not on the Device Owner allowlist, and a Device Owner cannot grant itself that permission because setPermissionGrantState only covers runtime permissions. The grant survives reboots but not a reinstall. The launcher re-applies the screen saver on every start and at boot, so the SCREENSAVER provisioning step flipping from WARN to OK is the confirmation; nothing else needs re-running.

A word on why the screen saver used to appear not to work. The launcher's display policy sets STAY_ON_WHILE_PLUGGED_IN, and an always-plugged-in terminal that never naps never starts a dream, however the toggle in Settings reads. From v3.7.0 the launcher releases stay-on and sets the screen timeout to the idle delay, but only once it has verified it could actually write the screen saver settings. Without the grant the old always-on behavior is kept deliberately, so a unit never ends up with a black panel and no dream to show.

Stage three, verify the reboot. Reboot the unit three or four times and confirm it reaches the launcher every time. This is the failure mode that killed unit #1, and it is the one worth being patient about.

Stage four, enable dedicated terminal mode. Either flip it from the Advanced admin screen on the unit, or clear the NO_DEDICATED_TERMINAL variable, cut a new tag and re-provision. Reboot again and confirm Home and Recents still work on the real MicroTouch nav bar. If the taskbar or navigation misbehaves, adb is already authorized and the unit is recoverable.

A note on the CI loop. The vercel-site publish step must never put the literal skip-CI token in its commit message. GitHub treats that token on the head commit of a tag push as an instruction to skip the run, so the next tag cut from main is silently ignored and no build happens. Tags v3.6.1 and v3.6.2 were both lost this way. If a tag produces no workflow run, check the tagged commit's message first.
