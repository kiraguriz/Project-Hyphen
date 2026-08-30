# Spike: API 36 Device Presence (`ObservingDevicePresenceRequest`)

- **Tracker**: HYP-M1-009 · **Builds on**: HYP-M1-007 (self-managed CDM), HYP-M1-008 (adapter layering)
- **Status**: Spike complete; the intended self-managed Wi-Fi presence path is unsupported for an ordinary third-party app. Compile-gated adapters remain, but they are not product-lifecycle evidence.

## Corrected platform finding (2026-08-30)

The earlier spike conflated service-side/AOSP surfaces with the third-party
public SDK. Rechecking the SDK 36 `android.jar` and current Android reference
material established:

1. `CompanionDeviceManager.startObservingDevicePresence(ObservingDevicePresenceRequest)`
   is public on API 36, but public observation covers BLE range and Bluetooth
   connection state. The Android reference explicitly says Wi-Fi devices are
   not supported.
2. `CompanionDeviceService.onDevicePresenceEvent(DevicePresenceEvent)` is the
   system delivery surface once valid observation exists.
3. `notifyDeviceAppeared(int)`, `notifyDeviceDisappeared(int)`, and the newer
   self-managed report surface exist in AOSP as `@SystemApi @hide`; they are
   absent from the public SDK 36 `CompanionDeviceManager` class. Hyphen must not
   call them through reflection or private APIs.
4. Therefore a normal Hyphen build cannot report LAN-TLS reachability to CDM
   and cannot obtain a system binding from self-managed LAN presence. A CDM
   association records a user-approved relationship only; TLS pinning remains
   trust, and the visible `connectedDevice` foreground service remains the
   Android background-lifecycle owner.

Sources checked: the public [CompanionDeviceManager API reference](https://developer.android.com/reference/android/companion/CompanionDeviceManager), the [companion-device pairing guide](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing), SDK 36 `android.jar` inspected with `javap`, and the Android 16 QPR2 [framework source](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/core/java/android/companion/CompanionDeviceManager.java) showing the report methods annotated `@SystemApi`/`@hide`.

## What remains useful in the repository

- `AssociationController` / `CdmAssociationBackend` create and remove the
  public self-managed association on API 33+.
- `CompanionAssociationBindings` durably binds an association ID to exactly one
  already trusted 32-byte SPKI fingerprint. App-driven removal, trust removal,
  and trust reset clear the mapping; Activity resume/listing also reconciles it
  against `myAssociations` so a Settings-side deletion cannot remain durable.
  A process-scoped attempt token prevents Activity recreation from owning two
  platform requests and transfers an unclaimed approval launcher to the current
  visible Activity without retaining the destroyed one. A repeated terminal
  callback for the same association ID is idempotent; a different stale ID
  cannot consume a newer peer identity and is cleaned as an orphan. A launcher
  exception abandons the unusable request so the user can retry. Every
  Activity-era registry operation reloads durable bindings under a process lock,
  and trust reset/removal returns the affected IDs for best-effort platform
  disassociation. If
  trust changes during approval, binding persistence fails, or a new approval
  displaces an older association for the same peer, Hyphen best-effort
  disassociates the orphan. The mapping never authorizes TLS.
- `HyphenCompanionDeviceService`, `DevicePresenceBridge`,
  `Api36PresenceAdapter`, and `Api36PresenceObservation` retain the compile-gated
  API-shape spike and pure event tests. The service is not registered in the
  product manifest, and these sources do not prove that self-managed Wi-Fi
  presence can be armed in a third-party build.

## Evidence boundary and release decision

| Claim | Status | Required proof |
|---|---|---|
| User can create/remove a self-managed association | environment-only | Physical API 33+ system-dialog run (HYP-M1-007) |
| Association ID stays bound to the intended trusted SPKI locally | host-confirmed | JVM binding/index tests |
| API 36 BLE/BT observation API shape compiles | host-confirmed | SDK 36 compile + pure mapper tests |
| CDM keeps Hyphen alive for a self-managed LAN-TLS peer | unsupported | No public third-party reporting API; do not claim |
| Visible FGS maintains the opted-in LAN link | environment-only beyond host invariants | Physical-device notification, process, timeout, Doze/OEM matrix |

Revisit only if Android publishes a third-party API for self-managed presence
that supports this Wi-Fi/LAN companion model. Such a change requires a fresh
ADR/platform review; it must not be inferred from hidden framework source.
