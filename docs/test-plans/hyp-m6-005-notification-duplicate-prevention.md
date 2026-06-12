# HYP-M6-005 Notification Duplicate Prevention Test Log

Status: automated acceptance passed for the v0 duplicate-prevention invariant.

## Scope

This log finalizes the duplicate-prevention evidence produced by HYP-M4-008. It covers the deterministic mirror-pipeline invariant:

- Android sends `notification.posted` only for the first occurrence of each stable `StatusBarNotification.getKey()` value.
- Repeated Android keys become `notification.updated`, not new posted records.
- macOS maps one Android `sbnKey` to one deterministic notification identifier.
- Removal by `sbnKey` clears the mapped macOS identifier and keeps delivered state bounded.

Live Notification Center permission/visual checks remain part of HYP-M3-004 and the M4 compatibility matrix; they are not redefined by this log.

## Automated Run

| Date | Platform | Command | Result |
|---|---|---|---|
| 2026-06-12 | Android | `cd apps/android && ./gradlew :app:testDebugUnitTest --tests dev.hyphen.android.notifications.NotificationMirrorEventSenderTest` | pass |
| 2026-06-12 | macOS | `cd apps/macos && swift test --filter NotificationMirrorReceiverTests/testNotificationStormCoalescesByAndroidKeyAndRemovalsStayBounded` | pass |

The first Android command attempt used the aggregate `test --tests ...` form and failed with `Unknown command-line option '--tests'`; the corrected targeted task above passed.

## Acceptance Evidence

| Invariant | Evidence |
|---|---|
| Android active keys stay bounded during storm input | `notification storm keeps active keys bounded and repeats become updates` sends 1,000 events across 25 keys, then removes 10 keys. |
| Android posts only first sighting of each key | Same test asserts 25 `notification.posted` envelopes and 975 `notification.updated` envelopes. |
| Android removals are acked and scoped to `sbnKey` | Same test asserts 10 `notification.removed` envelopes and all envelopes use `notifications.v1` with `requiresAck`. |
| macOS coalesces by Android key | `testNotificationStormCoalescesByAndroidKeyAndRemovalsStayBounded` handles 1,000 posted/updated events across 25 keys and asserts 25 delivered identifiers. |
| macOS removals stay bounded | Same macOS test removes 10 keys and asserts 15 delivered identifiers remain. |
| Latest update wins | Same macOS test asserts key 24's body is `message-999`, proving repeated updates replace the prior presentation for that identifier. |

## Result

Pass for automated v0 acceptance. The duplicate-prevention rule is stable-key based and does not rely on `postTime`.

## Residual Risks

- Live macOS Notification Center rendering still needs device/manual permission evidence under HYP-M3-004 and HYP-M4-006.
- OEM notification-listener delivery behavior still needs Android device matrix coverage under HYP-M4-005.
- Beta duplicate-rate measurement from real user sessions is out of scope until opt-in diagnostics and beta cohorts exist.
