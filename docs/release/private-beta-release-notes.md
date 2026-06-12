# Private Beta Release Notes (Draft)

- **Tracker**: HYP-M4-011
- **Status**: draft release notes for technical private beta testers.
- **Audience**: maintainers and 20-50 invited technical testers.
- **Build status**: pre-alpha local/test builds only; no signed, notarized, or
  store-ready public release is available yet.

Project Hyphen is a local-first Android companion layer for macOS. This private
beta is for testing the pairing, transport, notification, text/link, transfer,
diagnostics, and packaging paths before any public beta or v1 release.

Use these notes with:

- [Installation guide](../install/installation_en.md)
- [Troubleshooting guide](../troubleshooting_en.md)
- [Compatibility matrix](../compatibility-matrix.md)
- [Roadmap tracker](../project_hyphen_roadmap_tracker_v0_3.md)

## What To Test

Private beta testers should focus on observed behavior, not broad product
polish. Record the exact build, devices, OS versions, network case, and result.

Candidate scenarios:

- First pairing through QR/manual endpoint and SAS confirmation.
- Trust persistence, peer forget, and reset-all paired-device controls.
- mDNS/Bonjour discovery where the network allows it, with QR/manual fallback
  when it does not.
- Notification mirror, update, remove, dismiss, hidden-body mode, and Quick
  Reply where the original Android notification exposes a supported RemoteInput.
- Android to macOS and macOS to Android text/link send with user confirmation.
- File transfer, interruption, resume, hash verification, progress, and cancel.
- Mac sleep/wake and Wi-Fi/network changes.
- Redacted diagnostics preview/export/delete on Android and macOS.
- Default-off beta diagnostics opt-in and immediate disable behavior.

## Privacy And Safety Defaults

Hyphen should behave like this in every beta build:

- No account, cloud relay, NAT relay, or telemetry by default.
- Pairing trust is created only after matching SAS confirmation.
- Discovery is never trust; it is only a hint before pinned TLS pairing.
- Notification Listener access is user-enabled and can read notification titles,
  actions, and content; hidden-body mode strips body text before sending.
- Diagnostics are local and redacted by default. Export is user-triggered.
- Beta diagnostics trace IDs are off by default and local/user-exported only.
- Hyphen does not request SMS, Call Log, Accessibility, background clipboard,
  location, contacts, or default phone/SMS roles in v1.

Do not paste notification text, file contents, URLs, IP addresses, passwords,
tokens, private contact details, or personal device identifiers into bug reports.
Use redacted diagnostics exports and compatibility-matrix fields instead.

## Known Issues And Blockers

This draft lists the current known limitations from the roadmap. A private beta
must keep these visible instead of implying support that has not been observed.

| Area | Current status | Tester expectation |
|---|---|---|
| Android device matrix | Blocked: no first-five device/OEM matrix has been observed yet. | Treat every Android/OEM result as evidence collection, not confirmed support. |
| macOS matrix | Blocked: three distinct OS/device combinations have not been observed. | Record exact Mac model and macOS version for every run. |
| Sleep/wake reconnect | Blocked: the 20-cycle manual run has not been scheduled/executed. | Report reconnect timing after wake; do not assume the 30s target passes. |
| Android diagnostics UI | Implemented, but manual preview/export/delete verification needs an attached device. | Test preview/export/delete on real hardware before relying on it for triage. |
| Peer management UI | Implemented, but final revoke/reset verification needs a real paired session. | After forgetting a peer, confirm reconnect requires re-pairing. |
| Permission comprehension | Copy pass complete, but user understanding has not been validated with beta feedback. | Note any confusing permission, special access, or fallback copy. |
| Notification end-to-end behavior | Core code and duplicate-prevention tests exist, but real device/macOS notification-center checks are still blocked. | Test real apps and note package/app family without including contents. |
| Quick Reply beta | Implemented only for notifications with supported RemoteInput actions; compatibility matrix is missing. | Record app family, action availability, and explicit failure/error states. |
| 1GB transfer resume | Test scaffold exists; final multi-combo 1GB evidence is missing. | Use smaller transfers for smoke tests; run the 1GB plan only in scheduled sessions. |
| Crash-free beta sessions | Not measurable: no beta session log corpus exists yet. | Report crashes/failures with build, scenario, device, and redacted diagnostics. |
| macOS public packaging | Local ZIP/DMG dry run exists; Developer ID signing/notarization is blocked externally. | Do not redistribute local ad-hoc packages as trusted public builds. |
| Android public packaging | Release dry run exists; Play-ready signing key and channel access are external blockers. | Do not treat unsigned dry-run artifacts as store-ready. |
| F-Droid | Metadata draft is disabled and not submission-ready. | Use it as a maintainer checklist only, not an install channel. |
| License release readiness | Dependency audit found no blocking runtime license issue, but root license files/map/notice policy are incomplete. | Do not use this draft as final public release legal clearance. |

## Unsupported In v1

These are not bugs in the private beta:

- SMS continuity, Call Log access, default SMS/phone roles, or call audio bridge.
- Accessibility services, private APIs, root-only behavior, or hidden background
  clipboard monitoring.
- Cloud account, cloud relay, NAT relay, or internet sync fallback.
- Screen mirroring, remote control, folder sync, Finder share extension, Sparkle
  auto-update, Homebrew Cask, iOS, Windows, or Linux companions.
- Automatic crash upload, analytics SDKs, ad SDKs, or telemetry endpoints.

## What To Include In Feedback

For each issue or pass/fail report, include:

- Hyphen commit or package checksum.
- Android device model, Android version, OEM skin, and install track.
- Mac model and macOS version.
- Network case: home Wi-Fi, mesh, hotspot, AP/client isolation, VPN, restricted
  LAN mode, or permission-denied mode.
- Scenario and result: `pass`, `fail`, `blocked`, or `not-run`.
- Redacted diagnostics export when available.
- Steps to reproduce, expected result, observed result, and whether the issue is
  repeatable.

## Stop Conditions

Stop the private beta run and report a release blocker if:

- A forbidden permission or surface appears.
- A local check fails before testing starts.
- Pairing stores trust before SAS confirmation.
- Notification contents appear in diagnostics, logs, screenshots, or release
  notes unintentionally.
- A dry-run artifact is mistaken for a signed/notarized/store-ready artifact.
- The tester cannot use QR/manual fallback after discovery or LAN permission
  failure.
