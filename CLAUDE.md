# CLAUDE.md — Project Hyphen repo rules

Project Hyphen is an open-source, local-first, auditable Android companion layer for macOS (menu-bar Mac app + Android app, paired over LAN TLS). Pre-alpha: M0 docs/skeleton phase.

## Source of truth, in order

1. `docs/project_hyphen_roadmap_tracker_v0_3.md` — implementation status, task selection, progress log. Update it with every task.
2. `docs/adr/` — accepted decisions. ADR-0001 freezes v1 scope; scope changes require a new superseding ADR.
3. `docs/project_hyphen_plan_v0_3_en.md` (EN) / `_zh.md` (中文) — design intent and rationale.

Code wins over docs when they disagree; flag the mismatch and fix the doc.

## Task selection (agent operating rule)

1. Pick the next unchecked P0 task with no unmet dependency; else next P1.
2. Implement the smallest coherent slice. Platform-risk burn-down before UI polish.
3. Run the narrowest relevant checks first, then `./scripts/check.sh`.
4. Update the tracker row + progress log; add/update ADRs and protocol docs when behavior changes.
5. Commit if green, message prefixed with the task ID: `HYP-Mx-NNN: summary`.
6. If blocked by credentials, devices, signing accounts, store access, or OS availability: mark the row `[?]` with the exact blocker and move on.

## Check and test commands

| Scope | Command | Status |
|---|---|---|
| Whole repo | `./scripts/check.sh` | available: md link check + secret scan; platform checks SKIP until M1/M2 |
| Protocol schemas/vectors | `./scripts/test-protocol.sh` | available: schema-subset validator over `protocol/test-vectors/` |
| Android | `cd apps/android && ./gradlew test assembleDebug` | available |
| macOS | `cd apps/macos && swift test` (xcodebuild scheme `Hyphen` also works) | available |

Until those exist, verify docs changes by: relative links resolve, tables render, claims match implementation reality.

## Forbidden actions

- **No cloud relay, accounts, or NAT relay code.** Local-first is frozen (ADR-0001).
- **No telemetry or crash upload by default.** Diagnostics are local, redacted, opt-in export only.
- **No SMS / Call Log permissions, no Accessibility services, no private APIs, no background clipboard listening.**
- **No GPL code copying** (KDE Connect included). Clean-room reimplementation only; cite behavior sources.
- **No secrets in the repo**: no signing keys, tokens, credentials, or personal data — including test fixtures and logs.
- **No destructive commands**: no force-push, no history rewrite, no `rm -rf` outside scratch paths, no `git push` unless explicitly requested by a human.
- **No new dependencies** without recording the license in the dependency audit notes (GPL/AGPL are incompatible with the intended MPL/Apache direction).
- **No license file** until HYP-M0-015 decides it; don't front-run.
- Don't edit `docs/project_hyphen_plan_v0_3_*.md` (historical plan record) except to fix factual errors with a note.

## Conventions

- Notification identity: `StatusBarNotification.getKey()` is the primary key; never include `postTime` in identity.
- Discovery is not trust: mDNS/Bonjour results are hints; only pinned fingerprints + SAS confirmation establish trust.
- Every permission request maps to a visible user action with rationale copy.
- Protocol envelope is JSON (auditable); see `docs/protocol/` once HYP-M0-007 lands.
