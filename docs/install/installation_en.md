# Project Hyphen Installation Guide (Pre-Alpha)

Project Hyphen is still pre-alpha. These instructions are for local maintainers
and technical testers who are building from this repository. They do not
describe a trusted public release yet.

Use this guide with the current tracker:
[roadmap tracker](../project_hyphen_roadmap_tracker_v0_3.md).

## Current Install Paths

| Platform | Current path | Public-release status |
|---|---|---|
| macOS | Local ZIP/DMG from `packaging/macos/package-local.sh` | Ad-hoc signed by default; not notarized |
| Android debug | Local debug APK from Gradle | Developer/test install only |
| Android release dry run | APK/AAB from `packaging/android-play/build-release.sh` | Unsigned unless external signing variables are set |
| F-Droid | Metadata draft only | Disabled and not submission-ready |

## Prerequisites

- macOS 14 or later for the menu-bar app.
- Xcode Command Line Tools, including `swift`, `codesign`, `hdiutil`, and
  `ditto`.
- Android SDK and JDK 17 for Android builds.
- ADB plus an Android device or emulator for Android install testing.
- Same-LAN network access for pairing and transport tests.

Do not commit signing keys, keystores, passwords, generated release artifacts,
or copied signing logs.

## macOS Local Package

From the repository root:

```bash
./packaging/macos/package-local.sh
```

Expected artifacts:

- `packaging/macos/build/Hyphen-macOS-0.0.1.zip`
- `packaging/macos/build/Hyphen-macOS-0.0.1.dmg`
- `packaging/macos/build/SHA256SUMS`

Verify checksums:

```bash
cd packaging/macos/build
shasum -a 256 -c SHA256SUMS
```

Install for a local smoke test:

1. Open `Hyphen-macOS-0.0.1.dmg`, or unzip
   `Hyphen-macOS-0.0.1.zip`.
2. Copy `Hyphen.app` to `/Applications`, or run it from the mounted/staged
   folder for a short test.
3. Start `Hyphen.app`.
4. Confirm the Hyphen menu-bar item appears.

The default package uses ad-hoc signing (`SIGN_IDENTITY=-`). It is suitable for
local packaging verification only. It is not notarized, and macOS Gatekeeper may
warn or block it on other machines.

For Developer ID signing, install a valid certificate locally and run:

```bash
SIGN_IDENTITY="Developer ID Application: Example Team (TEAMID1234)" \
./packaging/macos/package-local.sh
```

Notarization remains a separate step:

```bash
SIGN_IDENTITY="Developer ID Application: Example Team (TEAMID1234)" \
NOTARY_PROFILE="hyphen-notary" \
./packaging/macos/notarize-dry-run.sh
```

If Developer ID or notary credentials are missing, the notarization script exits
with a `BLOCKED` message. Keep credentials in the local keychain or one-time
environment variables only.

## Android Debug Install

Use the debug APK for local device testing:

```bash
cd apps/android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

This path requires an attached Android device or emulator with USB debugging
enabled. The current app id is `dev.hyphen.android`.

Uninstall:

```bash
adb uninstall dev.hyphen.android
```

## Android Release Dry Run

From the repository root:

```bash
./packaging/android-play/build-release.sh
```

Expected output directory:

- `packaging/android-play/build/`

The script copies release APK/AAB artifacts into that directory and writes
`SHA256SUMS`. With no signing environment configured, the artifacts are dry-run
outputs and are not Play-ready.

Verify checksums:

```bash
cd packaging/android-play/build
shasum -a 256 -c SHA256SUMS
```

For a signed release build, set all four external signing variables before
running the script:

```bash
HYPHEN_ANDROID_KEYSTORE="/secure/path/hyphen-upload.jks" \
HYPHEN_ANDROID_KEY_ALIAS="hyphen-upload" \
HYPHEN_ANDROID_KEYSTORE_PASSWORD="..." \
HYPHEN_ANDROID_KEY_PASSWORD="..." \
./packaging/android-play/build-release.sh
```

If any signing variable is set, all four must be set. Partial signing
configuration fails closed. Do not store these values in the repository.

## F-Droid Status

`packaging/android-fdroid/metadata/dev.hyphen.android.yml` is a disabled draft.
It is not an install source yet. Before submission it needs the public repo URL,
issue tracker, release tag, final app name, root license files, dependency
audit, and signing/update strategy.

## First-Run Notes

- Pairing is local-first: QR/manual endpoint fallback plus SAS confirmation.
- mDNS/Bonjour discovery is a convenience path, not trust.
- Trust comes only from pinned fingerprints plus SAS confirmation.
- Android notification mirroring requires the user to grant Notification
  Listener access.
- macOS local-network access should be prompted only after a visible user
  action.
- Hyphen does not use accounts, cloud relay, SMS, Call Log, Accessibility, or
  telemetry by default.

## Known Pre-Alpha Blockers

- macOS public distribution still needs Developer ID signing, notarization, and
  stapling evidence.
- Android public distribution still needs external signing keys and Play/F-Droid
  track review.
- Device and OS compatibility matrices are not complete.
- Some acceptance checks still require physical Android devices, additional Mac
  hardware, beta users, or explicit sleep/wake test sessions.
