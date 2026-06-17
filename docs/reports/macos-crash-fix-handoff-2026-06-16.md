# Handoff — macOS app "crashes easily" fix + remaining findings

- **Date:** 2026-06-16
- **Branch:** `main` (1 commit ahead of `origin/main` at `c0dac50`)
- **Area:** `apps/macos/` — SwiftUI design surfaces hosted in the AppKit menu-bar app
- **Status of the crash:** **FIXED** in the working tree (uncommitted). Build + tests green. Not yet runtime-verified on device.

---

## 1. Context

A large SwiftUI UI surface was just landed (commit `c0dac50`) on top of the AppKit
menu-bar app, with further uncommitted edits in the working tree. The user reported
the macOS app "crashes easily" at runtime and asked for the cause + a fix.

Root cause was found and fixed. This doc lets you (a) confirm/verify the fix on a real
machine, and (b) pick up the remaining (unfixed) review findings.

Source-of-truth reminders (per `CLAUDE.md`): read `docs/project_hyphen_roadmap_tracker_v0_3.md`
before claiming task state; code + runnable checks beat stale docs.

---

## 2. The crash (FIXED)

**Use-after-free in the SwiftUI-in-AppKit window bridge `DesignWindowController`**
(`apps/macos/Sources/HyphenApp/UI/AppWindows.swift`).

The borderless Settings/Pairing windows host their SwiftUI content in an
`NSHostingController` whose **only strong reference** is `DesignWindowController.window`.
Several SwiftUI buttons *inside* those windows call back into the controller and
**synchronously** tear down or replace that hosting controller from within their own
action handler — freeing the `NSHostingView` that is still mid-dispatch. AppKit then
returns into freed memory → `EXC_BAD_ACCESS`.

Two trivially-reproducible entry points:

1. `close()` (`window = nil`) — reached by the window-chrome red close dot, the Escape
   monitor, and the pairing **confirm / reject** buttons.
   Repro: open Settings from the menu-bar popover → click the red close dot → crash.
2. `present()` existing-window branch (synchronous `window.contentViewController = …`) —
   reached by `refreshSettingsWindow()` when you toggle the trace-IDs / beta-diagnostics
   switch inside Settings (`onRequestTraceIds → setBetaDiagnosticsEnabled →
   refreshSettingsWindow`).
   Repro: open Settings → flip the diagnostics switch → crash.

### The fix

Single mechanism-level change in `DesignWindowController` — defer the order-out and the
controller swap to the next runloop tick, capturing `window` so it (and its hosting
controller) stays alive until the SwiftUI action frame unwinds. One fix covers every
entry point (close button, Escape, confirm, reject, settings refresh) instead of
sprinkling `async` at each call site.

- `close()`: `guard let window else { return }`, set `self.window = nil`, then
  `DispatchQueue.main.async { window.orderOut(nil) }`.
- `present()` existing-window branch: build the `NSHostingController` now, then
  `DispatchQueue.main.async { window.contentViewController = hosting }`.

`NSPopover.performClose` (the menu-bar popover) is already safe — it does not dealloc its
`contentViewController` synchronously — so the popover path was intentionally left alone.

### Verification done
- `cd apps/macos && swift build` → clean.
- `swift test` → 148/148 pass.
- **NOT done:** on-device runtime confirmation. This is a UI timing use-after-free the
  headless XCTest suite cannot exercise.

### What you should verify (please do this)
Launch the app and confirm the previously-crashing flows no longer crash:
1. Menu-bar left-click → popover opens; click ⚙ to open Settings → click the red close
   dot. Should close cleanly. Repeat with Escape.
2. Open Settings → toggle the diagnostics / trace-IDs switch a few times. No crash.
3. Pairing window: trigger pairing, then exercise close / Escape / confirm / reject.
Run from `apps/macos` (e.g. `swift run HyphenApp`) or build the app bundle via
`./packaging/macos/package-local.sh`.

### Constraints
- **Do not revert to synchronous teardown.** A project memory note records this gotcha
  (`macos-hosting-view-teardown-gotcha`). Any future code that tears down or replaces a
  hosting controller reachable from a SwiftUI action it hosts must defer one runloop tick.

