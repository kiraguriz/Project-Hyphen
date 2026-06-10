#!/usr/bin/env bash
# Protocol schema + test-vector checks (HYP-M2-001/002/003/004).
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/validate_protocol_fixtures.py
python3 scripts/lint_error_registry.py
python3 scripts/verify_pairing_vectors.py
