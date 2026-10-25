# Emulator Runtime Evidence Run (2026-10-24)

- **Date**: 2026-10-24
- **Scope**: first end-to-end runtime pass of the release-critical journeys on an
  Android emulator plus this Mac, replacing "`adb devices` lists nothing" with
  observed behavior.
- **Evidence rule**: everything here is **emulator-class runtime observation**.
  It is a separate layer from host-side automated checks and from named
  physical-device/OEM proof; it does not fill a compatibility-matrix row.
- **Tracker**: HYP-M2-010, HYP-M3-001, HYP-M4-002, HYP-M4-004 (updated);
  HYP-M1-005, HYP-M1-007, HYP-M4-009, HYP-M4-010 (observations only).

## Environment

| Item | Value |
|---|---|
| Android | Pixel 10 Pro XL AVD, Android 17 (API 37), Google APIs Play Store image, arm64 |
| Android build | `./gradlew assembleDebug` debug APK from the working tree, installed with `adb install -r -g` |
| macOS | this Mac, macOS 27.0.1, `packaging/macos/package-local.sh` ad-hoc bundle copied to `/Applications/Hyphen.app` |
| Network | emulator NAT; the emulator reaches the host LAN address outbound, mDNS multicast does not cross the NAT |
| Driving | `adb shell input` / `uiautomator dump`; macOS app UI not yet driven (see "Open") |

Raw screenshots and the unified-log capture stay local; this file records
observations only and contains no addresses, payloads, or notification text.

## Observed (Android, no paired Mac)

| Journey / task | Observation | Result |
|---|---|---|
| First-use guide (HYP-M4-009) | Versioned guide shown on first launch in English; "Finish later" dismisses it and the home screen renders the not-paired state. | pass |
| Deep-link strict parse (HYP-M2-010) | `am start -a VIEW -d hyphen://pair?...` with missing `fp`/`n` logs `pairing link rejected: INVALID_PAYLOAD`; a well-formed link to an unreachable port is accepted, attempts provisional TLS, and fails closed after the 5 s connect timeout with no trust written. | pass |
| External camera entry (HYP-M2-010) | **Bug found and fixed.** "Open camera" reported "System camera unavailable" although `com.android.camera2` resolves `STILL_IMAGE_CAMERA` from the shell. Cause: Android 11+ package visibility; the manifest had no `<queries>` declaration, so `resolveActivity()` returned null on every modern device. After adding the `<queries>` intent (no camera permission), the system camera opens and Back returns to Hyphen. Contract test: `CameraHandoffVisibilityContractTest`. | fixed, re-observed pass |
| Notification access onboarding (HYP-M3-001) | "Enable notification mirror" shows the rationale dialog first; "Open settings" lands on the system listener page, which shows the localized label "Hyphen notification mirror"; after Allow, returning to the app flips the card to Enabled without a manual refresh and "Check notification access" logs `listener=CONNECTED`. Revoking via `cmd notification disallow_listener` returns `DISCONNECTED`. | pass |
| Status-row badge layout | **Bug found and fixed.** In the disabled state the "Permission" badge was squeezed to one letter per line by the wide "Enable notification mirror" control. The title now wraps and the badge keeps one line (`StatusRow`/`Pill` in `HyphenHome.kt`). | fixed, re-observed pass |
| Diagnostics preview / export / delete (HYP-M4-002) | Preview shows the `hyphen-diagnostics-v0` JSON with redacted structured events; Export opens the Android share sheet ("Sharing text"); Delete clears local diagnostics without a confirmation step. | pass |
| Beta diagnostics opt-in (HYP-M4-004) | Default Off; tapping shows the explanatory dialog with Cancel / Turn on; after Turn on the control reads On and tapping again returns to Off immediately. | pass |
| Local discovery window (HYP-M1-005) | "Find Mac" logs `multicast lock: ACQUIRED`, `discovery started (_hyphen._tcp)`, `multicast lock: RELEASED`, `window ended, resolved=0`; a second tap during the window logs `discovery already running`. No Mac is discoverable through emulator NAT, as expected. | pass (lock lifecycle); discovery itself not provable here |
| CDM association (HYP-M1-007) | "Create association" is gated: "Pair a Mac first". The system dialog can only be reached after pairing. | blocked until paired |
| Background companion link | "Keep connected" is gated the same way. | blocked until paired |
| Peer management (HYP-M4-010) | "Manage paired devices" shows the empty state "No trusted peers are stored on this phone." | empty state only |

### Notes that are not failures

- The pairing failure line in the local event stream includes the raw socket
  exception text (peer and local addresses). It stays in the in-app log and is
  not part of the redacted diagnostics export, which showed zero structured
  events for the same failure. Whether connect failures should be recorded as
  redacted diagnostics events is a follow-up question, not a privacy leak.
- Delete logs has no confirmation step; acceptable for local-only data, noted
  for the UX review.

## Open (needs the macOS app driven)

The Mac side could not be automated from this session: the app bundle was not
in the desktop automation index at session start, so no pairing listener was
started. These journeys remain unobserved and keep their tracker state:
bilateral SAS confirm/mismatch, text/link both directions, transfer
interrupt/resume/delivery/cancel, notification mirror/dismiss/privacy on the
Mac, macOS diagnostics, peer revoke/reset, process-kill reconnect, 1 GiB.

## Commands

```text
~/Library/Android/sdk/emulator/emulator -avd Pixel_10_Pro_XL -no-boot-anim
cd apps/android && ./gradlew assembleDebug && adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -a android.intent.action.VIEW -d '<hyphen://pair URI>'
adb shell cmd notification allow_listener dev.hyphen.android/dev.hyphen.android.notifications.HyphenNotificationListenerService
adb shell cmd package resolve-activity -a android.media.action.STILL_IMAGE_CAMERA
./packaging/macos/package-local.sh && ditto packaging/macos/build/staging/Hyphen-macOS/Hyphen.app /Applications/Hyphen.app
cd apps/android && ./gradlew test        # green after the fixes
```
