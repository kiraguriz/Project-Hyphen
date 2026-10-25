# Project Hyphen instructions

Goal: build a local-first Android companion for the macOS menu-bar app while preserving the frozen scope, LAN protocol, security model, and auditable milestone evidence. Root `AGENTS.md` and `CLAUDE.md` are mirrors.

## Sources of truth

- `docs/project_hyphen_roadmap_tracker_v0_3.md`: task state, blockers, evidence, and progress.
- `docs/adr/`: accepted architecture, scope, security, dependency, and licensing decisions.
- `docs/protocol/`, `protocol/schema/`, and vectors: wire compatibility.
- `docs/project_hyphen_plan_v0_3_en.md` and `_zh.md`: design intent, not current implementation status.

Current code and runnable checks beat stale prose. Update only the source that owns a changed fact.

## Workflow and completion

- For autonomous roadmap work, take the next unchecked unblocked P0, then P1, unless the user names a target.
- Update tracker/ADR/protocol only when owned behavior or evidence changes. Commit or push only when explicitly requested.
- Tracker evidence distinguishes automated checks, device/runtime proof, and release blockers.

## Verification

- Repo: `./scripts/check.sh`; protocol: `./scripts/test-protocol.sh`.
- Android: `cd apps/android && ./gradlew test assembleDebug`.
- macOS: `cd apps/macos && swift build && swift test`.
- Packaging/release: use the scoped dry-run under `packaging/`.

Instruction-only edits need mirror/path/diff checks; run platform gates for affected behavior. Report unavailable device/signing checks as evidence gaps.

## Frozen boundaries

- ADR-0001 owns scope. v1 has no cloud accounts/relay, public NAT relay, external backend dependency, default telemetry, or default crash upload.
- Do not add SMS/Call Log, Accessibility, private APIs, root, background clipboard, screen mirroring, or remote control without an accepted scope decision.
- Protocol/TLS changes update the owning ADR, docs, schema/vectors, and both platform checks together.
- External GPL/AGPL projects are behavior references only. New dependencies require explicit approval and license review.
- Formal license/NOTICE/SPDX/contribution files change only for named license or release work.
- Never read, print, or commit secrets, signing material, personal data, private logs, `.env*`, generated runtime artifacts, dependencies, or caches. Use `.claudeignore` as the exclusion source.
