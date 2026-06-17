# Handoff — Frontend UX improvement plan (macOS + Android)

- **Date:** 2026-06-17
- **Branch:** `main` (working tree has uncommitted changes; see `git status`)
- **Type:** Implementation handoff. This is a plan to execute, not a report to verify.
- **Author context:** Produced from a read-only assessment of both apps. All
  file/line anchors below were read directly; both test suites were run green on
  the current working tree (macOS `swift test` → 159 tests / 0 failures, Android
  `./gradlew test` → BUILD SUCCESSFUL).

> Source-of-truth reminder (`CLAUDE.md`): read
> `docs/project_hyphen_roadmap_tracker_v0_3.md` before claiming any task state;
> code + runnable checks beat stale docs. Stay inside ADR-0001 v1 scope — this is
> **UI realization + usability + polish, not new features**.

---

## 0. Locked decisions (do not relitigate)

| Decision | Choice |
|---|---|
| Android UI framework | **Jetpack Compose + Material 3** |
| Ambition | **Realize the existing design + fix usability.** Do NOT redesign the visual language — reuse the existing dark design tokens/typography. |
| Sequencing | **macOS first (Track A, M-A1→M-A6), then Android (Track B).** |

---

## 1. The core problem (why both UIs are stuck on the same thing)

Both apps ship a polished design shell that is **not bound to live state**. There
is **no observable "connection + activity" model** that the backend feeds and the
UI renders. That single absence is why:

- **macOS** passes `days: []` into the popover, so the timeline always shows
  "暂无活动" in production; the pretty notification/transfer/link timeline only
  exists in `#Preview` sample data. The popover's reply/dismiss buttons are
  stubs. (`apps/macos/Sources/HyphenApp/main.swift:209` `makeMenuBarPopoverView`.)
- **Android** hardcodes a fake timeline and a fake "已连接 MacBook Pro · 延迟
  18ms" summary with no binding to real state, and routes real output to a
  monospace debug log `TextView`.
  (`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:254` and
  `:332`.)

**Phase 0 (the observable model) is the linchpin. Build it first; everything else
is polish on top of it.**

---

## 2. Phase 0 — shared model (concept, implemented per-platform)

Define the same conceptual model on both sides:

- `ConnectionState` — connected / degraded / reconnecting / discovering /
  sleeping / suspended. **macOS already has this enum**: `HyphenConnectionState`
  in `apps/macos/Sources/HyphenApp/DesignSystem/HyphenComponents.swift` — reuse it.
- Peer identity (display name) + optional latency (ms).
- A **bounded** `ActivityFeed`, grouped by day, holding:
  - mirrored notification (app, sender/title, body-or-redacted, time, reply
    actions if any),
  - file transfer in/out (name, size, progress, SHA-256 result),
  - text/link sent or received,
  - pairing events.
  - In-memory only, capped count. **Must not persist notification history**
    (project convention; Android `MainActivity` already keeps no copy and deletes
    completed transfer temp files).

The model is the contract between backend event producers and the UI. Keep the
two platforms' feed semantics aligned so the design reads the same.

---

## 3. Track A — macOS (do this first; zero new dependencies)

### What already exists and must be reused (do NOT rebuild)

- Design system: `HyphenTheme.swift` (dark/light palette tokens),
  `HyphenTypography.swift` (mono is reserved for auditable detail — keep that
  rule), `HyphenComponents.swift` (`StatusDot`, `HyphenCard`, `WindowChrome`,
  button styles, `MonoTag`, `AppGlyph`, `Hairline`). All under
  `apps/macos/Sources/HyphenApp/DesignSystem/`.
- Prebuilt-but-unwired SwiftUI surfaces in `apps/macos/Sources/HyphenApp/UI/`:
  `MenuBarPopoverView.swift` (timeline popover), `MirrorBannerView.swift`
  (+ `MirrorReplyExpandedView`, `MirrorToastView`), `TransferPanelView.swift`,
  `SettingsWindowView.swift`, `PairingWindowView.swift`, `PairingDialogView.swift`.
- An observable-model precedent: `PairingWindowModel` (an observable used by the
  pairing window, defined in `apps/macos/Sources/HyphenApp/UI/AppWindows.swift`
  and driven by `PairingController`).
