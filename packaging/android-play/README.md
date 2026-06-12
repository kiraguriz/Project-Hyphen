# Android Play Packaging

## Release Build Dry Run

`build-release.sh` builds release APK/AAB artifacts and writes SHA-256 checksums under `packaging/android-play/build/`.

```bash
./packaging/android-play/build-release.sh
```

Without signing environment variables, Gradle produces unsigned dry-run artifacts where applicable. This is useful for reproducibility checks, but it is not a Play-ready release.

Release signing uses an external keystore only. Do not commit keystores, passwords, or copied signing logs.

Required environment variables:

- `HYPHEN_ANDROID_KEYSTORE`: absolute path to the release/upload keystore.
- `HYPHEN_ANDROID_KEY_ALIAS`: key alias.
- `HYPHEN_ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `HYPHEN_ANDROID_KEY_PASSWORD`: key password.

Example:

```bash
HYPHEN_ANDROID_KEYSTORE="/secure/path/hyphen-upload.jks" \
HYPHEN_ANDROID_KEY_ALIAS="hyphen-upload" \
HYPHEN_ANDROID_KEYSTORE_PASSWORD="..." \
HYPHEN_ANDROID_KEY_PASSWORD="..." \
./packaging/android-play/build-release.sh
```

If any signing variable is set, all four must be set; the Gradle build fails closed on partial signing configuration.

## Play Policy Drafts

- [Foreground service declaration draft](fgs-declaration-draft.md)
- [Data safety statement draft](data-safety-draft.md)
