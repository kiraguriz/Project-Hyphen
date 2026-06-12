# Project Hyphen

**Paired Android continuity for macOS.** An open-source, local-first, auditable Android companion layer that keeps a trusted Mac and Android phone continuously connected for notifications, actions, text, links, files, state, and recovery.

> **Status: pre-alpha.** This repository now contains runnable Android/macOS skeletons and local packaging dry runs, but there is no signed, notarized, or store-ready public release yet. Use the install docs only for local maintainer/tester builds.

## What Hyphen is

- A **persistent paired companion**, not a one-off file-transfer utility.
- **Local-first**: no account, no cloud relay, no telemetry by default.
- **Auditable**: protocol documents, permission rationale, threat model, and diagnostics format are public in this repository.

## What v1 will and will not do

The v1 scope is frozen in [ADR-0001](docs/adr/0001-product-scope.md). Highlights:

| v1 does | v1 does not |
|---|---|
| QR pairing + SAS confirmation, pinned TLS | Cloud accounts, cloud relay, NAT relay |
| Notification mirror / update / remove / dismiss | SMS / Call Log permissions |
| Bidirectional text/link and file transfer with resume | Screen mirroring or remote control |
| Reconnect across Mac sleep/wake and network changes | Background clipboard listening, folder sync |
| Redacted local diagnostics export, opt-in only sharing | Private APIs, root, Accessibility hacks |

## Repository layout

```text
apps/android/    Android companion app (pre-alpha)
apps/macos/      macOS menu-bar app (pre-alpha)
protocol/        Protocol schemas, test vectors, conformance
docs/            Plan, roadmap tracker, ADRs, protocol docs
scripts/         Check/format/test scripts
ci/              CI configuration
packaging/       macOS / Play / F-Droid packaging notes and dry runs
```

## Key documents

- [Plan v0.3 (EN)](docs/project_hyphen_plan_v0_3_en.md) · [计划 v0.3 (中文)](docs/project_hyphen_plan_v0_3_zh.md)
- [Roadmap tracker](docs/project_hyphen_roadmap_tracker_v0_3.md) — source of truth for implementation status
- [ADR-0001 — v1 product scope freeze](docs/adr/0001-product-scope.md)
- Installation: [English](docs/install/installation_en.md) · [中文](docs/install/installation_zh.md)
- Troubleshooting: [English](docs/troubleshooting_en.md) · [中文](docs/troubleshooting_zh.md)

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md). All participation is covered by the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

The license decision is recorded in [ADR-0005](docs/adr/0005-license-and-clean-room-policy.md): app source code uses MPL-2.0, protocol specs/schemas/test vectors use Apache-2.0, and documentation uses CC-BY-4.0 unless a file says otherwise. Formal root license files, SPDX sweeps, and contribution terms still need to land before public release packaging or external code contributions.
