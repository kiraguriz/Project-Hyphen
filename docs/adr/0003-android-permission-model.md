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

- `minSdk 26` (CompanionDeviceManager floor), initial `targetSdk 36`, and SDK 37 (`ACCESS_LOCAL_NETWORK`) compatibility work begins in M1, not later.

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
| User-initiated large transfer | `dataSync` | Started only on explicit user action; handles platform timeout by checkpointing and surfacing a resumable state (⚠ exact timeout behavior validated in M1/M3). |
| Messaging continuity | `remoteMessaging` | **Not used in v1** (future SMS research track only, P3). |
| Maintenance/cleanup | none | WorkManager/JobScheduler; no resident service for housekeeping. |

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
| `CAMERA` | QR scan during pairing only | Runtime, requested at scan time; denial falls back to manual entry |
| Notification access (special) | Mirroring | Special access screen, §4 |
| CDM association (`REQUEST_COMPANION_*` as applicable) | Presence/background resilience | ⚠ exact set fixed by HYP-M1-007/008/009 PoCs |

Both distribution tracks share this ceiling; the Play build may *reduce* (never extend) it if review demands (ADR-0004).

## Consequences

- **Positive**: no Play high-risk permission review in v1; permission denial never bricks the product; the resident FGS notification doubles as the privacy transparency indicator; SDK 37 enforcement arrives with a tested code path instead of an emergency.
- **Negative / accepted**: QR/manual pairing UX is heavier than invisible discovery — accepted per Gate A cut rule. The visible connection notification may annoy some users — accepted as a transparency trade-off. CAMERA adds one more runtime prompt — scoped to the pairing screen.
- **Validation hooks**: HYP-M1-002..009 PoCs confirm every ⚠ above; manifest diffs are reviewed against this ADR, and any new permission requires updating this ADR first.
