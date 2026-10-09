#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
apk="${1:?Usage: verify-apk-signature.sh path/to/app.apk}"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK is not configured}}"
mapfile -t signers < <(find "$sdk/build-tools" -maxdepth 2 -type f -name apksigner | sort -V)
if (( ${#signers[@]} == 0 )); then
  echo "apksigner was not found" >&2
  exit 1
fi
expected=$(keytool -exportcert -keystore app/debug.keystore -storepass android -alias androiddebugkey 2>/dev/null | sha256sum | awk '{print $1}')
certificate_output=$("${signers[-1]}" verify --print-certs "$apk")
# Signer labels vary by build-tools version (e.g. "Signer #1" or "V2 Signer").
actual=$(printf '%s\n' "$certificate_output" | sed -n 's/^.*certificate SHA-256 digest: //p' | tr '[:upper:]' '[:lower:]' | sort -u)
if [[ -z "$actual" ]]; then
  echo "Could not read APK signing certificate from apksigner output" >&2
  printf '%s\n' "$certificate_output" >&2
  exit 1
fi
if [[ "$actual" != "$expected" ]]; then
  echo "APK certificate differs from the shared signing certificate (expected $expected, actual $actual)" >&2
  exit 1
fi
echo "Verified APK signing certificate SHA-256: $actual"
