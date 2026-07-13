#!/usr/bin/env bash
# Local macOS ZIP/DMG packaging dry run for Project Hyphen (HYP-M5-003).
#
# Defaults to ad-hoc signing so maintainers can verify the package path without
# an Apple Developer account. Developer ID signing is enabled by passing
# SIGN_IDENTITY="Developer ID Application: ...". Notarization and stapling stay
# with HYP-M5-002 / release automation.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PACKAGE_DIR="$ROOT/apps/macos"
PRODUCT="${PRODUCT:-HyphenApp}"
APP_NAME="${APP_NAME:-Hyphen}"
BUNDLE_ID="${BUNDLE_ID:-dev.hyphen.mac}"
VERSION="${VERSION:-0.0.1}"
CONFIGURATION="${CONFIGURATION:-release}"
SIGN_IDENTITY="${SIGN_IDENTITY:--}"

BUILD_DIR="$ROOT/packaging/macos/build"
STAGING_ROOT="$BUILD_DIR/staging"
STAGING_DIR="$STAGING_ROOT/$APP_NAME-macOS"
APP_BUNDLE="$STAGING_DIR/$APP_NAME.app"
ZIP_PATH="$BUILD_DIR/$APP_NAME-macOS-$VERSION.zip"
DMG_PATH="$BUILD_DIR/$APP_NAME-macOS-$VERSION.dmg"
CHECKSUMS_PATH="$BUILD_DIR/SHA256SUMS"

SIGN_IDENTITY="$SIGN_IDENTITY" \
  PRODUCT="$PRODUCT" \
  CONFIGURATION="$CONFIGURATION" \
  "$ROOT/packaging/macos/sign-local.sh"

BIN_DIR="$(swift build \
  --package-path "$PACKAGE_DIR" \
  -c "$CONFIGURATION" \
  --show-bin-path)"
SOURCE="$BIN_DIR/$PRODUCT"

if [ ! -x "$SOURCE" ]; then
  echo "package-local: built product not found or not executable: $SOURCE" >&2
  exit 1
fi

# SwiftPM emits the HyphenApp target's resources (zh-Hans/en Localizable.strings)
# as a separate bundle next to the binary. Bundle.module resolves it from the
# app's Contents/Resources at runtime, so the packaged .app is broken — it falls
# back to raw localization keys — unless we copy the bundle in. Fail loud if it
# is missing so this regresses to red instead of shipping silently (dim 06-03).
RESOURCE_BUNDLE="$(find "$BIN_DIR" -maxdepth 1 -name 'Hyphen_*.bundle' -type d | head -n 1)"
if [ -z "$RESOURCE_BUNDLE" ] || [ ! -d "$RESOURCE_BUNDLE" ]; then
  echo "package-local: SwiftPM resource bundle (Hyphen_HyphenApp.bundle) not found in $BIN_DIR" >&2
  echo "package-local: the packaged app would launch without its localized strings" >&2
  exit 1
fi

rm -rf "$STAGING_DIR"
mkdir -p "$APP_BUNDLE/Contents/MacOS" "$APP_BUNDLE/Contents/Resources"
cp "$SOURCE" "$APP_BUNDLE/Contents/MacOS/$APP_NAME"
chmod +x "$APP_BUNDLE/Contents/MacOS/$APP_NAME"
cp -R "$RESOURCE_BUNDLE" "$APP_BUNDLE/Contents/Resources/"

cat > "$APP_BUNDLE/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDevelopmentRegion</key>
  <string>en</string>
  <key>CFBundleExecutable</key>
  <string>$APP_NAME</string>
  <key>CFBundleIdentifier</key>
  <string>$BUNDLE_ID</string>
  <key>CFBundleInfoDictionaryVersion</key>
  <string>6.0</string>
  <key>CFBundleName</key>
  <string>$APP_NAME</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>$VERSION</string>
  <key>CFBundleVersion</key>
  <string>1</string>
  <key>LSUIElement</key>
  <true/>
  <key>NSHighResolutionCapable</key>
  <true/>
</dict>
</plist>
PLIST

cat > "$STAGING_DIR/README.txt" <<README
Hyphen macOS local package

This is a local packaging dry run. It is not notarized unless a later release
step submits and staples the artifact.

Install test:
1. Copy $APP_NAME.app to /Applications or run it from this folder.
2. Open $APP_NAME.app.
3. Confirm the Hyphen menu-bar item appears.
README

codesign_args=(--force --sign "$SIGN_IDENTITY")
if [ "$SIGN_IDENTITY" = "-" ]; then
  codesign_args+=(--timestamp=none)
else
  codesign_args+=(--options runtime --timestamp)
fi
codesign "${codesign_args[@]}" "$APP_BUNDLE"
codesign --verify --strict --verbose=2 "$APP_BUNDLE"

rm -f "$ZIP_PATH" "$DMG_PATH" "$CHECKSUMS_PATH"
ditto -c -k --keepParent "$APP_BUNDLE" "$ZIP_PATH"
hdiutil create \
  -volname "$APP_NAME" \
  -srcfolder "$STAGING_DIR" \
  -ov \
  -format UDZO \
  "$DMG_PATH"

(
  cd "$BUILD_DIR"
  shasum -a 256 "$(basename "$ZIP_PATH")" "$(basename "$DMG_PATH")" > "$CHECKSUMS_PATH"
)

echo "package-local: app bundle  $APP_BUNDLE"
echo "package-local: zip         $ZIP_PATH"
echo "package-local: dmg         $DMG_PATH"
echo "package-local: checksums   $CHECKSUMS_PATH"
