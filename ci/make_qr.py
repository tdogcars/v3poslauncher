#!/usr/bin/env python3
"""
Generate the Device-Owner provisioning QR for v3poslauncher.

The QR encodes a single JSON object that Android's setup wizard reads after the 6-tap entry on a
factory-fresh device. It names the admin component, where to download the LAUNCHER APK, and how to
verify it — plus optional configuration (home app list, admin PIN) in the admin-extras bundle.

This build installs NO other APK: the launcher only shows already-installed apps (Chrome,
Settings by default), so there is no POS URL or POS checksum in the payload.

CRITICAL — the SIGNATURE checksum, not the package checksum:
  PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM is the SHA-256 of the SIGNING CERTIFICATE,
  base64url (no padding). Because it is tied to the keystore and NOT to a specific build, the SAME
  printed QR stays valid across every future launcher release signed with that keystore.

Usage:
  python3 make_qr.py \
      --download-url        https://github.com/OWNER/REPO/releases/latest/download/v3poslauncher-release.apk \
      --signature-checksum  <base64url sha-256 of signing cert> \
      [--home-apps "com.android.chrome,com.android.settings"] \
      --wifi-password       "<install-site Wi-Fi password>" \
      [--wifi-ssid "FLO Secure"] \
      [--admin-pin 5913] [--hide-stock-launcher] \
      [--out provisioning-qr.png] [--json-out provisioning.json]

Only qrcode[pil] is required (pip install "qrcode[pil]").
"""
import argparse
import json
import sys

# The admin component MUST match AndroidManifest: <package>/<receiver class>
ADMIN_COMPONENT = "com.flo.v3poslauncher/com.flo.v3poslauncher.admin.PosDeviceAdminReceiver"


def build_provisioning_dict(a) -> dict:
    # Off by default since v3.0.2: hiding launcher packages hung MicroTouch units at boot.
    admin_extras = {
        "hideStockLauncher": "true" if a.hide_stock_launcher else "false",
        "hideTaskbar": "true" if a.hide_taskbar else "false",
        # Dedicated-terminal (lock task) mode is what suppresses the taskbar. Default ON.
        "dedicatedTerminal": "false" if a.no_dedicated_terminal else "true",
        # App hiding is UNSAFE (crashes the stock launcher) — default OFF, lab use only.
        "hideOtherApps": "true" if a.hide_other_apps else "false",
        "disableAppSuggestions": "true" if a.disable_app_suggestions else "false",
    }
    if a.home_apps:
        admin_extras["homeApps"] = a.home_apps
    if a.admin_pin:
        admin_extras["adminPin"] = a.admin_pin
    if a.wifi_ssid:
        admin_extras["wifiSsid"] = a.wifi_ssid
    if a.wifi_password:
        admin_extras["wifiPassword"] = a.wifi_password

    return {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": ADMIN_COMPONENT,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": a.download_url,
        # Signature (cert) checksum — stable across releases. base64url, no padding.
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": a.signature_checksum,
        # We hide the stock launcher/taskbar ourselves, selectively, so keep all system apps
        # enabled at provisioning time (Chrome, Settings, etc. must remain available).
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
        "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": False,
        # Company-standard install-site Wi-Fi so provisioning can reach the launcher download.
        "android.app.extra.PROVISIONING_WIFI_SSID": a.wifi_ssid,
        "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE": "WPA",
        "android.app.extra.PROVISIONING_WIFI_PASSWORD": a.wifi_password,
        "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": admin_extras,
    }


def main() -> int:
    p = argparse.ArgumentParser(description="Generate v3poslauncher provisioning QR")
    p.add_argument("--download-url", required=True)
    p.add_argument("--signature-checksum", required=True,
                   help="base64url (no padding) SHA-256 of the signing certificate")
    p.add_argument("--home-apps", default="",
                   help='comma-separated ordered package names, e.g. "com.android.chrome,com.android.settings"')
    p.add_argument("--wifi-ssid", default="FLO Secure")
    p.add_argument("--wifi-password", default="", help="install-site Wi-Fi password (required)")
    p.add_argument("--admin-pin", default="", help="optional 4-digit override of the default PIN")
    p.add_argument("--no-dedicated-terminal", action="store_true",
                   help="do NOT put the device in lock task (dedicated terminal) mode; the stock "
                        "taskbar with suggested apps then stays visible. Default: enabled")
    p.add_argument("--hide-other-apps", action="store_true",
                   help="LAB ONLY, unsafe: hide apps absent from the home list (crashes Quickstep)")
    p.add_argument("--disable-app-suggestions", action="store_true",
                   help="LAB ONLY, unsafe: hide the app-prediction service")
    p.add_argument("--hide-taskbar", action="store_true",
                   help="ALSO hide the Quickstep taskbar/recents provider (removes the large-screen "
                        "taskbar inside apps; Recents button becomes inert). Default: off")
    p.add_argument("--hide-stock-launcher", action="store_true",
                   help="ALSO hide the stock launcher package (default: off; being default HOME is enough)")
    p.add_argument("--out", default="provisioning-qr.png")
    p.add_argument("--json-out", default="provisioning.json")
    a = p.parse_args()

    if a.admin_pin and (len(a.admin_pin) != 4 or not a.admin_pin.isdigit()):
        print("ERROR: --admin-pin must be exactly 4 digits", file=sys.stderr)
        return 2
    if not a.wifi_password:
        print("ERROR: --wifi-password is required (the device needs it to join the install-site "
              "network during provisioning).", file=sys.stderr)
        return 2

    data = build_provisioning_dict(a)
    payload = json.dumps(data, separators=(",", ":"), ensure_ascii=False)

    with open(a.json_out, "w", encoding="utf-8") as f:
        f.write(payload)

    # Redacted echo for the CI log (never print the Wi-Fi password or PIN).
    redacted = json.loads(payload)
    redacted["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = "***REDACTED***"
    extras = redacted["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"]
    if "adminPin" in extras:
        extras["adminPin"] = "****"
    if "wifiPassword" in extras:
        extras["wifiPassword"] = "***REDACTED***"
    print("Provisioning payload (redacted):")
    print(json.dumps(redacted, indent=2))
    print(f"\nPayload length: {len(payload)} bytes")

    try:
        import qrcode
        from qrcode.constants import ERROR_CORRECT_M
    except ImportError:
        print('ERROR: qrcode not installed. Run: pip install "qrcode[pil]"', file=sys.stderr)
        return 3

    qr = qrcode.QRCode(version=None, error_correction=ERROR_CORRECT_M, box_size=10, border=4)
    qr.add_data(payload)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white")
    img.save(a.out)
    print(f"Wrote {a.out} (QR version {qr.version}) and {a.json_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
