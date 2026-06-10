# Test Plan: Android 16 Restricted Local Network Mode

- **Tracker**: HYP-M1-003 · **Feeds**: Gate A (LAN/discovery survivability)
- **Validates**: ADR-0003 §2 deny-tolerant behavior; `LocalNetworkAccessController` (HYP-M1-002)
- **Status**: Plan documented. Execution requires a physical/emulated Android 16 (API 36) device and the discovery/fallback PoCs (HYP-M1-004/005/006); each case lists the task that activates it. Record runs in the log at the bottom and in `docs/compatibility-matrix.md` once it exists (HYP-M0-014).

## 1. Why this exists

Android 17 enforces local-network protection for apps targeting SDK 37+ (`ACCESS_LOCAL_NETWORK` runtime permission). Android 16 ships the enforcement machinery behind a per-app compat toggle, letting us experience tomorrow's denial behavior today. Hyphen's thesis requires that **a LAN-denied phone is a degraded-but-working product** (QR/manual pairing), never a crash or dead end.

Two distinct things must be tested and not conflated:

| Path | What it is | Controller view |
|---|---|---|
| **A16 restricted mode** | Behavioral blocking of LAN I/O on API 36 via compat flag; no permission dialog exists | `GRANTED, platformGated=false` — the controller cannot see the restriction; only socket/NSD failures reveal it |
| **SDK 37 permission** | Real `ACCESS_LOCAL_NETWORK` runtime permission on Android 17 | `UNKNOWN/DENIED/GRANTED, platformGated=true` |

Hyphen must degrade gracefully on **both** paths: the first via I/O-failure fallback, the second via the permission state machine.

## 2. Prerequisites

- Android 16 (API 36) device or emulator with developer options + adb.
- Hyphen debug build installed: `cd apps/android && ./gradlew installDebug` (package `dev.hyphen.android`).
- ⚠ **Verify toggle commands on-device before first run** against the official doc (plan §22 reference: developer.android.com/privacy-and-security/local-network-permission). Expected mechanism (compat framework):

```bash
# enable restriction for Hyphen only
adb shell am compat enable RESTRICT_LOCAL_NETWORK dev.hyphen.android
# verify
adb shell am compat dump | grep -A2 RESTRICT_LOCAL_NETWORK
# force-stop so the flag takes effect
adb shell am force-stop dev.hyphen.android
# disable after testing
adb shell am compat disable RESTRICT_LOCAL_NETWORK dev.hyphen.android
```

If the device build uses a different flag name/mechanism, record the working commands in the log and update this section.

## 3. Test cases

Columns: **Steps** assume a Mac on the same Wi‑Fi advertising `_hyphen._tcp` (HYP-M1-011) unless stated.

| ID | Active from | Steps | Expected result |
|---|---|---|---|
| TC-1 baseline discovery | HYP-M1-004/005 | Restriction **disabled**. Open discovery screen, start "Find my Mac". | Mac service found within the 15–30 s window; MulticastLock acquired at window start and released at end (verify via logs); no lock held after leaving the screen. |
| TC-2 restricted discovery | HYP-M1-004/006 | Enable restriction. Relaunch app, start discovery. | No crash, no infinite spinner. Discovery reports a failure/empty state within the window and the UI offers QR/manual pairing as the primary action. Failure reason logged locally with a `permission/local-network-denied`-family code. |
| TC-3 restricted direct connect | HYP-M1-006 | Enable restriction. Attempt manual-endpoint connect to the Mac's `ip:port`. | Socket fails fast (≤10 s). UI shows a clear, non-technical error with troubleshooting hint; no retry storm (backoff respected); app remains responsive. |
| TC-4 restricted multicast lock | HYP-M1-005 | Enable restriction. Trigger a discovery window; inspect logs. | Lock acquire/release still balanced (acquired count == released count) even when NSD fails immediately; no leaked lock after failure. |
| TC-5 QR/manual pairing unaffected | HYP-M1-006 + M2 pairing | Enable restriction. Complete QR pairing flow (camera scan of Mac QR). | ⚠ If restriction blocks even direct connections to the scanned endpoint, this is a **critical Gate A finding**: record exact socket errno and escalate — fallback design assumes user-entered endpoints remain reachable; if not, hotspot/USB guidance becomes the fallback. Either way: no crash, actionable error copy. |
| TC-6 restriction toggled mid-session | HYP-M1-004 | Start connected (restriction off), then enable restriction + force-stop, relaunch. | App starts into degraded state, shows remembered-peer with "can't reach" status, offers manual/QR re-pair; no ANR, no crash loop. |
| TC-7 controller state honesty | HYP-M1-002 (now) | On API 36 with restriction enabled, query `LocalNetworkAccessController.status()` (debug surface/log). | Reports `GRANTED, platformGated=false` — documenting that A16 restriction is invisible to the permission API. The *capability* layer (discovery results) must carry the failure signal instead. |
| TC-8 SDK 37 permission flow | Android 17 preview | On an Android 17 (SDK 37) image with the app targeting SDK 37: trigger "Find my Mac" before/after grant, deny once, deny permanently. | Controller walks UNKNOWN → (rationale) DENIED → permanent DENIED exactly as unit-tested; system dialog appears only on user action; settings deep-link offered on permanent denial. **Blocked until an Android 17 preview image is available locally.** |

## 4. Pass criteria (Gate A input)

- Zero crashes/ANRs across TC-2..TC-6.
- Every restricted failure surfaces a user-actionable state inside one discovery window (≤30 s).
- QR/manual remains a completable path under restriction (or TC-5's escalation is filed).
- MulticastLock never leaks (TC-1, TC-4).

## 5. Execution log

```text
Date:
Tester:
Device/OS build:
Hyphen commit:
Toggle mechanism verified (exact commands):
TC-1: pass/fail — notes
TC-2: pass/fail — notes
TC-3: pass/fail — notes
TC-4: pass/fail — notes
TC-5: pass/fail — notes
TC-6: pass/fail — notes
TC-7: pass/fail — notes
TC-8: pass/fail/blocked — notes
Issues filed:
```
