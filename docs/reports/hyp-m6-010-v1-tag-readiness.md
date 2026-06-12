# HYP-M6-010 v1.0 Tag Readiness Review

- **Date**: 2026-06-12
- **Tracker**: HYP-M6-010
- **Status**: blocked; no `v1.0` tag was created.

## Goal

HYP-M6-010 requires a v1.0 git tag, release notes, checksums, and docs. It
depends on HYP-M6-009, the v1.0 release candidate task.

## Current State

HYP-M6-009 is blocked. There is no valid v1.0 RC artifact set to tag:

- No RC artifacts were created or privately published.
- No final RC checksums exist.
- Reliability gates remain blocked: beta failure categories, crash-free beta
  sessions, wake/network reconnect, and 1GB resume evidence.
- Release gates remain blocked: macOS Developer ID/notary credentials, Android
  release/upload keystore, Play/F-Droid channel access, and private beta cohort
  evidence.
- License release readiness remains blocked by missing root license files,
  top-level license map, NOTICE/SPDX/header policy, and DCO/CLA decision.

## No-Go Decision

Do not create `v1.0`, a GitHub release, or release checksums from this commit.
Doing so would turn a blocker ledger into a misleading release marker.

No git tag was created during this review.

## Required Evidence To Unblock

Before tagging v1.0:

1. Complete or explicitly cut HYP-M6-009 with a reviewed RC artifact set.
2. Run `./scripts/check.sh` on the exact commit to be tagged.
3. Verify final macOS and Android artifact checksums after packaging.
4. Confirm release notes include current known issues and unsupported cases.
5. Confirm security/privacy no-go checks from the threat-model review.
6. Confirm license readiness has formal root files, map, notice/header policy,
   and contribution terms.
7. Create the tag only after the above evidence is stored in the repo or linked
   from a maintainer-controlled release record.

## Future Tag Command

When the blockers are resolved, use a signed or annotated tag according to the
project's release policy:

```bash
git tag -a v1.0 -m "Project Hyphen v1.0"
```

Do not run that command until the RC evidence is complete.
