# Open-Source Release Readiness Assessment

- **Date**: 2026-06-15
- **Scope**: Whole-repo evaluation of whether Project Hyphen can be released
  as a standard open-source tool.
- **Type**: Read-only audit. No source, docs, or tracker rows were changed by
  this assessment. Evidence is drawn from current files, the roadmap tracker,
  and live build/test runs on this machine.

## Verdict

**Not ready to release as a usable v1 open-source tool.** The repository is a
high-quality, well-documented **pre-alpha** (its own `README.md` says so). The
gap is not polish; it is two hard gates: (1) the formal license/legal files do
not exist yet, and (2) the core product features lack end-to-end real-device
verification. A source-only public exposure (pre-alpha) is reachable quickly;
a usable v1 release is not.

## Evidence: what is solid

| Dimension | Evidence |
|---|---|
| Governance docs | `README.md`, `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, and ADR-0001/0003/0004/0005/0006 all present. |
| Auditable protocol | `protocol/schema/*` + `protocol/test-vectors/*` + dependency-free validators. `./scripts/test-protocol.sh` green: envelope/capability fixtures, error registry (24 codes / 5 categories), SAS pairing vectors (5 cases + 1 tamper). |
| Buildable + tested | macOS `swift build` + `swift test` → **141 tests, 0 failures**. Android `./gradlew test` → **BUILD SUCCESSFUL**. ~38 Kotlin test files, ~20 Swift test files. |
| CI present | `.github/workflows/checks.yml` (least-privilege `contents: read`) plus a private-beta bug issue template. |
| Honest tracking | The tracker consistently separates automated invariants from live-device evidence and does not convert missing evidence into `[x]`. |

## Evidence: blockers

### P0-1 — No formal root license files (legal blocker)

- No root `LICENSE`, `NOTICE`, SPDX headers, or DCO/CLA exist
  (`test -f LICENSE` → absent).
- ADR-0005 *decides* the policy (app source MPL-2.0, protocol Apache-2.0, docs
  CC-BY-4.0) but explicitly defers landing the files.
- `README.md:51` and tracker row **HYP-M6-008 `[?]`** both state the formal
  license files / SPDX sweep / contribution terms must land before public
  release.
- Without a `LICENSE` file the repository is, by default, **not legally
  open source** — third parties cannot lawfully use or redistribute it.

### P0-2 — Core features lack real-device end-to-end verification

The product is a "persistent paired companion" for notifications and transfer,
but its core P0 feature paths are all `[?]` blocked in the tracker pending
real-device evidence:

- Notification mirror end-to-end: **HYP-M3-001 / 004 / 005 / 006** (Android
  listener → macOS show/update/remove, privacy filter, Mac-side dismiss).
- Bidirectional text/link: **HYP-M3-008 / 009**.
- Discovery / pairing on real hardware: **HYP-M1-004 (mDNS)**,
  **HYP-M1-006 (QR/manual fallback)**, **HYP-M1-007 (CDM association)**.
- No live evidence: compatibility matrix is still a template
  (**HYP-M4-005/006**), no 1 GB real-device transfer log (**HYP-M3-015 /
  HYP-M6-004**), no 20-cycle sleep/wake reconnect run (**HYP-M4-007 /
  HYP-M6-003**), no beta cohort (**HYP-M4-012**).

The implementation layer (streaming transfer, SHA-256, resume checkpoints,
reconnect state machine) is built and unit-tested, but passing unit/loopback
tests is not the same as a real paired-device run — the project's own
conventions make this distinction explicit.

### P0-3 — No release artifacts exist

- No git tags, no GitHub release (`git tag -l` empty).
- **HYP-M6-009** (release candidate) and **HYP-M6-010** (tag v1.0) are `[?]`
  blocked on the prerequisites above.
- macOS notarization (**HYP-M5-002**) and reproducible Android release build
  (**HYP-M5-004**) are `[?]`, gated on external signing/store accounts.

### Secondary — Decision gates not passed

Of six gates: **G-D** (notification 10-app matrix), **G-E** (distribution
feasibility), and **G-F** (v1 reliability, ≥99% crash-free) are `[ ]` not
passed; **G-A** (LAN survivability) and **G-C** (wake recovery) are `[~]`
conditional with named residuals. Only **G-B** (companion API viability) is
`[x]`.

## Tracker snapshot

| Milestone | Done | Blocked `[?]` | Remaining |
|---|---:|---:|---:|
| M0 Scope/Ops | 15 | 0 | 0 |
| M1 Platform PoCs | 12 | 3 | 0 |
| M2 Core Transport | 15 | 0 | 0 |
| M3 Feature MVP | 6 | 9 | 0 |
| M4 Beta Hardening | 4 | 8 | 0 |
| M5 Distribution | 7 | 3 | 0 |
| M6 Stabilization | 3 | 7 | 0 |

The blocked rows cluster exactly in the feature, beta, and release milestones —
the verification-heavy ones — which matches the verdict.

## Recommended path

Separate work that can be done locally now from work that needs external
resources.

1. **Unlocks "source-only open source" (doable now)**: land root `LICENSE`,
   `NOTICE`, SPDX headers, and a DCO; close HYP-M6-008. After this the repo
   can be made public as an auditable **pre-alpha** — but it must not be
   marketed as a usable v1 tool.
2. **Unlocks "usable tool" (needs a real device session)**: one two-person /
   two-device bring-up to exercise the M3 notification + text/link paths, fill
   the compatibility matrix, run the 1 GB transfer, and the 20-cycle sleep/wake
   test. This is the ~2h device checklist already scoped in `m1-findings.md`
   (HYP-M1-015).
3. **Unlocks "store distribution" (needs external accounts)**: Apple Developer
   notarization, Play / F-Droid channels.

## Bottom line

Releasing the source code (pre-alpha) is a small, well-defined step away —
mainly the license files. Releasing a *usable v1 tool* is blocked by real-device
verification and external signing/store gates that code changes alone cannot
satisfy.
