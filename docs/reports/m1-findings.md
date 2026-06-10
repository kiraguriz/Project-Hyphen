# M1 Platform-Risk PoC Findings Report

- **Tracker**: HYP-M1-015 · **Date**: 2026-06-10 · **Commit range**: `92fe8a7..c84fa50`
- **Status of M1**: 11/14 tasks fully verified; 3 (HYP-M1-004/006/007) implementation-complete with on-device acceptance pending **one physical Android device session**. 115+ unit tests green across both platforms (60 Android JVM, 22 macOS XCTest at time of writing); `./scripts/check.sh` exercises both.

## 1. Recommendation

**Conditional GO into M2 (protocol, trust store, transport).** M2 work is device-independent, and a working TLS transport makes the eventual device session strictly more valuable (it can verify discovery → connect → pair end-to-end in one sitting). No cut rule is triggered: nothing observed suggests mDNS, CDM, or wake recovery is unviable — the unverified parts are blocked by hardware access, not by negative findings.

## 2. Gate assessment

| Gate | Assessment | Evidence | Residual before full pass |
|---|---|---|---|
| G-A LAN/discovery survivability | **Conditional pass** | Mac-side mDNS advertise+browse proven on real mDNS (0.96s loopback XCTest); QR/manual fallback parser + TCP probe proven incl. real-socket tests; Android 16 restricted-mode behavior understood and test-planned (8 cases); deny-tolerant invariant (QR/manual in every permission state) enforced by code + tests | Android-device mDNS discovery of the Mac (HYP-M1-004), mDNS-off connect (HYP-M1-006), restricted-mode run (TC-1..7) |
| G-B Companion API viability | **PASS** (criteria met early; target was 2026-07-20) | API 26–35 and API 36+ adapters compile with PoC behavior and 21 unit tests; API 36 spike landed compile-gated against SDK 36; permission set fixed: `REQUEST_COMPANION_SELF_MANAGED`, no legacy BT association in v1 | CDM approval-dialog UX on a physical API 33+ device (HYP-M1-007) — does not affect the gate's written criteria |
| G-C macOS wake recovery | **Conditional pass** (prototype level) | Wake → reconnect state machine wired and unit-proven: fresh 1/5/15/30s schedule on wake, failure states surfaced immediately (well within the 30s criterion), sleep cancels retries | Real transport to reconnect (M2-007/013); 20 hardware sleep cycles (M4-007) |

## 3. Findings worth remembering

### Android

1. **`UNKNOWN` vs permanent denial requires app-side memory.** `granted=false, rationale=false` is ambiguous; only a persisted asked-before bit disambiguates. Encoded in `LocalNetworkAccessController` + tests.
2. **Android 16 restricted LAN is invisible to the permission API.** The controller correctly reports `GRANTED/platformGated=false` while sockets fail. Degradation must key off I/O failures on API 36, and off the permission state only on SDK 37+. Both paths are distinct test cases now.
3. **NsdManager constraints are real design inputs**: resolves must be serialized (one in flight), discovery must be windowed (20s) with the MulticastLock scoped to the window, and `MulticastLock.release()` throws on over-release — wrapped idempotently.
4. **Emulator NAT drops multicast** — Android-side mDNS verification requires physical hardware, full stop. This is why M1-004/006 carry `[?]`.
5. **Self-managed CDM is the right model and changes the presence story**: presence for self-managed associations is *app-driven* (`notifyDeviceAppeared/Disappeared` when the LAN-TLS session changes — M2 must wire this); the reward is system binding of the companion service while present. The `connectedDevice` FGS stays as the user-visible transparency surface.

### macOS

6. **SwiftPM-only menu-bar app works end to end** — no `.xcodeproj`, still builds via `xcodebuild -scheme Hyphen`, fully auditable, tests run in CI's `check.sh` hook.
7. **Advertise and browse verified against real mDNS in-process** — registration is answered locally by mDNSResponder; the LNP prompt is expected on browse rather than advertise (timing to confirm on-device; explain-first gate makes the question moot for UX).
8. **NotificationCenter injection makes power-event logic fully testable** (tests post the real `NSWorkspace` names). Found and fixed a real hazard: an unretained observer with weak handlers silently drops everything — documented in tests.
9. **Reconnect semantics pinned by tests**: schedule `[1,5,15,30]` capping at 30s; success and wake reset to a fresh schedule; suspended swallows all events; no timer stacking.

### Cross-cutting

10. **The service type `_hyphen._tcp` is pinned by tests on both platforms** (Swift + Kotlin) against protocol v0 — drift breaks a build, not a beta.
11. **kotlinx-coroutines and Compose remain deliberately un-introduced** — adapter APIs shipped callback-based; both dependencies need sanctioning at the M2 module split (open decision).

## 4. Decisions queued for M2 (need ADR-0002)

1. TLS identity: key type (P-256 vs Ed25519), cert lifetime, SPKI pin format on both platforms.
2. Trust store schema (Keychain item layout / EncryptedSharedPreferences structure) and `PeerId` = fingerprint derivation.
3. `notifyDeviceAppeared/Disappeared` wiring rules (what "session up" means).
4. Module split for `apps/android` (`core-protocol`, `core-transport`, `core-discovery`, `core-companion`) + coroutines/Compose adoption.
5. IPv6 endpoint literals (parser currently rejects them by design).
6. Resume-token construction (protocol v0 §9 open question #1).

## 5. Consolidated device-session checklist (one sitting, ~2h)

Physical Android phone (ideally API 33+; API 36 for presence spike) + this Mac on one Wi‑Fi:

1. `./gradlew installDebug`; Mac app advertising → discovery window finds Mac (HYP-M1-004 → `[x]`).
2. Disable Wi‑Fi mDNS path (or A16 restricted mode) → manual endpoint connect to Mac listener (HYP-M1-006 → `[x]`).
3. CDM associate/disassociate dialog round-trip (HYP-M1-007 → `[x]`).
4. Run `docs/test-plans/android-16-restricted-lan.md` TC-1..TC-7 (TC-8 needs an Android 17 preview image).
5. API 36 presence spike questions Q1–Q5 (`docs/spikes/api36-device-presence.md`).
6. macOS: observe LNP prompt timing (advertise vs browse), confirm menu-bar item visually (M1-010 note), one manual sleep/wake cycle watching the reconnect state line.

## 6. Sign-off

```text
Gate review date:
Reviewer:
G-A decision:            (conditional pass recorded 2026-06-10 by loop)
G-B decision:            (PASS recorded 2026-06-10 by loop)
G-C decision:            (conditional pass recorded 2026-06-10 by loop)
M2 entry approved: yes/no
Notes:
```
