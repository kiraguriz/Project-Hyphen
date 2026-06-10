# ADR-0001: v1 Product Scope Freeze

- **Status**: Accepted
- **Date**: 2026-06-10
- **Source**: `docs/project_hyphen_plan_v0_3_en.md` §1 (Final Decision), §5 (v1 Scope and Non-goals), §15 (Gates and Cut Rules)
- **Tracker**: HYP-M0-001

## Context

Project Hyphen is an open-source, local-first, auditable Android companion layer for macOS. Plan v0.3 concluded **Conditional Go**: the moat is persistent paired cross-device continuity, not file transfer. Quick Share ↔ AirDrop interoperability, LocalSend, and NearDrop already cover one-off transfer workflows, so v1 value must come from pairing, notification continuity, recovery, and auditability.

Without a frozen scope, implementation drifts toward UI breadth and feature-by-feature competition with transfer tools, while the actual P0 risks (Android 16/17 local-network permissions, CompanionDeviceManager API evolution, foreground-service policy, macOS Local Network Privacy, sleep/wake recovery) go unburned. This ADR freezes what v1 is and is not, and defines the cut rules that apply when a decision gate fails.

## Decision

### v1 must-have

- macOS menu-bar app.
- Android companion app.
- QR pairing plus SAS confirmation.
- Self-signed TLS / mTLS with certificate or public-key fingerprint pinning.
- Bonjour/mDNS discovery as a best-effort accelerator only.
- QR / manual IP / remembered-endpoint fallback.
- Android notification mirror / update / remove / dismiss.
- Quick Reply **beta** only where the originating notification exposes `RemoteInput` and has been tested.
- Bidirectional text/link sending.
- Bidirectional file transfer with resume, integrity checks, progress, and cancellation.
- Reconnect across Mac sleep/wake, network transitions, Android foreground/background, and battery restrictions.
- Local logs, redacted diagnostics export, and no telemetry by default.
- GitHub release; F-Droid preparation; Google Play policy-minimized track evaluation.
- macOS notarized build preparation.

### Explicit v1 non-goals

- Cloud accounts, cloud relay, or public-Internet NAT relay.
- SMS / Call Log permissions.
- Default SMS/Phone handler.
- Phone-call audio bridging.
- Screen mirroring or remote control.
- Automatic background clipboard listening.
- Full folder sync.
- AirDrop/AWDL reimplementation.
- Private APIs, root, jailbreak, or Accessibility hacks.
- Copying KDE Connect GPL code into an MPL/Apache codebase (clean-room only).

### Cut rules

Applied at the decision gates defined in the roadmap tracker (§2) and plan (§15):

| Gate | If it fails |
|---|---|
| Gate A — LAN/discovery survivability | Stop chasing invisible discovery; make QR/manual the primary pairing UX. mDNS stays best-effort. |
| Gate B — Notification thesis | v1 ships mirror + dismiss only; Quick Reply moves to v1.1. |
| Gate C — Distribution feasibility | Ship GitHub first; defer Google Play without blocking open beta. |
| Gate D — Reliability | Cut P1/P2 feature breadth; protect pairing, notifications, and transfer stability. |
| Google Play resists richer features | Do not stall the project; release the open-source track and the Play track separately. They may expose different feature sets while remaining protocol-compatible. |

### Scope-change rule

Any addition to v1 must-have, or removal of a non-goal, requires a new ADR that explicitly supersedes this one. Tasks not traceable to a roadmap row (`docs/project_hyphen_roadmap_tracker_v0_3.md`) are out of scope.

## Consequences

**Positive**

- Engineering effort goes to platform-risk burn-down (M1) before UI polish, matching the 30/60/90-day plan.
- Play-policy risk is contained: no SMS/Call Log, no Accessibility, no background clipboard means no high-risk permission review in v1.
- Privacy posture (no account, no cloud, no default telemetry) is a frozen guarantee that docs and marketing copy cannot overstate.

**Negative / accepted risk**

- v1 will compare unfavorably on feature count against KDE Connect and AirDroid; this is accepted positioning.
- Quick Reply may slip to v1.1 entirely if Gate B fails; mirror/dismiss is the protected core.
- Users wanting SMS continuity or remote control are explicitly not served by v1 (kept in the P2/P3 research backlog).
