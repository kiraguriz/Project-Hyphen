# Project Hyphen Agent Instructions

`AGENTS.md` and `CLAUDE.md` are mirrors. Keep them byte-identical unless an explained tool-specific exception is required.

Project Hyphen is a local-first Android companion layer for macOS: a menu-bar Mac app and an Android app paired over LAN TLS. Milestone progress, current readiness, and implementation snapshots do not belong in this file.

## Sources of Truth

- Tracker: `docs/project_hyphen_roadmap_tracker_v0_3.md` for task state, blockers, verification notes, and progress.
- ADRs: `docs/adr/` for accepted architecture, product scope, security, dependency, and licensing decisions.
- Protocol: `docs/protocol/`, `protocol/schema/`, and test vectors for wire behavior and compatibility.
- Plan: `docs/project_hyphen_plan_v0_3_en.md` and `_zh.md` for design intent; edit them only for a documented factual correction.

Current code and runnable checks beat stale documentation. Flag mismatches and update the source that owns the changed fact.

## Project Workflow

- Confirm `pwd`, the git root, and `git status --short --branch`; preserve unrelated dirty or staged work.
- For roadmap work, take the next unchecked, unblocked P0 row and then P1 unless the user names another target.
- Update the tracker, ADRs, or protocol only when behavior, evidence, or an owned source fact changes.
- Commit or push only when the user explicitly asks.

## Verification

- Whole repo: `./scripts/check.sh`
- Protocol: `./scripts/test-protocol.sh`
- Android: `cd apps/android && ./gradlew test assembleDebug`
- macOS: `cd apps/macos && swift build && swift test`
- Packaging or release: use the local dry-run scripts under `packaging/`.

If checks require unavailable devices, credentials, signing accounts, store access, OS images, or unsafe host actions, report the exact blocker. Keep local checks, device/runtime proof, and release readiness as separate evidence layers.

## Frozen Safety Boundaries

- ADR-0001 owns product scope. v1 has no cloud accounts, relay, public-Internet NAT relay, external backend dependency, default telemetry, or default crash upload.
- Do not add SMS/Call Log permissions, Accessibility services, private APIs, root requirements, background clipboard listening, screen mirroring, or remote control without an accepted scope decision.
- Protocol or TLS compatibility changes must update the owning ADR, protocol documents, schema or test vectors, and relevant checks together.
- Do not copy GPL/AGPL code; external projects are behavior references only. New dependencies require explicit approval and license-review notes.
- Change formal root license, NOTICE, SPDX, or contribution-policy files only for license or release-readiness work.
- Do not commit secrets, signing keys, tokens, credentials, personal data, private logs, `.env*`, or generated runtime artifacts. Do not perform destructive cleanup or rewrite history without an explicit request.

## Context Hygiene

Use `.claudeignore` as Claude's exclusion list and as exploration guidance for Codex. Do not read or print private runtime data, local signing material, credentials, keys, tokens, or secret files.
