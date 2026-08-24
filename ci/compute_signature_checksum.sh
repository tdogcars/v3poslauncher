#!/usr/bin/env bash
#
# Derive PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM from the release keystore.
#
# This is the SHA-256 of the signing certificate, base64url-encoded WITHOUT padding — exactly
# the form Android's ManagedProvisioning expects. It is tied to the KEYSTORE, so it is stable
# across every APK you ever sign with that keystore. Put its value in the QR; it never changes
# unless you change the keystore (which you must never do — see PROVISIONING.md).
#
# Usage:
#   ./compute_signature_checksum.sh release.keystore my-key-alias
# You will be prompted for the keystore password.
#
set -euo pipefail

KEYSTORE="${1:?usage: compute_signature_checksum.sh <keystore> <alias>}"
ALIAS="${2:?usage: compute_signature_checksum.sh <keystore> <alias>}"

# Export the certificate (DER), hash it, base64url-encode, strip padding.
CKSUM="$(keytool -exportcert -keystore "$KEYSTORE" -alias "$ALIAS" \
    | openssl dgst -sha256 -binary \
    | openssl base64 \
    | tr '+/' '-_' \
    | tr -d '=')"

echo "$CKSUM"
