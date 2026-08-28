# Continuation prompt — v3poslauncher (paste into a new session; attach v3poslauncher.zip)

You are helping me (Tyler) finish deploying an Android **Device Owner launcher** that provisions
point-of-sale terminals from a **single QR scan**. The full project already exists — it's in the
attached `v3poslauncher.zip`. Your job is to help me **build + sign the APK, host it on Vercel, and
produce the scannable QR**, then guide me command-by-command (I'm on macOS). Do not rewrite the app
unless I ask; it's built and type-checked. Read this whole brief first.

## What the product does (final, agreed design)

A technician powers on a **factory-fresh** 15.6" AIO POS terminal (brand: **MicroTouch**), taps the
setup-wizard welcome screen 6×, and scans one printed QR. The device then **auto-provisions**: joins
Wi-Fi, downloads + installs this launcher, grants it Device Owner, and the launcher makes itself the
home screen, hides the stock launcher/taskbar, saves the Wi-Fi network, keeps the screen on, and
lands on a true-black home showing **Chrome + Settings**. Zero touches after the scan.

Key decisions already baked in:
- **Installs NO other APK.** The launcher only shows apps already on the device. The home grid is
  **Chrome (`com.android.chrome`) + Settings (`com.android.settings`)**, admin-editable, icon size
  **125 dp**. Boot lands on this grid; nothing auto-opens.
- **No lock-task/kiosk mode.** Home and Recents stay functional.
- Everything is reversible from a PIN-gated admin panel, ending in `clearDeviceOwnerApp()` (no
  factory reset). Default admin PIN **5913** (documented, changeable; gates only the launcher's
  settings, not any Android keyguard).
- Wi-Fi: SSID **`FLO Secure`**, WPA2. The password is **not** in source — it's injected at build
  time from the CI `WIFI_PASSWORD` secret and supplied per-device by the QR. So the GitHub repo can
  be private or public safely.

## Technical facts

- `applicationId` = `com.flo.v3poslauncher`
- Admin component (must match the QR): `com.flo.v3poslauncher/com.flo.v3poslauncher.admin.PosDeviceAdminReceiver`
- `minSdk 29` (Android 10), `compileSdk/targetSdk 35`. Gradle 8.9 (wrapper included), AGP 8.7.3,
  Kotlin 2.0.21, JDK 17.
- Provisioning runs 5 steps: set default HOME → hide stock launcher/taskbar → save Wi-Fi → display
  policy (stay-on) → verify home apps. Handles Android 11+ (`GET_PROVISIONING_MODE` +
  `ADMIN_POLICY_COMPLIANCE`) and Android 10 (`PROVISIONING_SUCCESSFUL`).
- CI: `.github/workflows/release.yml` builds + **signs** the APK, **verifies the signing cert
  matches the keystore** (acceptance criterion), publishes a GitHub Release, and generates
  `provisioning-qr.png`. A repo variable **`DOWNLOAD_URL`** overrides where the QR tells the device
  to fetch the APK (set it to the Vercel URL).
- `ci/make_qr.py` builds the QR JSON; `ci/compute_signature_checksum.sh` derives the cert checksum.
- `vercel-site/` is a ready-to-deploy static host: `index.html` (technician "scan to set up" page),
  `vercel.json` (serves the `.apk` with the right headers), `README-DEPLOY.md`. Drop the built
  `v3poslauncher-release.apk` and `provisioning-qr.png` into it and deploy.

## Hosting plan

- **GitHub repo (private is fine):** source + GitHub Actions builds & signs the APK.
- **Vercel (public):** hosts the signed APK at a stable URL and serves the technician page.
- Chosen Vercel project name: **`flo-pos-setup`** →
  APK URL `https://flo-pos-setup.vercel.app/v3poslauncher-release.apk`
  (this is what `DOWNLOAD_URL` must equal; change both together if the name changes).

## Where I am right now

- Repo created on GitHub; project committed locally and (being) pushed.
- Not yet done: create keystore, set CI secrets, set `DOWNLOAD_URL`, push a tag to build, deploy to
  Vercel, print QR, test on a MicroTouch.

## Remaining steps — walk me through these on macOS, one at a time, checking output

**Phase A — build + sign via GitHub Actions** (run in `~/Downloads/v3poslauncher`):
1. `keytool -genkeypair -v -keystore release.keystore -alias flo-pos-launcher -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=FLO POS Launcher, O=FLO, C=US"`  (needs a JDK: `brew install --cask temurin`)
2. Set secrets with `gh` (`brew install gh`, `gh auth login`):
   - `base64 -i release.keystore | tr -d '\n' | gh secret set RELEASE_KEYSTORE_BASE64`
   - `gh secret set RELEASE_KEYSTORE_PASSWORD`  (paste)
   - `gh secret set RELEASE_KEY_ALIAS --body "flo-pos-launcher"`
   - `gh secret set RELEASE_KEY_PASSWORD`  (paste)
   - `gh secret set WIFI_PASSWORD`  (paste the FLO Secure password)
3. `gh variable set DOWNLOAD_URL --body "https://flo-pos-setup.vercel.app/v3poslauncher-release.apk"`
4. `git add . && git commit -m "wip" && git push` then `git tag v3.0.0 && git push origin v3.0.0`, then `gh run watch`
5. `gh run download --name provisioning`  → yields `v3poslauncher-release.apk` + `provisioning-qr.png`

**Phase B — host on Vercel:**
6. Copy the two downloaded files into `vercel-site/`.
7. `npm i -g vercel`, `vercel login`, then `cd vercel-site && vercel deploy --prod --yes --name flo-pos-setup`

**Phase C — test:**
8. Print `provisioning-qr.png`. On a **factory-fresh, account-free** MicroTouch: 6 taps on welcome →
   scan → watch 5 steps go green → lands on Chrome + Settings.

## Critical constraints / gotchas to keep me honest about

- **Device Owner needs a factory-fresh device with ZERO accounts.** If any account exists, the scan
  provisions a work profile, not full Device Owner, and it won't work. Factory reset and retry.
- **Keystore is permanent.** The QR is bound to its signing certificate. If it's ever lost or
  replaced, every printed QR stops working and provisioned devices can't take updates. Back it up.
- **The QR is plaintext** — it contains the Wi-Fi password. Treat printed cards like Wi-Fi password
  cards.
- **MicroTouch taskbar caveat (unverified on hardware):** the launcher hides the stock launcher to
  remove the taskbar/suggested-apps. On some large-screen builds the taskbar is drawn by SystemUI,
  not the launcher, and can't be hidden via `setApplicationHidden` — must be verified on a real
  MicroTouch; if it persists, disable it in the device's own display/navigation settings.
- **This is Device Owner software** — prove one full provision on a throwaway factory-fresh unit
  before any store terminal.

## Environment reality (don't promise these)

- The assistant sandbox **cannot build the Android APK** (no Android SDK; Google/Maven blocked) and
  **cannot host files or control my screen**. The APK must be built by GitHub Actions (above) or by
  me in Android Studio; Vercel hosts it; I run the terminal/browser steps. Guide me; don't claim to
  do them for me.

## Acceptance criteria (state pass/fail as we verify on hardware)

1. Factory-fresh + scan reaches the Chrome/Settings home with zero touches after the scan.
2. No home-app chooser dialog ever appears.
3. No stock taskbar/app-drawer/suggested-apps visible after provisioning, including after reboot
   (subject to the MicroTouch SystemUI caveat).
4. Reboot lands back on the launcher home grid.
5. Admin panel "Undo everything" returns the device to stock and makes the app uninstallable — no
   factory reset.
6. The QR's signature checksum matches the release keystore (CI verifies this automatically).
