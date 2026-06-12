# ADR-0005: License and Clean-Room Policy

- **Status**: Accepted
- **Date**: 2026-06-12
- **Source**: plan v0.3 §12.2; `CONTRIBUTING.md`; ADR-0001; HYP-M0-015
- **Tracker**: HYP-M0-015

## Context

Hyphen needs a license policy before public release work, F-Droid metadata,
dependency audits, and outside contributions can be treated as real gates.
The plan recommends an MPL/Apache route with CC-BY documentation, and the
project already forbids copying KDE Connect or other GPL code into the repo.

This ADR records the project license decision without adding root license
files yet. Formal `LICENSE`, `NOTICE`, SPDX header sweeps, and the DCO/CLA
mechanism remain release-readiness work before accepting external code
contributions.

## Decision

### 1. License families by artifact type

| Artifact | License decision | Notes |
|---|---|---|
| Android and macOS app source code | MPL-2.0 | File-level copyleft keeps app modifications auditable while allowing normal platform distribution. |
| Protocol specifications, schemas, test vectors, and conformance fixtures | Apache-2.0 | Maximizes interoperability and reimplementation freedom for the wire protocol. |
| Project documentation | CC-BY-4.0 | Applies to docs and user-facing explanatory material unless a file says otherwise. |
| Third-party files | Upstream license | Keep existing notices, for example Gradle wrapper Apache-2.0 headers. |
| Generated build outputs and release artifacts | Not source license grants | Release artifacts need their own checksums, notices, and signing/reproducibility process. |

### 2. Formalization steps before public release

- Add root license files for MPL-2.0, Apache-2.0, and CC-BY-4.0.
- Add a short top-level license map explaining which tree uses which license.
- Add SPDX identifiers to new source and documentation files where practical.
- Add `NOTICE` material if later Apache-2.0 dependencies or generated notices
  require it.
- Decide DCO vs CLA before merging external code contributions.

Until those steps land, maintainers should not present the repository as a
release-packaged licensed distribution.

### 3. Clean-room rules

- Do not copy code from GPL, AGPL, proprietary, or unknown-license projects
  into this repository.
- Do not close-paraphrase implementation structure from KDE Connect or similar
  GPL projects.
- Learning from public behavior, published documentation, bug reports, UI
  observation, protocol traces that the project is legally allowed to inspect,
  and independent manual testing is allowed.
- When implementing behavior observed elsewhere, cite the behavioral source in
  the PR or progress note and write an independent implementation.
- New runtime dependencies must be reviewed before adoption. GPL/AGPL
  dependencies, proprietary SDKs, and ambiguous-license artifacts require an
  explicit ADR and are presumed incompatible with the v1 direction unless the
  review proves otherwise.
- Dev-only tools may use broader licenses if they are not copied into the repo,
  not linked into release artifacts, and not required for end-user builds.

## Consequences

- **Positive**: protocol reuse remains permissive; app code keeps a stronger
  source-available modification boundary; documentation has a clear reuse path;
  F-Droid and dependency-audit work can target concrete SPDX identifiers.
- **Negative / accepted**: multiple license families require explicit file
  mapping and SPDX hygiene. Public release packaging cannot be considered done
  until the formal license files and contribution terms land.
- **Follow-ups**: HYP-M6-008 audits dependencies and license compatibility
  before Public Beta; release tasks must add checksums, notices, and any
  artifact-specific license disclosures.
