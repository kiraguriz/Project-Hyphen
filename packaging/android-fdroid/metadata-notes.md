# F-Droid Metadata Notes

Status: planning note for HYP-M0-013. This is not the final fdroiddata
metadata file; HYP-M5-007 drafts and validates the release-specific metadata
after the app ID, license decision, dependency audit, and signing strategy are
settled.

Official references to re-check during HYP-M5-007:

- F-Droid Build Metadata Reference:
  <https://f-droid.org/en/docs/Build_Metadata_Reference/>
- F-Droid Inclusion Policy:
  <https://f-droid.org/en/docs/Inclusion_Policy/>
- F-Droid Submitting Quick Start Guide:
  <https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/>
- F-Droid Reproducible Builds:
  <https://f-droid.org/en/docs/Reproducible_Builds/>

## Track Boundary

- F-Droid targets the transparent/open-source Android track described in
  ADR-0004, not the policy-minimized Google Play track.
- It must remain protocol-compatible with Play and GitHub builds.
- No proprietary service dependency, cloud relay, account requirement,
  telemetry by default, SMS, Call Log, Accessibility, or background clipboard
  listener may be added for the F-Droid track.
- Any feature difference between F-Droid and other Android tracks must be
  documented in release notes and, once divergence exists,
  `docs/track-differences.md`.

## Candidate Metadata Inputs

- App ID: `dev.hyphen.android` while the naming/trademark decision remains
  open.
- Name: `Project Hyphen` or final user-facing name from the naming decision.
- Summary: local-first Android companion for macOS.
- Description themes: no account, no cloud relay, paired LAN transport,
  notification continuity, text/link/file flow, local diagnostics export, and
  open protocol documentation.
- Categories: choose from current fdroiddata categories during HYP-M5-007;
  likely candidates are connectivity/productivity-adjacent, but do not invent a
  category locally.
- License: blocked on HYP-M0-015. Use the final SPDX identifier exactly; do not
  publish metadata with a provisional license.
- SourceCode / IssueTracker / Changelog: use stable public project URLs once
  releases and issue templates exist.
- RepoType: `git`.
- Repo: public HTTPS git URL only; no authenticated source.
- Builds subdir: likely `apps/android`, because that is the Gradle wrapper and
  settings root for the Android app.
- Current version seed from code: `versionName = 0.0.1`,
  `versionCode = 1`; final values must come from the tagged release commit.

## Reproducibility Considerations

- Use a full commit hash for each F-Droid build block, not a moving branch or
  ambiguous tag.
- Keep Gradle wrapper, Android Gradle Plugin, Kotlin plugin, compile SDK, target
  SDK, and JDK requirements pinned and documented.
- Prefer a build path that uses only source dependencies fetched from accepted
  repositories; avoid checked-in binaries, proprietary SDKs, generated jars, or
  network-generated source.
- If developer-signed reproducible builds are attempted, publish a versioned
  APK URL plus the expected signing key through F-Droid's `Binaries` /
  `binary` and `AllowedAPKSigningKeys` flow.
- If F-Droid-signed builds are used instead, document the user-update
  consequence before first public release. Android users cannot seamlessly
  switch between signing keys.
- Do not rely on Play App Signing artifacts for F-Droid. The GitHub/F-Droid
  signing plan must stay independently reproducible and auditable.
- Keep release artifacts, checksums, and any generated APK/AAB outputs outside
  source control; `packaging/android-play/build/` is already ignored.
- Audit timestamp, absolute path, locale, toolchain, and host-OS differences if
  local and F-Droid builds diverge.

## Inclusion Review Risks

- License metadata cannot be final until HYP-M0-015.
- Dependency review must reject GPL/AGPL-incompatible code paths and proprietary
  SDKs before Public Beta.
- Notification Listener and foreground service usage need clear user-facing
  rationale even though they are core features.
- Any future scanner, QR, crash-reporting, or update dependency must be checked
  for license and F-Droid acceptability before adoption.
- The final metadata should be linted with `fdroid lint <appid>` and normalized
  with `fdroid rewritemeta <appid>` when fdroidserver is available.
