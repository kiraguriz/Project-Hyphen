#!/usr/bin/env bash
# Android release build dry run for Project Hyphen (HYP-M5-004).
set -euo pipefail
shopt -s nullglob

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANDROID_DIR="$ROOT/apps/android"
OUT_DIR="$ROOT/packaging/android-play/build"

signing_values=(
  "${HYPHEN_ANDROID_KEYSTORE:-}"
  "${HYPHEN_ANDROID_KEY_ALIAS:-}"
  "${HYPHEN_ANDROID_KEYSTORE_PASSWORD:-}"
  "${HYPHEN_ANDROID_KEY_PASSWORD:-}"
)
signing_set=0
signing_missing=0
for value in "${signing_values[@]}"; do
  if [ -n "$value" ]; then
    signing_set=$((signing_set + 1))
  else
    signing_missing=$((signing_missing + 1))
  fi
done

if [ "$signing_set" -gt 0 ] && [ "$signing_missing" -gt 0 ]; then
  echo "build-release: BLOCKED: set all HYPHEN_ANDROID_* signing variables, or none" >&2
  exit 2
fi

if [ "$signing_set" -eq 0 ]; then
  echo "build-release: unsigned dry run; release keystore environment is not configured" >&2
fi

(cd "$ANDROID_DIR" && ./gradlew --no-daemon :app:assembleRelease :app:bundleRelease)

mkdir -p "$OUT_DIR"
rm -f "$OUT_DIR"/*.apk "$OUT_DIR"/*.aab "$OUT_DIR"/SHA256SUMS

artifacts=(
  "$ANDROID_DIR"/app/build/outputs/apk/release/*.apk
  "$ANDROID_DIR"/app/build/outputs/bundle/release/*.aab
)

if [ "${#artifacts[@]}" -eq 0 ]; then
  echo "build-release: no release artifacts found" >&2
  exit 1
fi

for artifact in "${artifacts[@]}"; do
  cp "$artifact" "$OUT_DIR/"
done

(cd "$OUT_DIR" && shasum -a 256 ./* > SHA256SUMS)

echo "build-release: wrote artifacts to $OUT_DIR"
if [ "$signing_set" -eq 0 ]; then
  echo "build-release: BLOCKED: release signing key not configured; artifacts are not Play-ready" >&2
fi
