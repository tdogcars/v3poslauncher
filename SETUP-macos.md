# Get it on GitHub and get the QR (macOS)

Two ways to try it on your MicroTouch:

- **Path A — the QR flow** (§1–§5): the real field-provisioning experience. Needs the APK at a
  public URL, which is why the repo goes public. The Wi-Fi password is no longer in source, so
  public is safe.
- **Path B — bench test with a cable, no hosting, no QR** (§6): fastest way to just watch the
  launcher run on a unit you have in hand. Repo can stay private.

Replace `<owner>` with your GitHub username or org (e.g. `flobiz`).

---

## Path A — the QR flow

### 1. Prerequisites (one-time)

```bash
# Java (gives you keytool):
brew install --cask temurin
keytool -version                 # confirm

# GitHub CLI:
brew install gh
gh auth login                    # GitHub.com → HTTPS → login in browser
```

### 2. Unzip and create a PUBLIC repo

```bash
cd ~/Downloads                   # wherever the zip landed
unzip -o v3poslauncher.zip
cd v3poslauncher

git init -b main
git add .
git commit -m "v3poslauncher initial import"

gh repo create <owner>/v3poslauncher --public --source=. --remote=origin --push
```

Public is required so the MicroTouch can download the APK during provisioning without logging in.
Nothing sensitive is in the source: the Wi-Fi password is injected from a secret at build time,
not committed. (The default admin PIN `5913` is a documented, changeable default — set the
`ADMIN_PIN` variable in §4 if you want a different one baked into the QR.)

### 3. Create the release keystore (ONCE, ever)

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias flo-pos-launcher \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=FLO POS Launcher, O=FLO, C=US"
```

Prompts for a **keystore password** (twice) and a **key password** (press Return to reuse it).
**Write both down and back them up now.**

> ⚠ Permanent. If it's ever lost or replaced, every printed QR stops working and provisioned
> devices can't take launcher updates. `.gitignore` already keeps `*.keystore` out of git.

### 4. Set the secrets and variables

```bash
# Keystore, base64 single-line, straight into the secret:
base64 -i release.keystore | tr -d '\n' | gh secret set RELEASE_KEYSTORE_BASE64

gh secret set RELEASE_KEYSTORE_PASSWORD     # paste keystore password (hidden)
gh secret set RELEASE_KEY_ALIAS --body "flo-pos-launcher"
gh secret set RELEASE_KEY_PASSWORD          # paste key password (hidden)
gh secret set WIFI_PASSWORD                 # paste the FLO Secure Wi-Fi password (hidden)

# Optional — defaults are fine to skip:
gh variable set HOME_APPS --body "com.android.chrome,com.android.settings"
gh variable set WIFI_SSID --body "FLO Secure"
# gh variable set ADMIN_PIN --body "1234"   # only to override the default PIN in the QR

gh secret list && gh variable list          # verify
```

### 5. Build and get the QR

```bash
git tag v3.0.0
git push origin v3.0.0
gh run watch                                # ~3–6 min
```

CI builds + signs the APK, verifies the signing cert matches your keystore (fails loudly if not),
publishes the APK to the release, and generates the QR. Then:

```bash
gh run download --name provisioning         # downloads provisioning-qr.png + provisioning.json
open provisioning-qr.png
```

Print it ≥ 4 cm square. **Treat the printed card like a Wi-Fi password card** — it contains the
Wi-Fi password (and PIN if you set one) in plaintext.

Scan it on a **factory-fresh** MicroTouch: 6 taps on the welcome screen → scan → watch the five
steps go green → it lands on the Chrome + Settings home.

---

## Path B — bench test with a cable (no hosting, no QR)

Fastest way to see the launcher run on a unit in hand. Good for a first look before you commit to
the QR flow.

### 1. Tools

```bash
brew install --cask android-platform-tools   # gives you adb
```

### 2. Get a signed APK

Easiest is to let CI build it (do §1–§5 above, or at least push a tag), then:

```bash
gh run download --name provisioning          # also includes the APK
# or grab it from the release:
gh release download v3.0.0 --pattern '*.apk'
```

(If you don't want CI yet, the APK can also be built locally with Android Studio / the Android
command-line tools — but CI is less setup.)

### 3. Factory-fresh device, USB debugging on

On the MicroTouch: factory reset, then during setup enable Developer options → **USB debugging**
(or enable it in Settings if the unit is already at the home screen but has **no accounts added** —
Device Owner requires zero accounts). Plug it into the Mac.

```bash
adb devices                                   # accept the prompt on the device
adb install -r v3poslauncher-release.apk
adb shell dpm set-device-owner com.flo.v3poslauncher/.admin.PosDeviceAdminReceiver
```

If `set-device-owner` succeeds, open the app (or reboot) — it runs the same provisioning sequence
and lands on the Chrome + Settings home. If it errors with "not allowed... accounts on the device,"
the unit isn't account-free; factory reset and retry.

> Note: on the bench path there's no QR, so the Wi-Fi password isn't supplied by a QR bundle. Set
> it once in the admin panel (long-press home → PIN `5913` → Home/Wi-Fi), or provision via the QR
> path when you're ready for the real flow.

---

## Before rollout

Prove one full provision on a throwaway factory-fresh unit and watch all five steps go green. This
is Device Owner software — a bad provision means a factory reset, so verify once before any store
terminal.
