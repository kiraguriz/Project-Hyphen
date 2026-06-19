#!/usr/bin/env bash
# Project Hyphen repo checks (HYP-M0-005).
# Runs every check that exists today; prints SKIP with the blocking roadmap
# task for platform checks that cannot run until that code lands.
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0
strict=0

for arg in "$@"; do
  case "$arg" in
    --strict) strict=1 ;;
    *)
      echo "usage: $0 [--strict]" >&2
      exit 2
      ;;
  esac
done

skip_check() {
  echo "  SKIP: $*"
  if [ "$strict" -eq 1 ]; then
    fail=1
  fi
}

# --- [1/5] markdown relative links -----------------------------------------
echo "[1/5] markdown relative links"
broken=0
while IFS= read -r -d '' f; do
  dir=$(dirname "$f")
  while IFS= read -r link; do
    [ -z "$link" ] && continue
    case "$link" in
      http://*|https://*|mailto:*|'#'*) continue ;;
    esac
    target=${link%%#*}
    [ -z "$target" ] && continue
    if [ ! -e "$dir/$target" ]; then
      echo "  BROKEN: $f -> $link"
      broken=1
    fi
  done < <(grep -oE '\]\([^)]+\)' "$f" 2>/dev/null | sed -E 's/^\]\(//; s/\)$//' || true)
done < <(find . -name '*.md' -not -path './.git/*' -print0)
if [ "$broken" -eq 0 ]; then echo "  OK"; else fail=1; fi

# --- [2/5] secret patterns ---------------------------------------------------
echo "[2/5] secret patterns"
secret_hits=$(grep -rInE \
  -e 'BEGIN [A-Z ]*PRIVATE KEY' \
  -e 'AKIA[0-9A-Z]{16}' \
  -e 'ghp_[A-Za-z0-9]{36}' \
  -e 'xox[baprs]-[A-Za-z0-9-]{10,}' \
  -e 'AIza[0-9A-Za-z_-]{35}' \
  --exclude-dir=.git --exclude=check.sh . || true)
if [ -z "$secret_hits" ]; then
  echo "  OK"
else
  echo "$secret_hits" | sed 's/^/  HIT: /'
  fail=1
fi

# --- [3/5] android ----------------------------------------------------------
echo "[3/5] android unit tests"
if [ -x apps/android/gradlew ]; then
  (cd apps/android && ./gradlew --quiet test) || fail=1
else
  skip_check "no Gradle project yet (pending HYP-M1-001)"
fi

# --- [4/5] macos ------------------------------------------------------------
echo "[4/5] macos build/tests"
if compgen -G 'apps/macos/*.xcodeproj' >/dev/null || [ -f apps/macos/Package.swift ]; then
  if [ "$(uname -s)" != "Darwin" ]; then
    # The macOS app targets Apple-only frameworks (AppKit/Security/Network/
    # CryptoKit); a Linux swift toolchain cannot build it even though `swift`
    # is on PATH. macOS dev machines and macOS CI cover this step.
    skip_check "macOS app requires Apple frameworks; not buildable on $(uname -s)"
  elif command -v xcodebuild >/dev/null 2>&1 || command -v swift >/dev/null 2>&1; then
    if [ -f apps/macos/Package.swift ]; then
      (cd apps/macos && swift test) || fail=1
    else
      (cd apps/macos && xcodebuild build -quiet) || fail=1
    fi
  else
    skip_check "Xcode toolchain not available on this machine"
  fi
else
  skip_check "no macOS project yet (pending HYP-M1-010)"
fi

# --- [5/5] protocol schemas ---------------------------------------------------
echo "[5/5] protocol schemas and test vectors"
if [ -x scripts/test-protocol.sh ]; then
  ./scripts/test-protocol.sh || fail=1
else
  skip_check "scripts/test-protocol.sh missing (pending HYP-M2-001)"
fi

# --- summary -----------------------------------------------------------------
if [ "$fail" -eq 0 ]; then
  if [ "$strict" -eq 1 ]; then
    echo "check.sh: strict checks passed"
  else
    echo "check.sh: all available checks passed (platform checks above may be SKIPped until M1/M2 land)"
  fi
else
  echo "check.sh: FAILURES above" >&2
fi
exit "$fail"
