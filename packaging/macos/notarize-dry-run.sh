#!/usr/bin/env bash
# Notarization dry run for Project Hyphen (HYP-M5-002).
#
# This script intentionally reads credentials only from the environment or an
# existing notarytool keychain profile. It never stores Apple credentials.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PACKAGE_DIR="$ROOT/apps/macos"
PRODUCT="${PRODUCT:-HyphenApp}"
CONFIGURATION="${CONFIGURATION:-release}"
SIGN_IDENTITY="${SIGN_IDENTITY:-}"
NOTARY_PROFILE="${NOTARY_PROFILE:-}"
APPLE_ID="${APPLE_ID:-}"
TEAM_ID="${TEAM_ID:-}"
APP_SPECIFIC_PASSWORD="${APP_SPECIFIC_PASSWORD:-}"

blocker() {
  echo "notarize-dry-run: BLOCKED: $*" >&2
  exit 2
}

command -v xcrun >/dev/null 2>&1 || blocker "xcrun is unavailable; install Xcode command line tools"
xcrun notarytool --help >/dev/null 2>&1 || blocker "xcrun notarytool is unavailable"

if [ -z "$SIGN_IDENTITY" ] || [ "$SIGN_IDENTITY" = "-" ]; then
  blocker "set SIGN_IDENTITY to an installed Developer ID Application certificate"
fi

if [ -z "$NOTARY_PROFILE" ]; then
  if [ -z "$APPLE_ID" ] || [ -z "$TEAM_ID" ] || [ -z "$APP_SPECIFIC_PASSWORD" ]; then
    blocker "set NOTARY_PROFILE or APPLE_ID, TEAM_ID, and APP_SPECIFIC_PASSWORD"
  fi
fi

SIGN_IDENTITY="$SIGN_IDENTITY" \
  PRODUCT="$PRODUCT" \
  CONFIGURATION="$CONFIGURATION" \
  "$ROOT/packaging/macos/sign-local.sh"

BIN_DIR="$(swift build \
  --package-path "$PACKAGE_DIR" \
  -c "$CONFIGURATION" \
  --show-bin-path)"
TARGET="$BIN_DIR/$PRODUCT"
ARCHIVE_DIR="$ROOT/packaging/macos/build"
ARCHIVE="$ARCHIVE_DIR/$PRODUCT-notary-dry-run.zip"

mkdir -p "$ARCHIVE_DIR"
rm -f "$ARCHIVE"
ditto -c -k --keepParent "$TARGET" "$ARCHIVE"

if [ -n "$NOTARY_PROFILE" ]; then
  xcrun notarytool submit "$ARCHIVE" --wait --keychain-profile "$NOTARY_PROFILE"
else
  xcrun notarytool submit "$ARCHIVE" --wait \
    --apple-id "$APPLE_ID" \
    --team-id "$TEAM_ID" \
    --password "$APP_SPECIFIC_PASSWORD"
fi

echo "notarize-dry-run: submitted $ARCHIVE"
