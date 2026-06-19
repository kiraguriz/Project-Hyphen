# Project Hyphen Troubleshooting Guide (Pre-Alpha)

This guide is for pre-alpha testers. It turns known failure modes into concrete
checks without pretending the compatibility matrix is complete.

Keep the current evidence boundaries in mind:

- Android and macOS device matrices still contain blocked/not-run rows.
- Some checks require a physical Android device, extra Mac hardware, or a
  scheduled sleep/wake session.
- Discovery is not trust. Only pinned fingerprints plus SAS confirmation create
  a trusted peer.
- Do not paste notification text, file contents, URLs, IP addresses, passwords,
  tokens, or private contact details into bug reports.

## What To Capture First

Before changing settings, record:

- Hyphen commit or package checksum.
- Android device model, Android version, OEM skin, and install track.
- Mac model and macOS version.
- Network case: home Wi-Fi, mesh, hotspot, AP/client isolation, VPN, or
  restricted LAN mode.
- Scenario: pairing, discovery, notification mirror, dismiss, quick reply,
  text/link, transfer, wake reconnect, diagnostics export.
- Result: `pass`, `fail`, `blocked`, or `not-run`.

Use the templates in [Compatibility Matrix](compatibility-matrix.md). Attach
redacted diagnostics exports where available.

## Local Network Permission

### Android

Symptoms:

- "Find my Mac" finds nothing.
- Manual endpoint connect fails quickly.
- Android 16 restricted-LAN testing blocks NSD or socket access.
- Android 17 local-network permission is denied or not yet granted.

Checks:

1. Use QR/manual pairing first. LAN denial is a supported degraded mode.
2. Confirm the device is on the same network as the Mac.
3. Disable VPNs or private DNS profiles that isolate local traffic for this test.
4. On Android 16 restricted-LAN runs, follow
   [Android 16 Restricted Local Network Mode](test-plans/android-16-restricted-lan.md)
   and record the exact compat-toggle commands that worked on that device.
5. On Android 17 or SDK 37 test builds, grant Local Network access only from the
   user-triggered discovery/pairing flow. If denied, use QR/manual fallback.

Do not add location, SMS, Call Log, or Accessibility permissions to work around
LAN failures. ADR-0003 explicitly rejects that direction.

### macOS

Symptoms:

- The Local Network prompt never appears.
- Advertising/browsing fails after the user denied Local Network.
- Hyphen is missing from System Settings.

Checks:

1. Hyphen should not trigger the OS prompt at launch. Click a local-network
   action first, such as advertising or discovery.
2. If denied, use QR/manual pairing or enable Hyphen in:
   System Settings -> Privacy & Security -> Local Network.
3. If the entry is missing, quit and relaunch Hyphen, click the local-network
   action again, then re-check System Settings.
4. Record macOS version and prompt behavior in the compatibility matrix.

## mDNS / Bonjour Discovery

Symptoms:

- Android cannot find the Mac service.
- Discovery works on one Wi-Fi network but not another.
- Discovery finds stale or duplicate-looking peers.

Checks:

1. Treat mDNS as an accelerator only. If it fails, continue with QR/manual.
2. Confirm the Mac has started advertising `_hyphen._tcp`.
3. Keep both devices on the same SSID/VLAN for the baseline run.
4. Retry on a plain home Wi-Fi network before debugging mesh or enterprise Wi-Fi.
5. If the network uses AP/client isolation, expect discovery to fail and record
   QR/manual fallback behavior instead.
6. Do not trust any discovered service until SAS confirmation succeeds.

Record the network case as one of the matrix rows: home Wi-Fi, mesh, hotspot,
AP/client isolation, Android restricted LAN, permission denied, or Wi-Fi switch.

## Pairing And Trust

Symptoms:

- SAS codes do not match.
- A previously paired device no longer connects.
- The wrong device appears in discovery.

Checks:

1. If SAS codes differ, reject pairing on both devices. Do not continue.
2. Use "Manage paired devices" to forget the peer, then pair again.
3. Confirm the displayed endpoint or QR payload came from the Mac you intend to
   pair with.
4. If a trust reset happened, macOS `PairingController.stopAfterTrustChange` clears
   responder-side resume tokens via `ResumeTokenStore.invalidatePeer` (single forget)
   or `invalidateAll` (reset all); Android clears client-side `resumeToken` in the
   connection supervisor. Old tokens must not resume a session after reset.

## Wake, Sleep, And Network Changes

Symptoms:

- The Mac wakes but the Android session does not return.
- Wi-Fi changes leave the UI stuck in a connected-looking state.
- Reconnect repeats forever without a clear degraded state.

Checks:

1. Wait up to 30 seconds after wake or network change.
2. Confirm Hyphen shows either a reconnected session or a clear degraded/error
   state.
3. If it remains stale, restart the local app surface and capture diagnostics.
4. Record sleep duration, wake reconnect time, fallback path, and pass/fail in
   the wake/reconnect template in the roadmap tracker.

The final 20-cycle sleep/wake acceptance test is not complete yet. Mark this
area `blocked` when the blocker is missing human scheduling or unavailable
device coverage, not when the behavior is simply unobserved.

## Android OEM Background Restrictions

Symptoms:

- Connection drops when the phone screen turns off.
- Notifications stop after battery saver starts.
- Transfer/reconnect works on Pixel but not on Samsung, Xiaomi, OnePlus/Oppo, or
  another OEM skin.

Checks:

1. Keep the visible Hyphen connection/foreground notification enabled when that
   surface is present. It is part of the privacy and reliability model.
2. For the test device, allow background activity for Hyphen in the OEM battery
   settings.
3. Test once with battery saver off, then record a separate battery-saver run.
4. Record the exact OEM skin, settings path, and observed behavior in
   [Compatibility Matrix](compatibility-matrix.md).
5. If an OEM kills the connection anyway, file it as an OEM-specific matrix
   finding rather than broadening permissions.

## Notifications

Symptoms:

- Notifications do not mirror.
- Updates create duplicates.
- Dismiss or quick reply does not work.

Checks:

1. Confirm Notification Listener access is granted from Android Settings.
2. Confirm the sending app is actually posting notifications on Android.
3. For duplicate behavior, record the Android notification key behavior. Hyphen
   uses `StatusBarNotification.getKey()` as the stable identity.
4. Quick Reply is beta-only and should be visible only when the originating
   notification exposes a supported `RemoteInput` action.
5. Do not include notification contents in bug reports; use redacted diagnostics.

## File And Text/Link Transfer

Symptoms:

- Text/link send does not appear on the other device.
- File transfer stops after interruption.
- Large transfer cannot resume.

Checks:

1. Confirm the devices are paired and the session is not degraded.
2. Confirm user confirmation dialogs are accepted on the receiving side.
3. For transfers, record direction, size, network, interruption point, resume
   behavior, and hash verification result.
4. The 1GB multi-device acceptance run is not complete yet; record real device
   coverage instead of assuming it passes.

## When To Mark Blocked

Use `blocked` in tracker or matrix notes when the missing item is external to
the code in this repo:

- No attached Android device or emulator.
- Missing OS version or OEM device.
- Missing Apple Developer ID or notary credentials.
- Missing Android release/upload keystore.
- Missing Play/F-Droid account or review access.
- Sleep/wake testing not scheduled with the human user.

If a local test fails with a concrete error, record the exact error output and
fix that error first.
