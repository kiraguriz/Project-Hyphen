# Security Policy

Project Hyphen is a local-first device-pairing product; pairing, trust-store, and transport security are core to its value. We take reports in these areas especially seriously.

## Supported versions

Pre-alpha: no releases exist yet. Security review of the protocol drafts, pairing design, and threat model (`docs/protocol/`) is welcome at any time.

## Reporting a vulnerability

- **Preferred**: GitHub **private vulnerability reporting** on this repository (Security tab → "Report a vulnerability"), once the repository is public on GitHub.
- **Do not** open a public issue for anything you believe is exploitable — including LAN spoofing, pairing MITM, trust-store bypass, notification-content leakage, or diagnostics-redaction failures.
- If private reporting is unavailable, open a minimal public issue saying only "security report — request private contact" with no technical details, and a maintainer will arrange a private channel.

## What to include

- Affected component (pairing, transport, trust store, notifications, transfer, diagnostics) and platform (Android / macOS).
- Reproduction steps or a proof-of-concept transcript.
- Impact assessment: what an attacker on the same LAN (or with a paired device) gains.

## Response expectations

This is a volunteer pre-1.0 project: acknowledgement within 7 days, best effort. Confirmed protocol-level issues will produce an ADR and, where applicable, new test vectors so the class of bug stays fixed.

## Scope notes

- Attacks requiring a malicious *already-paired and user-confirmed* peer are still in scope (peer revocation must work), but attacks requiring root/jailbreak on the user's own device are generally out of scope.
- The threat model lives at `docs/protocol/threat-model.md` (HYP-M0-008) and is the reference for what Hyphen defends against.
