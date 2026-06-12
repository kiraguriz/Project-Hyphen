# Android Foreground Service Declaration Draft

Status: draft for HYP-M5-006. This is not a Play Console submission and must not be treated as Play-ready until the Android foreground service implementation, manifest declarations, release signing, and manual compatibility evidence are complete.

## Current Implementation Boundary

The current Android manifest does not yet declare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, or a concrete foreground service class. This draft records the intended v1 declaration surface from ADR-0003 and the implemented transfer/reconnect behavior so the later service implementation can be reviewed against a fixed policy target.

## Declaration Summary

| FGS type | Intended use | User trigger | Stop condition | Why background alternatives are insufficient |
|---|---|---|---|---|
| `connectedDevice` | Maintain a user-visible local LAN TLS session with the trusted Mac for notification continuity, text/link actions, transfer control, and reconnect state. | User pairs with a Mac and enables/starts the companion connection. | User disconnects, disables auto-connect, revokes trust, signs out of the local session, or the app cannot reconnect and surfaces a paused/error state. | The connection is an ongoing companion-device relationship with visible state and user control; periodic jobs cannot maintain a low-latency local socket or transparent reconnect status. |
| `dataSync` | Run a large, user-initiated file transfer between the Android device and the paired Mac, including progress, cancel, checksum verification, and resume checkpoint handling. | User explicitly starts a file transfer. | Transfer completes, fails with visible error, is cancelled, or the platform timeout requires surfacing a resumable checkpoint. | WorkManager/JobScheduler are not enough for active user-observed transfer progress/cancel and LAN socket continuity during a large transfer. |

## User-Visible Notification Requirements

Connected-device service notification:

- Title: `Hyphen connected to Mac`
- Body: `Local companion link active. Tap to disconnect or manage pairing.`
- Actions: `Disconnect`, `Manage paired devices`
- Must remain visible while the resident companion link is active.

Data-sync service notification:

- Title: `Hyphen file transfer`
- Body: `Sending/receiving <filename>: <progress percent>`
- Actions: `Cancel`
- Must be shown only while a user-started transfer is active.

No foreground-service notification may hide cloud upload, telemetry, account sync, background clipboard listening, SMS, Call Log, Accessibility, or broad file-management behavior. Those surfaces are out of v1 scope.

## Data Handling Statement

Foreground-service work remains local-first:

- No account is required.
- No cloud relay or cloud upload is used.
- No telemetry is enabled by default.
- Notification contents, text/link payloads, and files are transmitted only to the trusted paired Mac over pinned local TLS.
- Diagnostics are local and redacted; export is user-triggered.
- File transfer integrity uses SHA-256; large-transfer resume uses in-memory checkpoints in the current MVP.

## Review Evidence To Attach Later

Before a Play Console declaration is submitted, collect:

| Evidence | Source |
|---|---|
| Manifest diff showing only `connectedDevice` and `dataSync` FGS declarations | Android release branch |
| Foreground notification screenshots for connected link and active transfer | Physical Android device |
| 1 GiB transfer resume log | `docs/test-plans/hyp-m3-015-1gb-transfer-test.md` |
| Device/OEM matrix rows for FGS visibility and transfer resume | `docs/compatibility-matrix.md` |
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

- Android foreground service class and manifest declarations are not implemented yet.
- `POST_NOTIFICATIONS` runtime handling for FGS visibility is not implemented yet.
- HYP-M4-005 Android device matrix is blocked by lack of devices.
- HYP-M3-015 1 GiB transfer execution is blocked by lack of a paired Android/macOS session.
- Play privacy policy URL and final Data safety form submission are still pending.
