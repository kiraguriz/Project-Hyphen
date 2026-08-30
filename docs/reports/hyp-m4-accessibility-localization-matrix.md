# HYP-M4 Accessibility and Localization Matrix

- **Date**: 2026-08-30
- **Scope**: Android and macOS beta-critical pairing, transfer, privacy, and
  diagnostics surfaces
- **Locales**: English and Simplified Chinese
- **Evidence rule**: source/automated checks, emulator observation, and
  assistive-technology or physical-device proof are separate layers.

## Outcome

The repository now enforces English/Simplified-Chinese key parity on both
platforms, removes the remaining literal Android service label, uses the
standard Material 3 labelled editable control for Android text inputs, and assigns stable
macOS accessibility identifiers to the pairing and diagnostics controls.

Android first-use onboarding was observed in both English and Simplified
Chinese on a Pixel 10 Pro XL Android 17 emulator. The post-fix UI hierarchy
contained one focusable `EditText` with a non-focusable localized label and no
separate content-description wrapper, and a 200%
font-scale smoke showed the visible first-use content wrapping without observed
clipping. These observations are not TalkBack, VoiceOver, physical-device, or
end-to-end pairing proof.

## Evidence Matrix

| Platform / surface | Automated invariant | Runtime evidence | Open evidence |
|---|---|---|---|
| Android resource tables | Default and `values-en` tables have identical keys. | English and Simplified-Chinese first-use copy rendered on the Android 17 emulator. | Review on supported physical-device/OEM matrix. |
| Android manifest labels | Every `android:label` is a `@string/...` reference and the notification-listener label exists in both locales. | App installed and launched successfully after the manifest change. | Confirm the localized service name in each OEM's Notification access Settings screen. |
| Android first-use pairing input | Source contract requires Material 3's labelled editable control and rejects a custom content-description wrapper that can split the name from editable state/actions. | Post-fix UI hierarchy contained one focusable `EditText` with its localized non-focusable label in English; the earlier bilingual pass confirmed both localized strings render. | TalkBack announcement, editing, IME action, error handling, and external-camera return. |
| Android timeline send input | Same text-field source contract and localized label as first use. | Not reached without a paired Mac. | TalkBack and 200% font-scale run in a real paired session. |
| Android manual-pairing input | Same text-field source contract and localized label as first use. | Not reached in this first-use emulator state. | TalkBack and 200% font-scale run after onboarding. |
| Android 200% font scale | Text field uses a persistent label and scrolling content rather than a placeholder-only name. | Visible first-use content wrapped without observed clipping on the emulator; font scale was restored to `1.0` afterward. | Scroll every screen and dialog, test landscape, smallest supported display, and at least one physical device. |
| macOS localization tables | English and Simplified-Chinese `.strings` files have identical keys. | No localized app runtime was exercised in this phase. | Launch both locales and inspect truncation in light/dark appearance. |
| macOS pairing QR, payload, SAS, confirm/reject | Stable unique accessibility identifiers are unit-tested; QR and SAS have explicit localized labels/values. | Not exercised because no paired Android/Mac session was created. | VoiceOver focus order and announcements, full keyboard operation, SAS comparison, mismatch, disabled-button state, and Dynamic Type/zoom observation. |
| macOS diagnostics trace, preview, export/delete | Stable unique accessibility identifiers are unit-tested; trace control has a localized label/hint and preview has a localized container label. | Not exercised in the macOS app UI. | VoiceOver navigation, keyboard operation, save panel, destructive-action confirmation, and both locales. |

## Commands and Observations

Automated checks:

```text
cd apps/android
./gradlew testDebugUnitTest \
  --tests dev.hyphen.android.ui.AccessibilityLocalizationContractTest \
  --max-workers=1

cd apps/macos
swift test --filter 'LocalizationTests|AccessibilityContractTests'
```

Android emulator observation:

```text
/Users/haitianzhu/Library/Android/sdk/platform-tools/adb devices -l
cd apps/android && ./gradlew installDebug --max-workers=1 --console=plain
adb -s emulator-5554 shell am start -n dev.hyphen.android/.MainActivity
adb -s emulator-5554 shell uiautomator dump ...
adb -s emulator-5554 shell cmd locale set-app-locales dev.hyphen.android --locales zh-Hans
```

- Target: `Pixel_10_Pro_XL(AVD)`, Android 17, `emulator-5554`.
- English and Simplified-Chinese first-use screens rendered.
- After the adversarial fix, the input hierarchy contained one focusable
  `EditText` with its localized label as a non-focusable child and no separate
  content-description node. This is stronger runtime structure evidence, but
  still not a TalkBack announcement claim.
- A screenshot of the visible first-use region was inspected at 100% and 200%
  font scale. No clipping was observed in that region.
- Per-app locale was restored to the empty override, the app was relaunched,
  and system font scale was confirmed as `1.0` after the test.
- Ephemeral UI trees and screenshots were not added to the repository because
  they are runtime artifacts rather than portable compatibility evidence.

## Gate Status

- **Automated localization/accessibility contracts**: pass.
- **Android emulator bilingual first-use smoke**: pass for the observed screen.
- **Android 200% visible-region smoke**: pass for the observed screen.
- **TalkBack**: not run; no announcement/focus-order claim.
- **VoiceOver and macOS bilingual runtime**: not run.
- **Physical Android, real pairing, notification-access Settings, and full
  device/OS matrices**: blocked at their manual evidence layers.

HYP-M4-005 and HYP-M4-006 therefore remain `[?]`. This phase strengthens the
implementation and gate artifacts but does not convert one emulator smoke into
the required five-device Android or three-combination macOS matrices.
