# macOS Notarization Notes

Status: planning note for HYP-M0-011. Operational scripts live next to this file.

## Required Apple Assets

- Apple Developer Program membership for the release owner.
- Developer ID Application certificate installed in the signing keychain.
- A notarization credential path:
  - Preferred: `xcrun notarytool store-credentials` keychain profile used via `NOTARY_PROFILE`.
  - One-time fallback: `APPLE_ID`, `TEAM_ID`, and `APP_SPECIFIC_PASSWORD` environment variables.
- A distributable container from a later packaging task: ZIP, DMG, or PKG.

## Local Commands

- Local signing dry run: `./packaging/macos/sign-local.sh`
- Notarization dry run: `./packaging/macos/notarize-dry-run.sh`

The current notarization dry run zips the signed SwiftPM executable. DMG/ZIP packaging and stapling policy are deferred to HYP-M5-003.

## Secret Boundaries

- Do not commit certificates, private keys, app-specific passwords, notary profiles, keychain exports, or notarization logs that contain account identifiers.
- Prefer a keychain profile over raw Apple ID credentials in shell history.
- CI credentials, if added later, must be environment/secret-manager backed and documented separately before use.

## Expected Failure Modes

- Missing Developer ID certificate: signing/notarization is blocked until `SIGN_IDENTITY` names an installed certificate.
- Missing notary credentials: submission is blocked until `NOTARY_PROFILE` or one-time credentials are provided.
- Unsigned or ad-hoc-signed artifact: local verification can pass, but notarization is not Play-ready or public-release-ready.
- Stapling unavailable for the chosen container: packaging format must be revisited in HYP-M5-003.
