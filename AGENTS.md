# Project Hyphen Agent Instructions

`AGENTS.md` and `CLAUDE.md` are mirrors. Keep them byte-identical unless a
tool-specific exception is added and explained.

Project Hyphen is a local-first Android companion layer for macOS: a menu-bar
Mac app plus an Android app paired over LAN TLS. Do not put milestone progress,
current readiness, or implementation snapshots in this root file.

## Sources

- Tracker: `docs/project_hyphen_roadmap_tracker_v0_3.md` for task state,
  blockers, verification notes, and progress log.
- ADRs: `docs/adr/` for accepted architecture and scope decisions.
- Protocol: `docs/protocol/`, `protocol/schema/`, and test vectors for wire
  behavior and compatibility contracts.
- Plan: `docs/project_hyphen_plan_v0_3_en.md` / `_zh.md` for design intent;
  edit only for factual correction with a note.

Code and runnable checks beat stale docs. When they disagree, flag the mismatch
and update the appropriate source of truth.

## Workflow

- Start repo work by confirming `pwd`, git root, and
  `git status --short --branch`.
- Preserve unrelated dirty or staged changes. Never revert user work unless
  explicitly asked.
- Roadmap work: use the next unchecked unblocked P0 row, then P1, unless the
  user names another target. Implement the smallest coherent slice.
- Bug/failure work: read the complete error and relevant code before fixing.
  Report the actual command, error, file, and line; add a focused reproducing
  test when practical.
- Read-only/audit/review work: do not edit, stage, install dependencies, or
  mutate external state. Verify third-party claims against files, diffs, checks,
  or runtime evidence.
- Update tracker/docs/ADRs/protocol only when behavior, evidence, or source
  state changes.
- Commit or push only when the user explicitly asks.

## Verification

Use the narrowest relevant check first.

- Whole repo: `./scripts/check.sh`
- Protocol: `./scripts/test-protocol.sh`
- Android: `cd apps/android && ./gradlew test assembleDebug`
- macOS: `cd apps/macos && swift build && swift test`
- Packaging/release: use local dry-run scripts under `packaging/`

If checks need unavailable devices, credentials, signing accounts, store access,
OS images, or unsafe host actions, report the exact blocker. Keep local green,
device/runtime proof, and release readiness separate.

## Safety

- v1 has no cloud relay, accounts, NAT relay, server dependency, telemetry, or
  default crash upload.
- Do not add SMS/Call Log permissions, Accessibility services, private APIs,
  root requirements, background clipboard listening, screen control, or remote
  control without an approved scope decision.
- Do not copy GPL/AGPL code; external projects are behavior references only.
- New dependencies need explicit approval and license review notes.
- Formal root license, NOTICE, SPDX, or contribution-policy files are changed
  only for license/release-readiness tasks.
- Do not commit secrets, signing keys, tokens, credentials, personal data,
  private logs, `.env*`, or generated runtime artifacts.
- Do not run destructive cleanup, force pushes, or history rewrites unless the
  user explicitly asks.

## Context Hygiene

- Prefer `rg` / `rg --files` for exploration.
- Avoid generated, dependency, build, cache, runtime-state, private-data, and
  secret trees unless the task is specifically about them.
- Do not read or print `.env*`, credentials, keys, tokens, local signing
  material, private account data, or private runtime logs.
- Common avoid-list: `.git/`, `apps/android/.gradle/`, `apps/android/build/`,
  `apps/android/local.properties`, `apps/macos/.build/`,
  `apps/macos/.swiftpm/`, `packaging/*/build/`, `.DS_Store`.

Let agents discover repository structure, module details, and implementation
facts from current files when a task needs them.