- Backend senders that the stubs should call:
  `NotificationReplySender` / `NotificationDismissSender` /
  `NotificationPrivacyPolicySender` in
  `apps/macos/Sources/HyphenNotifications/NotificationMirrorReceiver.swift`.
- The orchestrator: `apps/macos/Sources/HyphenApp/PairingController.swift`. It
  currently surfaces **everything through a single `onStatus: (String) -> Void`**
  callback (`hasActiveSession` / `isActive` are exposed). Receivers for transfer,
  notification, and text all funnel into `onStatus(...)` string lines today.

### Milestones (each independently committable + verifiable)

**M-A1 — App-level observable model + event routing**
- Add `HyphenAppModel: ObservableObject` (model it on `PairingWindowModel`). Fields:
  connection state, peer name, latency, bounded `[ActivityDay]`/`[ActivityItem]`.
- Define a structured `ActivityEvent` enum (notification posted/updated/removed,
  transfer started/progress/completed/cancelled, text/link sent/received, pairing
  state changes).
- Extend `PairingController` to **emit `ActivityEvent`s in addition to (or instead
  of) the `onStatus` strings**. Keep `onStatus` for the legacy NSMenu state line
  if convenient, but the model is the new source of truth.
- `AppDelegate` (`main.swift`) owns the `HyphenAppModel` and injects it into the
  popover hosting controller.
- Verify: `swift test` + new unit tests for the model's reducer/append/bounding.

**M-A2 — Bind the popover to real data**
- `makeMenuBarPopoverView` (`main.swift:209`): stop passing `days: []` /
  `latencyMs: nil`. Render the real feed from `HyphenAppModel`.
- Add the missing states: empty ("no activity yet" is fine), connecting,
  reconnecting/degraded, error, and **not-paired** (today the popover collapses
  these). The `HyphenConnectionState` cases already map to colors via
  `state.color(_:)`.
- Verify: launch (`swift run HyphenApp`), left-click the menu-bar item.

**M-A3 — Make reply / dismiss functional**
- The popover's `onReply` / `onDismiss` are stubs that only set the status line
  ("回复需要活动会话"). Wire them to `NotificationReplySender.requestReply(...)`
  and `NotificationDismissSender.requestDismiss(...)` via `PairingController`.
- Use the existing `MirrorReplyExpandedView` for inline reply composition and
  `MirrorToastView` for the result.
- Verify: requires a paired device — mark as live-device residue if no device.

**M-A4 — Promote primary actions into the popover (reduce NSMenu/NSAlert reliance)**
- Today real actions live in the right-click NSMenu + NSAlert dialogs
  (`sendTextLink`, `sendFile`, `managePeers`, diagnostics). Surface "send
  text/file" as an in-popover sheet; render transfer progress via
  `TransferPanelView`; show mirror state via `MirrorBannerView`.
- Keep the NSMenu as the complete fallback (don't delete it).

**M-A5 — Stability / perf cleanup (these are known issues)**
- **Popover global-monitor single-slot leak**: `popoverMonitor` is assigned on
  every open but removed only in `popoverDidClose` (`main.swift:197` add, leak on
  re-open before close). Fix: remove any existing monitor before installing a new
  one; null-guard in `popoverDidClose`.
- **Sync Keychain read on the popover hot path**: every left-click rebuilds the
  popover root and calls `KeychainTrustStore().allPeers()` on the main thread
  (`currentTrustedPeer`). Cache the trusted peer; invalidate on
  pair/forget/reset (those paths already call `refreshSettingsWindow()`).
- **Adopt STAGED `HyphenCard`** in the borderless windows (its comment claims it
  backs them; they currently hand-roll the same stack) and dedupe the
  `NSColor.hyphenHex/hyphenRGBA` helpers in `PairingDialogView.swift` against
  `Color.hex/rgba`.
- **Real-device-verify the already-fixed use-after-free.** See
  `docs/reports/macos-crash-fix-handoff-2026-06-16.md` — `DesignWindowController`
  teardown was fixed (defer one runloop tick), build+test green, but NOT confirmed
  on device. **Do not revert to synchronous teardown** (project memory note
  `macos-hosting-view-teardown-gotcha`). Run the three repro flows: close Settings
  via red dot / Escape; toggle the diagnostics switch; pairing confirm/reject.

**M-A6 — Polish**
- Light/dark parity walkthrough, motion consistency, VoiceOver labels, Dynamic
  Type, string localization. Keep mono-for-auditable-detail.

---

## 4. Track B — Android (after Track A; Jetpack Compose + Material 3)

Current state: a single 1533-line `MainActivity.kt` hand-builds the entire UI from
classic Views (LinearLayout/TextView/Button + GradientDrawable). **No Compose, no
XML layouts, no `res/` resource dir.** Color tokens are companion constants
(`HYPHEN_CANVAS`, `HYPHEN_ACCENT`, etc.). Timeline + connection summary are
hardcoded demo data.

### Dependency gate (STOP and get explicit confirmation here)

`CLAUDE.md` / ADR-0005 require a license note + user confirmation before adding
dependencies. Compose/Material 3 is a large new dependency surface (Apache-2.0).
**Before editing `build.gradle`:**
- **M-B-prep**: write an ADR (new ADR-0007 or amend ADR-0005) recording the
  Compose BOM + Material3 license (Apache-2.0) and dependency footprint; get the
  user's explicit go-ahead; then add the deps.
- **Port, don't redesign**: move the existing dark tokens (mirror of macOS
  `HyphenPalette`) into a Compose `ColorScheme` + custom `Typography`. Visual
  language is unchanged — Compose is only the new rendering host.

