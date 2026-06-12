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
