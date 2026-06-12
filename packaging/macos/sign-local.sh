#!/usr/bin/env bash
# Local macOS signing dry run for Project Hyphen (HYP-M5-001).
#
# Defaults to ad-hoc signing (`SIGN_IDENTITY=-`) so maintainers can verify the
# signing path without an Apple Developer account. Developer ID signing is
# enabled by passing SIGN_IDENTITY="Developer ID Application: ...".
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PACKAGE_DIR="$ROOT/apps/macos"
PRODUCT="${PRODUCT:-HyphenApp}"
CONFIGURATION="${CONFIGURATION:-release}"
SIGN_IDENTITY="${SIGN_IDENTITY:--}"

swift build \
  --package-path "$PACKAGE_DIR" \
  -c "$CONFIGURATION" \
  --product "$PRODUCT"

BIN_DIR="$(swift build \
  --package-path "$PACKAGE_DIR" \
  -c "$CONFIGURATION" \
  --show-bin-path)"
TARGET="$BIN_DIR/$PRODUCT"

if [ ! -x "$TARGET" ]; then
  echo "sign-local: built product not found or not executable: $TARGET" >&2
  exit 1
fi

codesign_args=(--force --sign "$SIGN_IDENTITY")
if [ "$SIGN_IDENTITY" = "-" ]; then
  codesign_args+=(--timestamp=none)
else
  codesign_args+=(--options runtime --timestamp)
fi

codesign "${codesign_args[@]}" "$TARGET"
codesign --verify --strict --verbose=2 "$TARGET"
codesign -dv --verbose=4 "$TARGET" 2>&1 | sed 's/^/codesign: /'

echo "sign-local: signed and verified $TARGET"