### Milestones

- **B0 — Split the monolith**: separate `MainActivity` into controller / UI /
  state-holder (e.g. a `ViewModel`-style holder). This is a prerequisite for
  everything else.
- **B1 — State model + delete fake data**: build the Phase-0-aligned state holder
  fed by the session and the notification/transfer/text receivers. **Delete the
  hardcoded timeline (`MainActivity.kt:254`) and fake connection summary
  (`:332`).**
- **B2 — Rebuild screens in Compose**: home/timeline, pairing (QR + manual +
  SAS), permission onboarding, settings, diagnostics. Reuse the ported tokens.
  Add empty / connecting / error / permission-denied states.
- **B3 — Real interactions**: reply / dismiss / send text+file / transfer progress
  + cancel, all bound to state — no more "append to event log only".
- **B4 — i18n + accessibility**: extract the mixed CN/EN copy into string
  resources (support zh + en), add contentDescription, ≥48dp touch targets,
  TalkBack support, light/dark theme.
- **B5 — Polish**: visual parity with the design handoff; add `@Preview`s.

---

## 5. Cross-cutting (both platforms)

- **Empty / loading / error / permission-denied states** — currently the UIs
  assume "connected". This is the biggest usability gap; treat it as first-class,
  not an afterthought.
- **Copy / i18n consistency** — both apps currently mix Chinese and English.
- **Accessibility** — VoiceOver/TalkBack, contrast, touch targets, Dynamic Type.
- **Keep auditable-detail rule** — monospace stays reserved for fingerprints /
  SAS / IPs / sizes / timestamps.
- **Verification honesty** — features needing a real paired device (reply,
  mirror, transfer E2E) must be marked as live-device residue, not silently
  checked off. This matches the tracker's existing `[?]` discipline.

---

## 6. Verification commands

```
# macOS (narrow → broad)
cd apps/macos && swift build && swift test       # 159 tests today
swift run HyphenApp                              # runtime / crash verification

# Android
cd apps/android && ./gradlew test                # JVM unit tests
cd apps/android && ./gradlew assembleDebug       # debug APK build

# whole repo
./scripts/check.sh
```

After each milestone: run the narrow check, then `./scripts/check.sh`. Update the
roadmap tracker row / progress log if a task's state changes.

---

## 7. Suggested first step

Start at **M-A1** (macOS observable model + event routing). It is the shared
foundation, has a controlled blast radius, and adds no dependencies. Land
M-A1→M-A6, then open the Android dependency gate (M-B-prep) with the user before
touching `build.gradle`.

## 8. Boundaries / do-not

- Stay within ADR-0001 v1 scope — no new product features.
- No new dependencies without an ADR + explicit user confirmation (gates Track B).
- Do not revert the `DesignWindowController` async teardown fix.
- Do not convert missing real-device evidence into a passing/`[x]` state.
- Preserve unrelated working-tree changes; commit only when asked, scoped tightly.
