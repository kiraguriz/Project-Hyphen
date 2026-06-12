# HYP-M3-015 1GB Transfer Resume Test Log

Status: scaffold ready; execution blocked until a paired Android/macOS session can run an active file transfer.

## Fixture

Create the deterministic 1 GiB source file on the sending machine:

```bash
python3 scripts/create_large_transfer_fixture.py /tmp/hyphen-transfer-1gib.bin
```

The command writes `/tmp/hyphen-transfer-1gib.bin` plus `/tmp/hyphen-transfer-1gib.bin.sha256`. Keep the printed `sha256` with the test record and compare it with the received file after transfer.

For script smoke tests only, use a small size:

```bash
python3 scripts/create_large_transfer_fixture.py --size-bytes 4096 --force /tmp/hyphen-transfer-smoke.bin
```

## Preconditions

| Item | Required value |
|---|---|
| Pairing | Mac and Android already trusted through SAS |
| Transport | Active local LAN session, no cloud relay |
| Transfer capability | `transfer.v1` negotiated with resume support |
| Diagnostics | Local-only diagnostics allowed; no payload/file contents exported |
| Storage | Sender and receiver each have at least 2 GiB free |

## Procedure

1. Start the paired Mac and Android apps on the same LAN.
2. Start a transfer of `/tmp/hyphen-transfer-1gib.bin`.
3. Wait until progress is between 20% and 80%.
4. Interrupt the connection once by toggling Wi-Fi, changing LAN reachability, or sleeping the Mac while keeping both app processes alive.
5. Restore the same LAN session and allow reconnect/resume.
6. Confirm the transfer resumes from the last checkpoint instead of restarting at chunk 0.
7. Let the transfer finish.
8. Compute SHA-256 on the received file and compare it with the fixture sidecar.
9. Export/preview diagnostics and confirm no file contents or full local paths appear.

Do not kill either app for this M3 test. Current checkpoints are in-memory; persistent checkpoints across app restarts are later hardening work.

## Pass Criteria

| Criterion | Expected result |
|---|---|
| Resume | Receiver checkpoint advances after interruption; resumed send starts at `nextChunkIndex > 0` |
| Integrity | Received SHA-256 equals source SHA-256 |
| UX | Progress remains visible and final success/failure is explicit |
| Recovery | Reconnect uses local session recovery or shows a clear failure reason |
| Privacy | Diagnostics omit file contents and full local paths |

## Run Record

| Field | Value |
|---|---|
| Date/time | TODO |
| Tester | TODO |
| Direction | TODO: Android to Mac / Mac to Android |
| Android device / OS | TODO |
| macOS device / version | TODO |
| Network | TODO |
| Fixture path | `/tmp/hyphen-transfer-1gib.bin` |
| Fixture SHA-256 | TODO |
| Chunk size | TODO |
| Interruption method | TODO |
| Progress at interruption | TODO |
| Resume checkpoint observed | TODO |
| Received SHA-256 | TODO |
| Result | TODO: pass / fail / blocked |
| Notes / failure code | TODO |
