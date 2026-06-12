# HYP-M6-009 v1.0 Release Candidate Readiness Review

- **Date**: 2026-06-12
- **Tracker**: HYP-M6-009
- **Status**: blocked; do not create or publish v1.0 RC artifacts yet.

## Goal

HYP-M6-009 requires private publication of v1.0 release-candidate artifacts.
The RC depends on HYP-M6-001 through HYP-M6-008, so every stabilization gate
must either be complete or explicitly accepted as a release cut.

## Prerequisite Status

| Task | Status | RC impact |
|---|---|---|
| HYP-M6-001 top 10 beta failures | `[?]` | Blocked: no beta issue/failure corpus exists to rank or fix. |
| HYP-M6-002 crash-free beta sessions | `[?]` | Blocked: no beta-session denominator or manual/diagnostics corpus exists. |
| HYP-M6-003 wake/network reconnect | `[?]` | Blocked: final human-run sleep/wake or network-transition log is missing. |
| HYP-M6-004 1GB resume behavior | `[?]` | Blocked: three Android/macOS/network combo runs are missing. |
| HYP-M6-005 notification duplicate prevention | `[x]` | Ready: automated Android/macOS duplicate-prevention tests and log exist. |
| HYP-M6-006 protocol v0 docs | `[x]` | Ready: protocol docs frozen for current pre-alpha behavior. |
| HYP-M6-007 threat-model review | `[x]` | Ready for local repo state; release no-go conditions remain visible. |
| HYP-M6-008 dependency/license audit | `[?]` | Audit done, but public-release license files/map/notice policy are incomplete. |

## Release Artifact Gates

The broader release surface is also not RC-ready:

| Gate | Current state |
|---|---|
| macOS signing/notarization | Blocked by missing Developer ID identity and notary credentials. |
| Android release signing | Blocked by missing release/upload keystore. |
| Play/F-Droid channels | Play review/channel access unavailable; F-Droid metadata disabled draft only. |
| Private beta cohort | Recruiting plan and template exist, but no accepted 20-50 tester cohort exists. |
| Checksums | Local dry-run paths can generate checksums, but no final RC artifact set exists. |
| Root license files | Not present; ADR-0005 records decisions only. |

## No-Go Decision

Do not create a v1.0 RC tag, GitHub release, private artifact bundle, or
release announcement from the current repo state.

The project may continue to create local dry-run packaging artifacts for
verification, but those artifacts must stay labeled as non-RC and non-public.

## Required Evidence To Unblock

Before HYP-M6-009 can move to done:

1. Resolve or explicitly cut HYP-M6-001, HYP-M6-002, HYP-M6-003, HYP-M6-004,
   and HYP-M6-008.
2. Run `./scripts/check.sh` on the intended RC commit.
3. Produce final macOS and Android RC artifacts from signed/notarized or
   approved release-signing paths, or record an explicit GitHub-only cut
   decision before artifact publication.
4. Generate and verify SHA-256 checksums after final packaging.
5. Review release notes against the current known-issues and unsupported-case
   lists.
6. Confirm no secrets, personal data, notification contents, file contents,
   URLs, or private logs are present in artifacts, fixtures, diagnostics, or
   release notes.

## Current Decision

HYP-M6-009 is blocked by unresolved reliability, beta-evidence, release-signing,
and license-readiness gates. No RC artifacts were created.
