# ADR-0003: Android Permission Model

- **Status**: Accepted (draft decisions; platform claims marked ⚠ are validated by M1 PoCs)
- **Date**: 2026-06-10
- **Source**: plan v0.3 §7.3–§7.6, §10.1; ADR-0001; `docs/protocol/threat-model.md`
- **Tracker**: HYP-M0-009
- Note: ADR-0002 (transport and pairing) is reserved for M2 implementation decisions; the number gap is intentional.

## Context

Android permissions are Hyphen's largest platform risk, and they are moving:

- **Local network access is becoming gated.** Android 16 provides a restricted-LAN test mode; Android 17 enforces local-network protection for apps targeting SDK 37+, with `ACCESS_LOCAL_NETWORK` as an explicit runtime permission. mDNS/NSD, `.local` resolution, listeners, and multicast all fall under it. Hyphen cannot treat LAN as an implicit capability.
- **Foreground services are typed and policed.** Persistent connections and large transfers need declared FGS types with Play declarations and timeout behavior.
- **Notification access is the most sensitive permission we request.** It grants reading of effectively all notification content.
- **SMS/Call Log and Accessibility are Play high-risk surfaces** that trigger heavyweight review and are frozen out of v1 by ADR-0001.

## Decision

### 1. SDK targets

- `minSdk 29` (raised from 26 by **ADR-0008** for the TLS 1.3 platform floor; the CDM floor was 26 but TLS 1.3 needs API 29+), initial `targetSdk 36`, and SDK 37 (`ACCESS_LOCAL_NETWORK`) compatibility work begins in M1, not later.

### 2. Local network: controller-mediated, deny-tolerant

- Every LAN operation (NSD discovery, `.local` resolution, TCP listen/connect to LAN peers, multicast) goes through one `LocalNetworkAccessController` (HYP-M1-002) exposing `granted / denied / unknown` plus rationale state.
- On SDK 37+: request `ACCESS_LOCAL_NETWORK` at the moment the user taps a discovery-requiring action ("Find my Mac"), never at app launch.
- **Denied is a supported mode, not an error**: the app switches to QR/manual-endpoint pairing and remembered endpoints; paired peers keep working to the extent the platform allows. No crashes, no dead-end empty states (HYP-M1-006).
- Android 16 restricted-LAN mode is part of the standing manual test plan (HYP-M1-003).
- Onboarding copy states: Hyphen only looks for the user's paired Mac on the local network, does not scan the internet, and does not upload network topology.

### 3. Foreground services

| Use | FGS type | Rules |
|---|---|---|
| Persistent companion link | `connectedDevice` | The only resident service. Notification is always user-visible (transparency feature, not a nuisance to hide). Auto-connect can be disabled in settings. |
| User-initiated large transfer | `dataSync` | Started only on explicit user action; an Android 15+ platform timeout stops the stream and tells the user to reopen Hyphen and reselect the file. The current `content://` picker source is not claimed as restart-restorable. |
| Messaging continuity | `remoteMessaging` | **Not used in v1** (future SMS research track only, P3). |
| Maintenance/cleanup | none | WorkManager/JobScheduler; no resident service for housekeeping. |

Implementation state (2026-08-30): Android now declares two separate services,
one `connectedDevice` owner for an explicitly enabled trusted-peer link and one
`dataSync` owner started only after the user selects a file. Both require a
displayable notification before starting and re-check channel/app visibility at
most 60 seconds apart. Revocation pauses TLS. Each user start takes a
process-scoped reservation before service launch and releases it only after
sender registration. Normal completion/cancellation is serialized through the
transfer service and releases `dataSync` only after all reservations and
outbound protocol work drain. A per-file registration/send failure cancels only
that file and releases its reservation; a service interruption, visibility loss,
or API-35 timeout failure-isolates local cleanup for every outbound stream.
Those terminal paths preserve an opted-in `connectedDevice` link, while an
otherwise unowned link pauses. Timeout preserves a retry-required flag and makes
no outbound-checkpoint promise. There is no boot receiver and no
`BOOT_COMPLETED` start.
These are host-verified invariants; process-kill, timeout, notification-revocation,
and OEM behavior still require the device matrix.

### 4. Notification listener

- `NotificationListenerService` is enabled only through an explicit onboarding step that the user initiates; the screen explains exactly what is read and where it goes (paired Mac over LAN TLS; nowhere else).
- Rebind/lifecycle failures surface as a visible "mirroring paused" state with a repair path (HYP-M3-001).
- No persistent notification-history database (frozen; threat model §3.2).

### 5. Not requested in v1 (frozen)

- SMS / Call Log permissions; default SMS/Phone handler roles.
- Accessibility services.
- Background clipboard access.
- Any location permission: ⚠ if a platform/OEM path makes NSD unusable without one, that path is **dropped in favor of QR/manual**, not satisfied by adding location (validated in HYP-M1-004).

### 6. Expected v1 manifest permissions (GitHub/F-Droid and Play tracks)

| Permission | Why | Note |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | LAN sockets, connectivity changes | Install-time |
| `CHANGE_WIFI_MULTICAST_STATE` | Scoped MulticastLock during 15–30 s discovery windows, always released (HYP-M1-005) | Install-time |
| `ACCESS_LOCAL_NETWORK` (SDK 37+) | Discovery + LAN transport | Runtime, action-triggered |
| `POST_NOTIFICATIONS` | FGS notification + transfer status | Runtime |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | §3 | Install-time + Play declaration |
| none for QR capture | The v1 dependency-free path uses the system camera/QR surface to open a host-filtered `hyphen://pair` deep link; Hyphen validates the complete payload before network I/O. Manual paste remains available | No `CAMERA` permission and no scanner SDK in the app |
| Notification access (special) | Mirroring | Special access screen, §4 |
| `REQUEST_COMPANION_SELF_MANAGED` | Self-managed CDM association on API 33+ (Hyphen manages the LAN-TLS link itself; CDM records the relationship). API 29–32: no CDM association — QR-only pairing with conservative background behavior | A process-scoped token owns each platform request across Activity recreation, while an unclaimed approval launcher is replayed only to the current visible Activity. A launcher exception abandons that token so the user can retry and makes any late result stale. Association IDs are durably bound to an already trusted SPKI identity; every Activity-era registry operation reloads storage under one process lock, reconciles against `myAssociations`, and returns IDs for best-effort disassociation on trust reset/removal. A duplicate terminal callback for the same ID is idempotent, while stale callbacks, failed bindings, and displaced approval orphans are best-effort disassociated. Android's app-reported self-managed presence methods are hidden `@SystemApi` in SDK 36, while public presence observation does not support Wi-Fi; CDM therefore does **not** provide v1 LAN-session liveness or replace the visible FGS |

Both distribution tracks share this ceiling; the Play build may *reduce* (never extend) it if review demands (ADR-0004).

## Consequences

- **Positive**: no Play high-risk permission review in v1; permission denial never bricks the product; the resident FGS notification doubles as the privacy transparency indicator; SDK 37 enforcement arrives with a tested code path instead of an emergency.
- **Negative / accepted**: QR/manual pairing UX is heavier than invisible discovery — accepted per Gate A cut rule. The visible connection notification may annoy some users — accepted as a transparency trade-off. Depending on the system QR surface is device-specific, so manual paste remains the no-permission fallback.
- **Validation hooks**: HYP-M1-002..009 PoCs confirm every ⚠ above; manifest diffs are reviewed against this ADR, and any new permission requires updating this ADR first.
