#!/usr/bin/env bash
# Notarization dry run for Project Hyphen (HYP-M5-002).
#
# This script intentionally reads credentials only from the environment or an
# existing notarytool keychain profile. It never stores Apple credentials.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PRODUCT="${PRODUCT:-HyphenApp}"
APP_NAME="${APP_NAME:-Hyphen}"
VERSION="${VERSION:-0.0.1}"
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
  APP_NAME="$APP_NAME" \
  VERSION="$VERSION" \
  CONFIGURATION="$CONFIGURATION" \
  "$ROOT/packaging/macos/package-local.sh"

ARCHIVE_DIR="$ROOT/packaging/macos/build"
ARCHIVE="$ARCHIVE_DIR/$APP_NAME-macOS-$VERSION.zip"

if [ ! -f "$ARCHIVE" ]; then
  blocker "expected packaged app ZIP missing after package-local.sh: $ARCHIVE"
fi

if [ -n "$NOTARY_PROFILE" ]; then
  xcrun notarytool submit "$ARCHIVE" --wait --keychain-profile "$NOTARY_PROFILE"
else
  xcrun notarytool submit "$ARCHIVE" --wait \
    --apple-id "$APPLE_ID" \
    --team-id "$TEAM_ID" \
    --password "$APP_SPECIFIC_PASSWORD"
fi

echo "notarize-dry-run: submitted $ARCHIVE"
