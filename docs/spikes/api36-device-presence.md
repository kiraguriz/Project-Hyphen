# Spike: API 36 Device Presence (`ObservingDevicePresenceRequest`)

- **Tracker**: HYP-M1-009 · **Builds on**: HYP-M1-007 (self-managed CDM), HYP-M1-008 (adapter layering)
- **Status**: Compile-gated implementation landed (allowed verification path per the roadmap row). On-device behavior log pending an API 36 physical device.

## What the API 36 model actually is (recorded behavior)

1. **Observation is per-association.** `CompanionDeviceManager.startObservingDevicePresence(ObservingDevicePresenceRequest)` takes an association id (the API 31–35 path took a MAC address and is deprecated). Stop is symmetric.
2. **Events arrive at a system-bound service, not a callback.** The app declares a `CompanionDeviceService` guarded by `BIND_COMPANION_DEVICE_SERVICE` with the `android.companion.CompanionDeviceService` intent-filter. API 36 delivers `onDevicePresenceEvent(DevicePresenceEvent)` with an event-type int: BLE appeared/disappeared, BT connected/disconnected, self-managed appeared/disappeared.
3. **Self-managed presence is app-driven, not radio-driven.** For `setSelfManaged(true)` associations (Hyphen's model, ADR-0003/HYP-M1-007), the *app* reports reachability via `notifyDeviceAppeared(associationId)` / `notifyDeviceDisappeared(associationId)` — i.e. Hyphen will call notify* when the LAN-TLS session to the Mac comes up/down (M2 transport wiring). The system then (a) binds the companion service for the present period and (b) reflects it back as `EVENT_SELF_MANAGED_APPEARED/DISAPPEARED`.
4. **The prize is the binding.** While a peer is present, the system keeps the companion service bound — process priority without a visible foreground service. The `connectedDevice` FGS (ADR-0003 §3) remains the transparency surface; the CDM binding is resilience, not a replacement.

## What Hyphen built (compile-gated, API-36 paths annotated)

- `HyphenCompanionDeviceService`: translates `DevicePresenceEvent` → appeared/disappeared via the pure `presenceEventToAppeared()` mapper (unit-tested against the platform constants); pre-36 deprecated callbacks are explicit no-ops (self-managed peers never fire them).
- `DevicePresenceBridge`: process-wide seam from the system-instantiated service to adapter consumers — `bind(associationId, peerId)` after association, `dispatch` routes to per-peer observers; unknown ids ignored. `BridgePresenceSource` plugs it into `Api36PresenceAdapter` (which dedupes flapping).
- `Api36PresenceObservation`: SDK-gated start/stop of system observation per association id.
- Manifest: exported service guarded by the system-only bind permission.

## Open questions for the device session (record results here)

| # | Question | Expected per docs | Observed |
|---|---|---|---|
| 1 | Does the service stay bound across doze when a self-managed peer is "appeared"? | Bound, elevated proc state | TBD |
| 2 | Latency from `notifyDeviceAppeared` to `EVENT_SELF_MANAGED_APPEARED` | ~immediate | TBD |
| 3 | Does `startObservingDevicePresence` require the association to exist first (error otherwise)? | IllegalArgumentException on unknown id | TBD |
| 4 | Behavior on app force-stop: does observation survive reboot/restart? | Re-arm needed on process start | TBD |
| 5 | Do BLE/BT event types ever fire for self-managed associations? | No | TBD |

## Test log

```text
Date:
Device/OS build:
Hyphen commit:
Q1..Q5 observations:
Issues filed:
```
