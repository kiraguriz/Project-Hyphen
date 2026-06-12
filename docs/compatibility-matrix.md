# Compatibility Matrix

Status: blank execution matrix for HYP-M0-014. Rows below are coverage targets
and log templates, not completed test results.

## Recording Rules

- Record only observed results from the named device, OS, network, build, and
  scenario. Do not infer from a similar device family.
- Keep raw evidence links local and redacted: screenshots, logs, diagnostics
  exports, commit hashes, APK checksums, macOS build IDs, and test notes.
- Use `pass`, `fail`, `blocked`, or `not-run` as the result.
- If a result is blocked, write the exact blocker: missing device, missing OS
  image, permission unavailable, account/signing gate, or human scheduling.
- Keep notification text, file contents, URLs, IP addresses, and personal data
  out of this document.

## Android Device Matrix

| Date | Build | Device family | Device model | Android version | OEM skin | Track | Network case | Scenario | Result | Evidence / notes |
|---|---|---|---|---|---|---|---|---|---|---|
| YYYY-MM-DD | TBD | Pixel | TBD | Android 14 | AOSP/Pixel | GitHub/F-Droid | Home Wi-Fi | First pairing | not-run |  |
| YYYY-MM-DD | TBD | Pixel | TBD | Android 15 | AOSP/Pixel | GitHub/F-Droid | Home Wi-Fi | Notification mirror/update/remove | not-run |  |
| YYYY-MM-DD | TBD | Pixel | TBD | Android 16 | AOSP/Pixel | GitHub/F-Droid | Restricted LAN mode | Discovery fallback | not-run |  |
| YYYY-MM-DD | TBD | Pixel | TBD | Android 17 preview | AOSP/Pixel | GitHub/F-Droid | Local-network permission | Permission deny/allow flow | not-run |  |
| YYYY-MM-DD | TBD | Samsung | TBD | Android 15/16 | One UI | GitHub/F-Droid | Mesh Wi-Fi | Foreground service + notification mirror | not-run |  |
| YYYY-MM-DD | TBD | Xiaomi | TBD | Android 15/16 | HyperOS | GitHub/F-Droid | AP isolation | QR/manual fallback | not-run |  |
| YYYY-MM-DD | TBD | OnePlus/Oppo | TBD | Android 15/16 | OxygenOS/ColorOS | GitHub/F-Droid | Hotspot | Transfer resume | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | Android 15/16 | Battery-restricted OEM | GitHub/F-Droid | Home Wi-Fi | Reconnect after background restriction | not-run |  |

## macOS Matrix

| Date | Build | Mac family | Mac model | macOS version | Network case | Scenario | Result | Evidence / notes |
|---|---|---|---|---|---|---|---|---|
| YYYY-MM-DD | TBD | Apple silicon | TBD | 15.1+ | Home Wi-Fi | First pairing + local-network prompt | not-run |  |
| YYYY-MM-DD | TBD | Apple silicon | TBD | Latest stable | Mesh Wi-Fi | Notification mirror/update/remove | not-run |  |
| YYYY-MM-DD | TBD | Intel | TBD | 15.1+ | Hotspot | Text/link + transfer resume | not-run |  |
| YYYY-MM-DD | TBD | Apple silicon | TBD | Latest stable | Home Wi-Fi | 20 sleep/wake reconnect cycles | blocked | Needs explicit human scheduling; see HYP-M4-007. |

## Network Matrix

| Date | Build | Android device | Mac device | Network case | Expected behavior | Result | Evidence / notes |
|---|---|---|---|---|---|---|---|
| YYYY-MM-DD | TBD | TBD | TBD | Same SSID home Wi-Fi | mDNS accelerates discovery; pinned TLS establishes session | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | Mesh Wi-Fi | Discovery works or QR/manual fallback is clear | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | AP/client isolation | QR/manual fallback is primary; failure is visible | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | Phone hotspot | Manual endpoint path is usable | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | Android restricted LAN mode | No crash; clear fallback and denied/disconnected state | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | Permission denied | QR/manual remains available where applicable | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | Wi-Fi switch during session | Reconnect or clear degraded state within target window | not-run |  |

## Scenario Matrix

| Date | Build | Android device | Mac device | Scenario | Acceptance target | Result | Evidence / notes |
|---|---|---|---|---|---|---|---|
| YYYY-MM-DD | TBD | TBD | TBD | First QR pairing + SAS confirmation | Trust persists only after explicit confirmation | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | SAS mismatch/reject drill | No trust record is written | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | Notification post/update/remove | Stable key prevents duplicates | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | Notification dismiss action | Phone notification clears or error is visible | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | RemoteInput quick reply | Works only for tested app family; unsupported remains hidden | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | Android to Mac text/link | User confirmation before paste/open | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | Mac to Android text/link | User confirmation before paste/open | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | File transfer interruption/resume | Integrity check passes after resume | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | 1GB transfer | Completes or resumes with visible progress/error | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | Mac sleep/wake | Reconnect within 30s or clear error | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | Android battery saver/background restriction | Foreground notification and reconnect state are clear | not-run |  |
| YYYY-MM-DD | TBD | TBD | TBD | Diagnostics preview/export/delete | Redacted export only; delete clears local history | not-run |  |

## Evidence Log

| Date | Tester | Build / commit | Artifact | Scope | Notes |
|---|---|---|---|---|---|
| 2026-06-12 | Codex | 79c75a1 | `/Users/haitianzhu/Library/Android/sdk/platform-tools/adb devices -l` | HYP-M4-005 Android device matrix | blocked: no attached Android devices or emulators were listed, so the first five OS/OEM/network rows could not be observed. |
| 2026-06-12 | Codex | 8a086cc | `sw_vers` | HYP-M4-006 macOS matrix | blocked: current host is macOS 26.5.1 build 25F80, but two additional macOS OS/device combinations and a paired Android session are unavailable. |
| YYYY-MM-DD | TBD | TBD | TBD | TBD |  |