---

## 3. Remaining findings (NOT fixed — kept the change surgical to the crash)

Ranked. Items 1–2 are the fixed crash; below are open.

### 3a. Popover global event monitor — single-slot leak (lower-confidence crash)
- **File:** `apps/macos/Sources/HyphenApp/main.swift` (~line 185 add, ~689 remove in
  `popoverDidClose`).
- **Issue:** `popoverMonitor` is assigned on every popover open but removed only in
  `popoverDidClose`. An open that happens before the prior close completes overwrites and
  permanently leaks monitor A, whose closure captures `self` and keeps firing
  `closePopover()` after its expected lifetime.
- **Fix shape:** remove any existing monitor before installing a new one; null-guard in
  `popoverDidClose`. Real but rarer than the fixed crashes.

### 3b. `LocalNetworkDialogActionButton` — native AppKit button bandaid (altitude/reuse)
- **File:** `apps/macos/Sources/HyphenApp/UI/PairingDialogView.swift` (~line 108–225).
- **Issue:** a ~100-line `NSViewRepresentable` button with a hand-copied dark/light
  palette, duplicating `AccentButtonStyle`/`SecondaryButtonStyle` and `HyphenPalette`.
  `PairingWindowView` uses plain SwiftUI buttons in the same kind of borderless host, so
  this looks like a workaround for a SwiftUI-button-in-borderless-window problem.
- **DO NOT remove blindly.** `AppWindows.swift` `LocalNetworkDialog.runModal()` sets
  `isMovableByWindowBackground = false` (the pairing/settings windows use `true`).
  Whether plain SwiftUI buttons work here depends on that flag — must be verified on
  device before replacing the native button. If they work, delete the representable + its
  private `NSColor.hyphenHex/hyphenRGBA` helpers and use the shared ButtonStyles.

### 3c. Duplicated `NSColor` hex/rgba helpers (reuse)
- **File:** `apps/macos/Sources/HyphenApp/UI/PairingDialogView.swift` (~line 212).
- **Issue:** `NSColor.hyphenHex/hyphenRGBA` re-implement `Color.hex/Color.rgba` in
  `HyphenTheme.swift`. Only needed by 3b; remove together with it.

### 3d. Synchronous Keychain read on the popover hot path (efficiency)
- **File:** `apps/macos/Sources/HyphenApp/main.swift` (`makeMenuBarPopoverView` ~line 198,
  `settingsView` ~line 252; `currentTrustedPeer` ~line 280).
- **Issue:** every menu-bar left-click rebuilds the popover root, each doing a main-thread
  `KeychainTrustStore().allPeers()`. Under a locked keychain this blocks the UI.
- **Fix shape:** cache the trusted peer, invalidate on pair/forget/reset (which already
  call `refreshSettingsWindow()`).

### 3e. Unused `HyphenCard` + misleading doc-comment (simplification)
- **File:** `apps/macos/Sources/HyphenApp/DesignSystem/HyphenComponents.swift` (~line 80).
- **Issue:** `HyphenCard` has zero call sites; its comment claims it backs the borderless
  windows, which actually hand-roll the same surface+hairline+clip stack inline. Either
  adopt it in the windows or drop it with its comment. (Note the file marks it `STAGED`
  intentional-foundation — confirm intent before deleting.)

---

## 4. Files touched this session
- `apps/macos/Sources/HyphenApp/UI/AppWindows.swift` — the crash fix (uncommitted,
  `+39 / -3`).
- Project memory (out of repo): `macos-hosting-view-teardown-gotcha` note +
  `MEMORY.md` index pointer.

No commit was made (no explicit request). If you commit, scope it to `AppWindows.swift`
and preserve the other unrelated working-tree changes already present.

## 5. Build / verify commands
```
cd apps/macos && swift build && swift test      # narrow gate (148 tests)
./scripts/check.sh                               # whole-repo gate
swift run HyphenApp                              # launch for runtime crash verification
```
