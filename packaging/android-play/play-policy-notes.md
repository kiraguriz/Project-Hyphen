# Google Play Policy Notes

Status: planning note for HYP-M0-012. This is not the final Play Console declaration; HYP-M5-005 and HYP-M5-006 draft the release-specific statements after the implementation surface settles.

## Track Boundary

- Play may ship a conservative feature set while staying protocol-compatible with GitHub/F-Droid builds.
- No cloud relay, account requirement, telemetry by default, SMS, Call Log, Accessibility, or background clipboard listener may be added to satisfy Play distribution.
- Any track difference must be visible in release notes and must not silently weaken local-first privacy guarantees.

## Foreground Service Areas

Expected v1 FGS declarations:

- `connectedDevice`: persistent paired-device connection, visible while the phone is linked to the Mac.
- `dataSync`: user-initiated large file transfer only; not a hidden background sync channel.

Release-specific wording is drafted in [`fgs-declaration-draft.md`](fgs-declaration-draft.md).

Out of scope:

- `remoteMessaging`: Hyphen is not a messaging app.
- Location, SMS, Call Log, Accessibility, and broad file-management categories.

## Data Safety Draft Inputs

Default behavior:

- No account.
- No cloud upload.
- No telemetry by default.
- No sale or sharing of user data.
- No persistent notification-history database.

Local/on-device data:

- Pinned peer trust records.
- Local diagnostics logs and user-triggered diagnostics export.
- In-memory notification mirror state while mirroring is active.
- User-selected text/link/file payloads transmitted only to the paired local peer over pinned TLS.

Potential opt-in beta diagnostics, if later enabled:

- Device class, OS/app version, failure codes, reconnect counts, and latency distributions.
- User preview/export controls and deletion controls are required before collection or sharing.

## Closed Testing Notes

- Test account requirement: none, because the app is accountless.
- Test instructions must include paired Mac setup, notification-listener enablement, foreground-service visibility, local-network fallback, and diagnostics export preview.
- Device coverage should include at least Pixel/AOSP, Samsung One UI, Xiaomi/HyperOS, OnePlus/Oppo, and one Android 16/17 local-network-permission path once available.

## Review Risks To Track

- Notification Listener permission needs clear core-feature rationale.
- Foreground-service declarations must match actual runtime usage and visible notification copy.
- Data safety must not overclaim "not collected" for user-triggered diagnostics exports if those are shared with maintainers.
- Play builds must not include features excluded by ADR-0003.
