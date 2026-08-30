# Android Foreground Service Declaration Draft

Status: draft for HYP-M5-006. This is not a Play Console submission and must not be treated as Play-ready until release signing, policy review, screenshots, and manual compatibility evidence are complete.

## Current Implementation Boundary

The Android manifest declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and `POST_NOTIFICATIONS`. `HyphenConnectionService` and `HyphenTransferService` are separate so a `dataSync` transfer can terminate without removing an opted-in `connectedDevice` link. The host implementation is present; this draft still lacks physical-device screenshots, timeout/process evidence, Play Console review, and release proof.

## Declaration Summary

| FGS type | Intended use | User trigger | Stop condition | Why background alternatives are insufficient |
|---|---|---|---|---|
| `connectedDevice` | Maintain a user-visible local LAN TLS session with the trusted Mac for notification continuity, text/link actions, transfer control, and reconnect state. | After pairing, the user explicitly enables the companion link. File selection also explains and enables this visible link before Android leaves the Activity for the picker. | User disconnects, revokes trust, or notification visibility is lost. Without the opt-in, leaving the Activity pauses TLS. | The connection is an ongoing companion-device relationship with visible state and user control; periodic jobs cannot maintain a low-latency local socket or transparent reconnect status. |
| `dataSync` | Run large, user-initiated file transfers between the Android device and the paired Mac, including progress, cancel, and checksum verification. | User explicitly starts a file transfer. | The last active transfer completes/is cancelled, or failure/platform timeout stops all outbound work and requires the user to reselect the file. | WorkManager/JobScheduler are not enough for active user-observed transfer progress/cancel and LAN socket continuity during a large transfer. |

## User-Visible Notification Requirements

Current connected-device service notification:

- Title: localized `Hyphen companion link`
- Body: localized connecting / connected / degraded / paused state
- Action: `Disconnect`; tapping the notification opens Hyphen
- Must remain visible while the resident companion link is active.

Current data-sync service notification:

- Title: `Hyphen file transfer`
- Body: localized indeterminate active-transfer state
- Cancellation is currently performed in Hyphen; progress/cancel notification controls are a release-UX follow-up and must not be claimed in Play screenshots before implemented
- Must be shown only while a user-started transfer is active.

Both services refuse to start when app/channel notification visibility cannot be
confirmed and re-check visibility at most 60 seconds apart. Losing visibility
pauses TLS; an interrupted picker transfer is marked retry-required. A
process-scoped reservation covers every user start before its worker registers
with the sender. Normal completion/cancellation is delivered to the running
service and stops it only after all reservations and outbound protocol work
drain. A per-file registration/send failure cancels only that file and cannot
abort a later transfer generation. Service interruption and Android 15+
`dataSync` timeout cancel every outbound stream with per-stream failure
isolation, then stop the transfer service. Timeout detaches the FGS notification
into a user-return/reselect-to-retry notification. An opted-in
`connectedDevice` owner keeps the shared TLS link alive; without that owner,
terminal transfer cleanup also pauses the link. There is no boot receiver and
`dataSync` is never launched from `BOOT_COMPLETED`.

No foreground-service notification may hide cloud upload, telemetry, account sync, background clipboard listening, SMS, Call Log, Accessibility, or broad file-management behavior. Those surfaces are out of v1 scope.

## Data Handling Statement

Foreground-service work remains local-first:

- No account is required.
- No cloud relay or cloud upload is used.
- No telemetry is enabled by default.
- Notification contents, text/link payloads, and files are transmitted only to the trusted paired Mac over pinned local TLS.
- Diagnostics are local and redacted; export is user-triggered.
- File transfer integrity uses SHA-256. Inbound and file-backed peer checkpoints are durable and fail closed, but the current Android picker uses a pathless `content://` stream; timeout/process death cannot restore that outbound source. The UI therefore tells the user to reselect the file until the transfer queue owns persistable URI access.

## Review Evidence To Attach Later

Before a Play Console declaration is submitted, collect:

| Evidence | Source |
|---|---|
| Manifest diff showing only `connectedDevice` and `dataSync` FGS declarations | Android release branch |
| Foreground notification screenshots for connected link and active transfer | Physical Android device |
| 1 GiB transfer interruption/retry log | `docs/test-plans/hyp-m3-015-1gb-transfer-test.md` |
| Device/OEM matrix rows for FGS visibility and transfer interruption | `docs/compatibility-matrix.md` |
| Data safety statement aligned with diagnostics/export behavior | `data-safety-draft.md` |

## Explicit Non-Uses

Hyphen v1 does not use these FGS categories or permission surfaces:

- `remoteMessaging`
- media projection
- location
- SMS or Call Log
- Accessibility
- background clipboard monitoring
- cloud backup/sync

## Open Release Blockers

- Physical-device proof for notification permission denial/revocation, 60-second visibility watchdogs, `dataSync` timeout, process kill/restart, and OEM background policy is not complete.
- Transfer-notification progress and cancel controls are not implemented; cancellation currently requires the app UI.
- HYP-M4-005 Android device matrix is blocked by lack of devices.
- HYP-M3-015 1 GiB transfer execution is blocked by lack of a paired Android/macOS session.
- Play privacy policy URL and final Data safety form submission are still pending.
