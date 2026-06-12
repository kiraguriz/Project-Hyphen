# Contributing to Project Hyphen

Thanks for your interest. Hyphen is in pre-alpha; the most useful contributions right now are review of the plan, ADRs, protocol drafts, and platform-risk findings (Android 16/17 local network, CompanionDeviceManager, macOS Local Network Privacy, sleep/wake recovery).

## Ground rules

1. **Scope discipline.** Every change must trace to a row in [the roadmap tracker](docs/project_hyphen_roadmap_tracker_v0_3.md) or to an accepted ADR. Additions to v1 scope require a new ADR superseding [ADR-0001](docs/adr/0001-product-scope.md).
2. **Clean-room only.** Do not copy code from GPL projects (including KDE Connect) into this codebase. Learning from publicly documented behavior and reimplementing independently is fine; copying or close paraphrasing of GPL source is not. State your sources in the PR description when reimplementing observed behavior.
3. **No high-risk surface.** PRs introducing cloud relay, default telemetry, SMS/Call Log permissions, Accessibility-service hacks, private APIs, or background clipboard listening will be closed; these are frozen v1 non-goals.
4. **No secrets.** Never commit credentials, signing keys, API tokens, or personal data — including in test fixtures, logs, and diagnostics samples.
5. **Privacy claims must match code.** Documentation may not promise more than the implementation does.

## Workflow

- Pick an unchecked task from the tracker (P0 first), or open an issue describing the problem.
- Keep changes small and verifiable; one tracker task per PR is ideal.
- Reference the task ID (e.g. `HYP-M1-004`) in the commit message and PR title.
- Run `./scripts/check.sh` before submitting (placeholder until CI lands; run the narrowest relevant platform checks too).
- Update the tracker row, and add or update ADRs/protocol docs when behavior changes.

## Commit style

- Subject: `HYP-Mx-NNN: imperative summary` for tracker tasks, or `docs:`/`chore:`/`fix:` prefixes otherwise.
- Explain *why* in the body when the change is not obvious.

## Licensing of contributions

The project license decision is recorded in [ADR-0005](docs/adr/0005-license-and-clean-room-policy.md): app source code uses MPL-2.0, protocol specs/schemas/test vectors use Apache-2.0, and documentation uses CC-BY-4.0 unless a file says otherwise. Formal root license files, SPDX sweeps, and a DCO or CLA decision must land before external code contributions are merged.
