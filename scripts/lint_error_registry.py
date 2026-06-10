#!/usr/bin/env python3
"""Error-code registry lint (HYP-M2-003).

The enum in protocol/schema/error.schema.json is the normative registry.
This lint enforces: category/kebab-case format with one of the five known
categories, every category populated (the row's acceptance criterion),
and no duplicates.
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATEGORIES = {"protocol", "transport", "trust", "permission", "plugin"}
PATTERN = re.compile(r"^(protocol|transport|trust|permission|plugin)/[a-z][a-z0-9]*(-[a-z0-9]+)*$")


def main():
    schema = json.loads((ROOT / "protocol" / "schema" / "error.schema.json").read_text())
    codes = schema["properties"]["code"]["enum"]

    failures = []
    if len(codes) != len(set(codes)):
        failures.append("duplicate codes in registry")
    for code in codes:
        if not PATTERN.fullmatch(code):
            failures.append(f"bad code format: {code}")
    missing = CATEGORIES - {code.split("/")[0] for code in codes}
    if missing:
        failures.append(f"categories without codes: {sorted(missing)}")

    if failures:
        print("error registry lint FAILED:", file=sys.stderr)
        for f in failures:
            print(f"  {f}", file=sys.stderr)
        return 1
    print(f"  error registry lint: {len(codes)} codes across {len(CATEGORIES)} categories OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
