#!/usr/bin/env python3
"""Create a deterministic large file for manual Hyphen transfer testing."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


DEFAULT_SIZE_BYTES = 1024 * 1024 * 1024
DEFAULT_BLOCK_BYTES = 1024 * 1024


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create a deterministic transfer fixture and .sha256 sidecar."
    )
    parser.add_argument(
        "output",
        type=Path,
        help="Output file path, for example /tmp/hyphen-transfer-1gib.bin.",
    )
    parser.add_argument(
        "--size-bytes",
        type=int,
        default=DEFAULT_SIZE_BYTES,
        help="Fixture size in bytes. Defaults to 1 GiB.",
    )
    parser.add_argument(
        "--block-bytes",
        type=int,
        default=DEFAULT_BLOCK_BYTES,
        help="Write block size in bytes. Defaults to 1 MiB.",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite the output file and checksum sidecar if they already exist.",
    )
    return parser.parse_args()


def deterministic_block(size: int) -> bytes:
    return bytes(index % 251 for index in range(size))


def main() -> int:
    args = parse_args()
    if args.size_bytes <= 0:
        raise SystemExit("--size-bytes must be positive")
    if args.block_bytes <= 0:
        raise SystemExit("--block-bytes must be positive")

    output: Path = args.output
    checksum_output = output.with_name(f"{output.name}.sha256")
    if not args.force:
        for path in (output, checksum_output):
            if path.exists():
                raise SystemExit(f"{path} already exists; pass --force to overwrite")

    output.parent.mkdir(parents=True, exist_ok=True)
    pattern = deterministic_block(args.block_bytes)
    digest = hashlib.sha256()
    remaining = args.size_bytes

    with output.open("wb") as handle:
        while remaining > 0:
            chunk = pattern[: min(args.block_bytes, remaining)]
            handle.write(chunk)
            digest.update(chunk)
            remaining -= len(chunk)

    checksum = digest.hexdigest()
    checksum_output.write_text(f"{checksum}  {output.name}\n", encoding="utf-8")
    print(f"file={output}")
    print(f"sizeBytes={args.size_bytes}")
    print(f"sha256={checksum}")
    print(f"sha256File={checksum_output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
