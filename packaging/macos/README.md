# macOS Packaging

## Local Signing Dry Run

`sign-local.sh` builds the SwiftPM `HyphenApp` product in release mode, signs the resulting executable, and verifies it with `codesign --verify --strict`.

Default usage needs no Apple Developer account:

```bash
./packaging/macos/sign-local.sh
```

This uses an ad-hoc signature (`SIGN_IDENTITY=-`). It proves the local signing command path works, but it is not a distributable Developer ID signature and it is not notarized.

Developer ID signing uses the same script once a certificate is installed locally:

```bash
SIGN_IDENTITY="Developer ID Application: Example Team (TEAMID1234)" ./packaging/macos/sign-local.sh
```

With a real identity, the script enables the hardened runtime and timestamping. Notarization credentials and submission are deliberately deferred to HYP-M5-002; do not store Apple credentials or signing secrets in the repository.

## Notarization Dry Run

`notarize-dry-run.sh` verifies the local notarization path without storing credentials. It requires:

- Xcode `xcrun notarytool`.
- An installed Developer ID Application certificate, supplied as `SIGN_IDENTITY`.
- Either an existing notarytool keychain profile (`NOTARY_PROFILE`) or one-time environment variables (`APPLE_ID`, `TEAM_ID`, `APP_SPECIFIC_PASSWORD`).

Preferred usage with a keychain profile:

```bash
SIGN_IDENTITY="Developer ID Application: Example Team (TEAMID1234)" \
NOTARY_PROFILE="hyphen-notary" \
./packaging/macos/notarize-dry-run.sh
```

One-time credential usage, without writing credentials into the repository:

```bash
SIGN_IDENTITY="Developer ID Application: Example Team (TEAMID1234)" \
APPLE_ID="dev@example.com" \
TEAM_ID="TEAMID1234" \
APP_SPECIFIC_PASSWORD="app-specific-password" \
./packaging/macos/notarize-dry-run.sh
```

If those prerequisites are absent, the script exits with a `BLOCKED` message naming the missing requirement. The current script zips the signed SwiftPM executable for notarization; DMG/ZIP packaging and stapling policy are deferred to HYP-M5-003.
