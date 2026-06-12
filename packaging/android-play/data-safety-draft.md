# Android Play Data Safety Statement Draft

Status: draft for HYP-M5-005. This is not a Play Console submission and must be re-reviewed before any Play track release, especially if Android foreground services, release signing, privacy-policy copy, third-party SDKs, crash capture, telemetry, accounts, or cloud services change.

Primary Play reference: [Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469).

## Basis Used For This Draft

The current Android implementation was reviewed against:

- `apps/android/app/src/main/AndroidManifest.xml`
- `apps/android/app/build.gradle.kts`
- `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt`
- `apps/android/app/src/main/kotlin/dev/hyphen/android/diagnostics/`
- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/`
- `apps/android/app/src/main/kotlin/dev/hyphen/android/text/`
- `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/`
- `apps/android/app/src/main/kotlin/dev/hyphen/android/trust/`
- `docs/adr/0001-product-scope.md`
- `docs/adr/0003-android-permission-model.md`
- `docs/protocol/threat-model.md`
- `packaging/android-play/play-policy-notes.md`
- `packaging/android-play/fgs-declaration-draft.md`

Google's form guidance currently treats "collection" as user data transmitted from the app off the user's device. It says local-only processing and end-to-end encrypted transfers that are unreadable to anyone except sender and recipient do not need to be disclosed as collected. It also treats direct transfer from the app to another app on the same device as sharing, while carving out user-initiated transfers that the user reasonably expects.

## Current Implementation Boundary

Current manifest permissions and special access surfaces:

- `INTERNET` for LAN sockets and pinned TLS.
- `CHANGE_WIFI_MULTICAST_STATE` for scoped discovery windows.
- `REQUEST_COMPANION_SELF_MANAGED` plus optional companion-device feature.
- `BIND_COMPANION_DEVICE_SERVICE` service.
- `BIND_NOTIFICATION_LISTENER_SERVICE` service, enabled only through Android system settings.

Current Android release build dependencies:

- Runtime dependencies: Android platform APIs and Kotlin standard library via the Android/Kotlin toolchain.
- Test-only dependency: JUnit.
- No analytics SDK, crash-reporting SDK, ads SDK, cloud messaging SDK, account SDK, or social SDK is present in the app module.

Current non-uses:

- No account or sign-in.
- No cloud relay, cloud upload, cloud backup, or developer server.
- No telemetry by default.
- No ad tracking or sale of user data.
- No location, contacts, SMS, Call Log, Accessibility, microphone, camera, or broad media-library permission in the current manifest.
- No persistent notification-history database.

## Recommended Play Console Answers

These are draft answers for the current source state. Use the Play Console wording available at submission time.

| Form area | Draft answer | Rationale |
|---|---|---|
| Does the app collect or share user data? | Yes, conservatively, because users can export diagnostics and may choose to send them to maintainers from Android's share sheet. | Do not overclaim "no data collected" while the app has user-triggered diagnostics export. |
| Data shared with third parties | No for automatic/background sharing. User-triggered diagnostics export opens Android's share sheet and is initiated by the user. | There is no SDK or server-side transfer. The chosen target app is under user control. |
| Data encrypted in transit | Yes for app-to-paired-Mac local companion traffic over pinned TLS. Do not rely on this answer for diagnostics after the user hands JSON to another app; the target app controls onward transport. | Hyphen's protocol uses pinned local TLS. |
| Data deletion mechanism | Local diagnostics can be deleted in-app. There is no Hyphen server account to delete. If users send a diagnostics bundle to maintainers, external support/privacy-policy deletion handling must exist before Play submission. | `Delete diagnostics` clears local logs; server-side deletion is not applicable unless support intake receives bundles. |
| Data collection optional? | Diagnostics export is optional and user-triggered. Core local companion transfer works without sending diagnostics to the developer. | Diagnostics preview/export/delete controls exist; beta diagnostics toggle is default-off. |

## Data Types To Declare

### App Info And Performance -> Diagnostics

Declare as optional, user-triggered, and used for app functionality / diagnostics.

Current diagnostics export may include:

- Schema/version marker.
- Generation timestamp.
- Platform string.
- App version.
- Android SDK version.
- Count of diagnostic events.
- Redacted failure events with level, category, failure code, and safe token attributes such as component and operation.
- Local trace IDs only when the default-off beta diagnostics toggle is explicitly enabled.

Current diagnostics export must not include:

- Notification bodies.
- Text/link payload values.
- File contents.
- File paths or filenames from transferred files.
- Full IP addresses or network topology.
- Account identifiers.
- Advertising IDs, IMEI, phone number, contacts, SMS, or Call Log data.

Do not declare automatic crash-log collection yet. No crash-report upload SDK or crash-stack capture path is implemented. If crash stacks are later added to beta diagnostics, update this statement before release.

## Data Types Not Collected By Developer

These data classes are used locally or transferred only between the user's paired Android/Mac endpoints over pinned local TLS. They are not collected by a Hyphen developer service in the current implementation.

| Data class | Current behavior |
|---|---|
| Notification content | Read only after the user enables Notification Listener. Mirrored to the trusted paired Mac while a local session is active. Hidden-body mode can strip body text before send. No persistent history database. |
| Text and links | Sent only after user action to the paired Mac. Received Mac-originated text/link prompts require Android confirmation before copy/open. |
| Files and documents | User-selected transfer payloads are sent only to the paired Mac, with chunk hashes and SHA-256 verification. Current code has no cloud upload. |
| Peer trust records | Stored locally in app-private storage, sealed with Android Keystore-backed AES-GCM where available. Not backed up by Android backup because `allowBackup=false`. |
| Pairing and transport keys | Local device identity and peer fingerprints are used for pinned TLS trust. They are not exported through diagnostics. |
| Local network metadata | LAN host/port values are used for pairing and local transport. They are not sent to a Hyphen server. |

## Data Sharing Notes

Hyphen does not automatically share user data with other companies or organizations.

User-triggered share-sheet exports:

- Diagnostics export uses `ACTION_SEND` with JSON text.
- The user chooses the recipient app.
- The preview step lets the user inspect the bundle before sharing.
- This path should be described in privacy-policy and support copy as "user-initiated diagnostics export."

If future Play review requires the share-sheet handoff to be declared despite the user-initiated exception, declare the optional diagnostics bundle only; do not broaden the declaration to notification bodies, file contents, or text/link payloads unless those are actually included.

## Security Practices

- Local companion traffic is protected by pinned TLS after QR/manual pairing and SAS confirmation.
- Diagnostics are redacted by default.
- Beta diagnostics are default-off and currently affect only local trace-id inclusion in preview/export.
- App-private peer trust storage is encrypted and excluded from Android backup.
- There is no default telemetry endpoint to secure because no telemetry is sent.

## Privacy Policy Inputs

The Play privacy policy should state, in plain language:

- Hyphen is accountless and local-first.
- Hyphen does not use a cloud relay or upload mirrored content by default.
- Notification access is optional and is used only to mirror notifications to the paired Mac over local pinned TLS.
- Text/link/file transfer sends user-selected content only to the paired local peer.
- Local diagnostics can be previewed, exported, and deleted by the user.
- Beta diagnostics are off by default; when enabled, local diagnostics exports may include trace IDs for troubleshooting while sensitive payloads stay redacted.
- If the user sends an exported diagnostics bundle to maintainers, that bundle is then handled outside the app and should be covered by support/privacy-policy retention and deletion terms.

## Submission Blockers

- A final Play privacy policy URL does not exist yet.
- Android foreground service implementation and notification screenshots are not complete.
- `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE*`, `CAMERA`, and future `ACCESS_LOCAL_NETWORK` permission paths are expected by ADR-0003 but are not yet all present in the current manifest.
- HYP-M4-002 and HYP-M4-004 Android manual diagnostics acceptance remain blocked by lack of an attached Android device/emulator.
- Any added SDK must be re-audited for its own data collection and sharing before this draft can be used.
