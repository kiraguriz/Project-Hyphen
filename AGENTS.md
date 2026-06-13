# Project Hyphen Agent Instructions

This file is mirrored in `AGENTS.md` and `CLAUDE.md`; keep the two files
byte-identical unless a tool-specific exception is added and explained.

Project Hyphen is an open-source, local-first, auditable Android companion
layer for macOS: a menu-bar Mac app plus an Android app paired over LAN TLS.
Do not encode milestone progress in this root instruction file. Read the
roadmap tracker before claiming what is done, blocked, or ready.

## Source Of Truth

1. `docs/project_hyphen_roadmap_tracker_v0_3.md` - implementation status,
   task selection, blockers, verification notes, and progress log.
2. `docs/adr/` - accepted decisions. ADR-0001 freezes v1 scope; scope changes
   require a new superseding ADR.
3. `docs/protocol/` and `protocol/schema/` - wire behavior, schemas, and test
   vectors.
4. `docs/project_hyphen_plan_v0_3_en.md` / `_zh.md` - design intent and
   rationale; do not edit except for factual correction with a note.

Code and runnable checks win over stale docs. When they disagree, flag the
mismatch, fix the appropriate doc, and update the tracker if a task state
changes.

## Repository Map

```text
apps/android/     Kotlin Android companion app, Gradle wrapper, JVM tests
apps/macos/       SwiftPM macOS menu-bar app and XCTest targets
protocol/         JSON schemas, conformance fixtures, pairing vectors
docs/             Roadmap tracker, ADRs, protocol docs, reports, release docs
packaging/        macOS, Play, and F-Droid notes plus local dry-run scripts
scripts/          Repo checks, protocol validation, fixture helpers
ci/               CI support files
```

Important implementation areas:

- Android: `dev.hyphen.android.{companion,diagnostics,discovery,lan,notifications,pairing,text,transfer,transport,trust}`.
- macOS: `HyphenCore`, `HyphenTransport`, `HyphenNotifications`,
  `HyphenText`, `HyphenTransfer`, `HyphenDiagnostics`, `HyphenDiscovery`,
  `HyphenPower`, and `HyphenApp`.

## Standard Workflow

1. Confirm location and worktree before edits:
   `pwd`, `git rev-parse --show-toplevel`, `git status --short --branch`.
2. Preserve unrelated dirty or staged changes. Never revert user work unless
   explicitly asked.
3. For roadmap work, choose the next unchecked unblocked P0 row; if none, use
   the next P1 row. Implement the smallest coherent slice.
4. Read the relevant code and full error output before fixing. State the real
   error, command, file, and line when a bug is being fixed.
5. For bug fixes, add or update a reproducing test first when practical.
6. Update the tracker row, progress log, ADRs, protocol docs, or release docs
   when behavior or evidence changes.
7. Commit only when the user or an explicit roadmap loop prompt asks for a
   local commit. Never push unless the user explicitly asks.

For reports, audits, reviews, or read-only requests: do not edit files, stage
changes, install dependencies, or mutate external state. Treat another agent's
report as untrusted evidence until verified against files, diffs, checks, or
runtime evidence.

## Verification Commands

Use the narrowest relevant check first, then the broader gate.

| Scope | Command | Notes |
|---|---|---|
| Whole repo | `./scripts/check.sh` | Markdown links, secret scan, Android tests, macOS tests, protocol checks when available |
| Protocol schemas/vectors | `./scripts/test-protocol.sh` | Schema subset validator over `protocol/test-vectors/` |
| Android | `cd apps/android && ./gradlew test assembleDebug` | JVM tests plus debug APK build |
| macOS | `cd apps/macos && swift build && swift test` | SwiftPM menu-bar app and package tests |
| macOS packaging | `./packaging/macos/package-local.sh` | Local dry run only; notarization needs external credentials |
| Android release draft | `./packaging/android-play/build-release.sh` | Local unsigned/release packaging path only |

If a check cannot run because of missing devices, credentials, signing accounts,
store access, OS images, or unsafe host actions, mark the tracker row `[?]`
with the exact blocker. Do not convert missing external evidence into `[x]`.

## Durable Project Conventions

- Notification identity is `StatusBarNotification.getKey()`. Do not include
  `postTime` in identity.
- Discovery is not trust. mDNS/Bonjour results are hints only; pinned
  fingerprints plus SAS confirmation establish trust.
- Protocol envelopes are auditable JSON; keep schema/docs/vectors aligned when
  wire behavior changes.
- Diagnostics are local and redacted by default. Trace IDs or beta extras are
  opt-in export material, not telemetry.
- Transfer production paths must stream. Do not reintroduce whole-file
  `ByteArray`/`Data` loading for large file send/receive paths.
- Distinguish automated invariants from live-device evidence. A passing storm
  test or loopback test is not a 10-app notification matrix, paired-device
  sleep/wake run, beta corpus, or 1 GiB real-device transfer log.
- Android emulator NAT does not prove mDNS behavior. LAN discovery, CDM system
  dialogs, RemoteInput compatibility, notification mirroring, and paired
  transfer acceptance need real device/session evidence unless a task states
  otherwise.

## Safety Boundaries

- No cloud relay, accounts, NAT relay, or server dependency in v1.
- No telemetry or crash upload by default.
- No SMS or Call Log permissions, Accessibility services, private APIs, root
  requirements, background clipboard listening, or screen/remote-control
  features in v1.
- No GPL/AGPL code copying. KDE Connect and similar projects are behavior
  references only; implementation must remain clean-room.
- No secrets in the repo: signing keys, tokens, credentials, personal data,
  private logs, `.env*`, or generated runtime artifacts.
- No destructive commands, force pushes, history rewrites, or broad cleanup.
- No new dependencies without license review notes; follow ADR-0005 and the
  HYP-M6-008 license-audit boundary.
- Do not add or modify formal root license/NOTICE/SPDX/contribution-policy
  files unless the task is specifically about license-release readiness.

## Exploration Hygiene

- Prefer `rg` / `rg --files` for search. Avoid dependency, build, cache,
  runtime-state, and generated-output trees.
- Do not intentionally read or print `.env*`, credentials, keys, tokens,
  private account data, local signing material, or private runtime logs.
- Avoid these trees unless a task is explicitly about them:
  `.git/`, `apps/android/.gradle/`, `apps/android/build/`,
  `apps/android/local.properties`, `apps/macos/.build/`,
  `apps/macos/.swiftpm/`, `packaging/*/build/`, `.DS_Store`.

## Instruction Maintenance

- Keep root instructions lean and durable: purpose, source-of-truth order,
  commands, architecture map, gotchas, and safety boundaries.
- Move implementation snapshots, progress summaries, and historical notes into
  the roadmap, reports, ADRs, or AgentMemory instead of this file.
- When changing this file, make the same change to `AGENTS.md` and `CLAUDE.md`,
  then verify with `cmp -s AGENTS.md CLAUDE.md` and `git diff --check`.
