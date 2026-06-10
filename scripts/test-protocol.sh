#!/usr/bin/env bash
# Protocol schema + test-vector checks (HYP-M2-001, grows with M2-002/004).
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/validate_protocol_fixtures.py
