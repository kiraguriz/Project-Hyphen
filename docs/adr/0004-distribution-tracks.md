# ADR-0004: Distribution Tracks

- **Status**: Accepted
- **Date**: 2026-06-10
- **Source**: plan v0.3 §0.3, §10.2, §10.3, §12; ADR-0001; ADR-0003
- **Tracker**: HYP-M0-010

## Context

One binary pipeline cannot serve both of Hyphen's audiences. Open-source/privacy users want transparency, completeness, and auditability (GitHub, F-Droid). Mainstream users arrive through Google Play, which optimizes for policy minimization, permission rationale, foreground-service declarations, and accurate Data safety disclosure. On macOS, anything public must be Developer ID signed and notarized or Gatekeeper friction kills adoption. v0.3 also moved release engineering to Phase 0: signing, notarization, store declarations, and metadata are engineering workstreams, not final-week packaging.

## Decision

### 1. Four separated tracks

| Track | Goal | Characteristics | First release target |
|---|---|---|---|
| GitHub Releases (Android APK + macOS DMG/ZIP) | Early community, transparency, technical users | Fastest path; full open-source feature set; checksums + release notes; still **no SMS/Call Log** (ADR-0001) | Private Beta (M4) |
| F-Droid | Open-source users, reproducible builds | Metadata, dependency review, reproducibility preparation (`packaging/android-fdroid/`); inclusion timeline not under our control | Public Beta (M5) window |
| Google Play | Discoverability, mainstream users | Policy-minimized build; strong permission rationale; FGS declarations; accurate Data safety; closed testing first | Evaluated at Gate E; may slip past v1.0 |
| macOS notarized GitHub build | Direct Mac downloads | Developer ID Application cert + notarization required for anything public; Homebrew Cask and Sparkle only after notarization matures (P2) | Public Beta (M5) |

### 2. Track invariants

1. **Protocol compatibility**: every track speaks the same `hyphen/0.x` protocol; a Play-build phone pairs with any Mac build.
2. **Permission ceiling**: ADR-0003's manifest table is the ceiling for all tracks. The Play build may *reduce* features/permissions under review pressure, never extend beyond GitHub.
3. **No dark divergence**: any feature difference between tracks is listed in the release notes and in a `docs/track-differences.md` once the first divergence exists.
4. **Same version, same code**: one version number across tracks per release; track differences are build-time flags in one repo, not forks.
5. **No telemetry on any track** by default (ADR-0001); the opt-in beta diagnostics toggle ships identically everywhere it ships at all.

### 3. Sequencing and cut rule

GitHub ships first. F-Droid preparation runs in parallel (metadata + reproducibility notes) but its inclusion queue is external. Play is evaluated last under Gate E: **if Play resists or stalls, ship GitHub/F-Droid and defer Play without blocking public beta** (plan §15 Gate C cut rule). The project never stalls on a store decision.

### 4. Signing and integrity

- **Android**: one release signing key for GitHub/F-Droid APKs; storage and rotation plan documented in `packaging/android-play/` and `packaging/android-fdroid/` notes (HYP-M0-012/013) before any release artifact exists. Play App Signing (upload key model) is acceptable for the Play track; its tension with reproducible builds is an F-Droid-notes topic, not a blocker.
- **macOS**: Developer ID Application certificate; notarization dry run is HYP-M5-002. Unsigned builds are for local development only and are never attached to releases.
- All public artifacts ship with SHA-256 checksums; releases include an SBOM and pass the dependency/license audit (HYP-M6-008) from Public Beta onward.

### 5. External gates (not local work — tracked separately)

| Gate | Needs | Roadmap |
|---|---|---|
| Apple Developer Program membership + Developer ID cert | Paid account, identity verification | blocks HYP-M5-001/002 |
| Google Play developer account (type decision: individual vs org) | Paid account, identity verification, closed-testing testers | blocks HYP-M5-005/006 |
| F-Droid inclusion | Upstream RFP/merge process, their build servers | blocks final F-Droid publish, not metadata prep |

These are credential/account blockers by nature; roadmap tasks that hit them get `[?]` with the exact missing account, while local preparation (scripts, metadata, declarations drafts) proceeds.

## Consequences

- **Positive**: store policy pressure cannot silently reshape the open-source product; Play review risk is firewalled behind Gate E; release engineering starts now, so signing/notarization surprises surface in M5 dry runs instead of launch week.
- **Negative / accepted**: maintaining build flags for track differences adds CI complexity; F-Droid reproducibility work is real effort with an external clock; mainstream discoverability may lag the open-source release by months if Play defers — accepted by ADR-0001 positioning.
- **Follow-ups**: HYP-M0-011 (notarization notes), HYP-M0-012 (Play policy notes), HYP-M0-013 (F-Droid metadata notes) detail each track's checklist.
