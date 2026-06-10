#!/usr/bin/env python3
"""Recompute pairing transcript + SAS vectors from first principles
(HYP-M2-004, protocol v0 §5.3) and compare against the committed
expectations. tamperCases must MISMATCH — that proves this verifier
actually detects wrong values.
"""

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LABEL = b"hyphen-pair-v0"
NONCE_LEN, FP_LEN = 16, 32


def compute(case):
    nonce = bytes.fromhex(case["nonceHex"])
    mac_fp = bytes.fromhex(case["macSpkiFpHex"])
    android_fp = bytes.fromhex(case["androidSpkiFpHex"])
    assert len(nonce) == NONCE_LEN, f"{case['name']}: nonce must be {NONCE_LEN} bytes"
    assert len(mac_fp) == FP_LEN and len(android_fp) == FP_LEN, f"{case['name']}: fp must be {FP_LEN} bytes"

    transcript = LABEL + nonce + mac_fp + android_fp + case["protocolVersion"].encode("utf-8")
    digest = hashlib.sha256(transcript).digest()
    sas = "%06d" % (int.from_bytes(digest[:8], "big") % 10**6)
    return digest.hex(), sas


def matches(case):
    digest_hex, sas = compute(case)
    return digest_hex == case["expectedTranscriptHashHex"] and sas == case["expectedSas"]


def main():
    data = json.loads(
        (ROOT / "protocol" / "test-vectors" / "pairing" / "sas-vectors.json").read_text()
    )
    failures = 0

    for case in data["cases"]:
        if matches(case):
            print(f"  OK   pairing/{case['name']}")
        else:
            digest_hex, sas = compute(case)
            print(f"  FAIL pairing/{case['name']}: computed hash={digest_hex} sas={sas}")
            failures += 1

    for case in data.get("tamperCases", []):
        if matches(case):
            print(f"  FAIL pairing/tamper/{case['name']}: matched but must mismatch")
            failures += 1
        else:
            print(f"  OK   pairing/tamper/{case['name']} correctly detected as mismatch")

    sas_values = [c["expectedSas"] for c in data["cases"]]
    if not any(s.startswith("0") for s in sas_values):
        print("  FAIL pairing: vector set must include a leading-zero SAS (padding pin)")
        failures += 1

    if failures:
        print(f"pairing vectors: {failures} failure(s)", file=sys.stderr)
        return 1
    print(f"  pairing vectors: {len(data['cases'])} cases + {len(data.get('tamperCases', []))} tamper checks OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
