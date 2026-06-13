# Public Beta Checklist (Draft)

- **Tracker**: HYP-M5-010
- **Status**: blocked for a real public beta until external signing, notary, and
  release-channel gates are available.
- **Scope**: reproducible maintainer checklist for the current GitHub/F-Droid,
  Play, and macOS distribution preparation paths.

This checklist is intentionally strict. A maintainer can use it to reproduce the
local dry-run artifacts today, but must stop before public distribution when a
required external gate is missing.

## Stop Conditions

Do not publish a public beta if any of these are true:

- macOS artifacts are not Developer ID signed and notarized.
- Android release artifacts are unsigned or signed with an unapproved local test
  key.
- SHA-256 checksums are missing or do not verify.
- Play Data safety / foreground-service declarations no longer match code.
- F-Droid metadata still contains placeholder public URLs or a draft commit.
- The beta known-issues section omits blocked matrix areas.
- Secrets, private logs, notification text, file contents, URLs, or personal
  device data appear in release notes, fixtures, diagnostics, or artifacts.

## Required External Gates

| Gate | Required for | Current status |
|---|---|---|
| Apple Developer Program membership | Developer ID signing and notarization | Blocked locally |
| Installed Developer ID Application certificate | macOS distributable package | Blocked locally |
| `notarytool` profile or one-time Apple credentials | macOS notarization dry run | Blocked locally |
| Android release/upload keystore | Play-ready APK/AAB signing | Blocked locally |
| Google Play developer account/review access | Closed/open beta distribution | Not available in repo |
| F-Droid review/submission path | F-Droid beta distribution | Metadata draft only |
| Physical Android/OEM device coverage | Compatibility matrix | Partially blocked |
| Scheduled sleep/wake session | Wake/reconnect evidence | Blocked until human-run test |

## Preflight

From the repository root:

```bash
git status --short
./scripts/check.sh --strict
```

Expected result:

- Working tree contains only intentional release-prep changes.
- `./scripts/check.sh --strict` passes markdown links, secret scan, Android tests, macOS
  tests, and protocol fixtures.

Also review:

- [Roadmap tracker](../project_hyphen_roadmap_tracker_v0_3.md)
- [Install guide](../install/installation_en.md)
- [Troubleshooting guide](../troubleshooting_en.md)
- [Compatibility matrix](../compatibility-matrix.md)
- [Play Data safety draft](../../packaging/android-play/data-safety-draft.md)
- [FGS declaration draft](../../packaging/android-play/fgs-declaration-draft.md)
- [F-Droid metadata draft](../../packaging/android-fdroid/metadata/dev.hyphen.android.yml)

## macOS Local Dry Run

```bash
./packaging/macos/package-local.sh
cd packaging/macos/build
shasum -a 256 -c SHA256SUMS
hdiutil verify Hyphen-macOS-0.0.1.dmg
```

Expected local artifacts:

- `packaging/macos/build/Hyphen-macOS-0.0.1.zip`
- `packaging/macos/build/Hyphen-macOS-0.0.1.dmg`
- `packaging/macos/build/SHA256SUMS`

Local dry-run output is ad-hoc signed by default. It is not public-beta ready.

## macOS Developer ID And Notarization

Run only after the external Apple gates are available:

```bash
SIGN_IDENTITY="Developer ID Application: Example Team (TEAMID1234)" \
NOTARY_PROFILE="hyphen-notary" \
./packaging/macos/notarize-dry-run.sh
```

Required evidence before publishing:

- `codesign --verify --strict --verbose=2` passes for `Hyphen.app`.
- Notarization succeeds.
- Stapling policy is decided and verified for the public container.
- Checksums are regenerated after final packaging.

## Android Release Dry Run

```bash
./packaging/android-play/build-release.sh
cd packaging/android-play/build
shasum -a 256 -c SHA256SUMS
```

Expected output directory:

- `packaging/android-play/build/`

Without all four `HYPHEN_ANDROID_*` signing variables, this is only a release
build dry run. The script must report that signing is not configured.

## Android Signed Release

Run only after the external keystore gate is available:

```bash
HYPHEN_ANDROID_KEYSTORE="/secure/path/hyphen-upload.jks" \
HYPHEN_ANDROID_KEY_ALIAS="hyphen-upload" \
HYPHEN_ANDROID_KEYSTORE_PASSWORD="..." \
HYPHEN_ANDROID_KEY_PASSWORD="..." \
./packaging/android-play/build-release.sh
```

Required evidence before publishing:

- All four signing variables are set from a local secret store, not the repo.
- Partial signing configuration fails closed.
- APK/AAB checksums verify.
- Play policy drafts still match the manifest and runtime behavior.

## F-Droid Prep

Current metadata is disabled and not submission-ready. Before an F-Droid beta:

1. Replace placeholder public URLs.
2. Replace the draft commit with the release tag or final full commit hash.
3. Confirm root license files and SPDX/license policy are complete.
4. Run metadata checks:

```bash
ruby -e 'require "yaml"; YAML.load_file("packaging/android-fdroid/metadata/dev.hyphen.android.yml")'
```

When `fdroidserver` is available in a proper fdroiddata checkout:

```bash
fdroid rewritemeta dev.hyphen.android
fdroid lint dev.hyphen.android
```

## Beta Notes And Known Issues

Release notes must include:

- Pre-alpha/beta status and supported install tracks.
- No account, no cloud relay, no telemetry by default.
- Notification Listener sensitivity and user-controlled enablement.
- Current blocked matrix areas: Android first-five device matrix, macOS
  multi-device/OS matrix, 20-cycle wake/reconnect test, 1GB multi-combo resume
  validation, and crash-free beta-session measurement.
- Unsupported v1 non-goals: SMS, Call Log, Accessibility, background clipboard,
  screen mirroring, remote control, cloud relay, and NAT relay.

## Publish Decision

Use these outcomes:

- `go`: all local checks pass, all external gates pass, checksums verify, known
  issues are current, and no forbidden surface was introduced.
- `blocked`: local dry runs pass but any external gate is missing.
- `no-go`: a local check fails, a policy statement is stale, a checksum fails,
  or a forbidden permission/surface appears.

Current local status for HYP-M5-010: `blocked` until Developer ID/notary,
Android release signing, and beta channel access are available.
