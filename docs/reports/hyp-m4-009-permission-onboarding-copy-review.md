# HYP-M4-009 Permission Onboarding Copy Review

- **Date**: 2026-06-12
- **Tracker**: HYP-M4-009
- **Plan references**: `docs/project_hyphen_plan_v0_3_en.md` sections 7.3, 7.6, 8.3, 10.1
- **Scope**: current Android/macOS permission and special-access prompts that affect private beta onboarding.

## Summary

The current repo has explicit, action-triggered copy for macOS Local Network
Privacy, Android notification listener access, and beta diagnostics. This pass
tightened the Android notification-listener explanation so a tester sees the
sensitive part first: Hyphen can read notification titles, actions, and content,
then mirrors them only to the paired Mac over local TLS.

The remaining HYP-M4-009 acceptance criterion is user comprehension evidence.
That cannot be claimed in this environment because HYP-M4-005 is blocked by the
absence of attached Android devices and there is no beta feedback corpus yet.

## Current Copy Inventory

| Surface | User action before prompt | Current repo surface | Copy status |
|---|---|---|---|
| macOS Local Network Privacy | User starts advertising/browsing | `LocalNetworkOnboardingGate` and `docs/copy/macos-local-network-onboarding.md` | Explains paired-device-only discovery, direct LAN connection, no internet scan, no upload, QR/manual fallback, and Settings repair path. Unit tests guard the core promise. |
| Android Notification Listener special access | User taps "Enable notification mirror" | `MainActivity.showNotificationAccessOnboarding()` | Updated in this pass. Copy now says Hyphen can read titles/actions/content, mirrors only to the paired Mac over local TLS, has no cloud relay/history store, supports hidden-body mode, never requests SMS/Call Log, and can be disabled later in Android Settings. |
| Android Local Network / LAN discovery | User taps discovery or uses QR/manual pairing | ADR-0003 plus Android 16 restricted-LAN test plan | Runtime permission is not active in the current target-SDK-36 build. Future SDK-37 permission copy must stay action-triggered and deny-tolerant. |
| Android Companion Device Manager | User taps CDM association | Android system association dialog plus debug button copy | Debug surface only. Private beta copy should avoid CDM jargon and present this as "remember this paired Mac" once the UI is promoted beyond the PoC surface. |
| Beta diagnostics opt-in | User taps "Beta diagnostics" | Android `MainActivity`, macOS menu item, public beta checklist | Default-off and explicit. Copy says exports are local/user-triggered, trace IDs may be included only for beta debugging, and notification bodies/file names/URLs/IP suffixes stay redacted. |

## Copy Rules For The Beta UI

1. Ask only after a visible user action; never on launch.
2. Lead with the sensitive capability before the benefit.
3. State where data goes and where it does not go.
4. Say the supported fallback when the user declines.
5. Name the settings path or repair action when the user changes their mind.
6. Keep frozen exclusions visible where they reduce policy/privacy ambiguity:
   no cloud relay, telemetry by default, SMS, Call Log, Accessibility, or
   background clipboard listening.

## Blocked Validation

HYP-M4-009 should remain blocked until review or beta feedback confirms users
understand why each permission or special-access request appears.

Required evidence:

- At least one Android device run from the HYP-M4-005 compatibility matrix.
- A paired Mac/Android session when checking notification and diagnostics copy.
- Review notes or beta feedback that cover Local Network, Notification
  Listener, Companion Device association, diagnostics export, and fallback
  behavior after denial.

Suggested tester questions:

1. What did Hyphen ask you to enable, and what did you expect it to read?
2. Where did you think the data would go?
3. What would you do if you did not want to grant the permission?
4. How would you turn the permission off later?
5. Did any copy imply cloud sync, accounts, SMS/Call Log access, or background
   clipboard monitoring?
