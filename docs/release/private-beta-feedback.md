# Private Beta Recruiting And Feedback Plan

- **Tracker**: HYP-M4-012
- **Status**: local recruiting/feedback packet ready; actual recruitment is
  blocked until a maintainer-owned channel and invited tester list exist.
- **Audience**: maintainers preparing a 20-50 person technical private beta.

Use this with [private beta release notes](private-beta-release-notes.md) and
the GitHub issue form at `.github/ISSUE_TEMPLATE/private_beta_bug.yml`.

## Recruiting Target

Recruit 20-50 technical testers who can run local builds, capture device/OS
details, avoid sensitive data in reports, and tolerate pre-alpha rough edges.

Recommended coverage:

| Cohort | Minimum target | Why |
|---|---:|---|
| Pixel / AOSP Android 14-17 preview | 5 | Baseline Android behavior and restricted-LAN path. |
| Samsung One UI | 4 | OEM background and notification behavior. |
| Xiaomi / HyperOS | 3 | Aggressive background restriction coverage. |
| OnePlus/Oppo | 3 | ColorOS/OxygenOS battery/network behavior. |
| Apple silicon Macs on current stable macOS | 5 | Primary macOS target. |
| Intel Mac or older supported macOS | 2 | Best-effort compatibility boundary. |
| Mesh, hotspot, AP/client isolation, or VPN networks | 5 | Discovery/fallback evidence. |

The numbers can overlap. A single tester may cover multiple cohorts, but each
matrix result must record the exact device, OS, network, build, and scenario.

## Channel Setup Checklist

Before inviting testers, a maintainer should:

1. Choose the private feedback channel: private GitHub repository/issues,
   GitHub Discussions with limited access, Discord, Slack, or email alias.
2. Pick one public safety path for security reports: GitHub private
   vulnerability reporting, or the fallback described in `SECURITY.md`.
3. Create triage labels or queues for `pairing`, `notifications`, `transfer`,
   `diagnostics`, `compatibility`, `release`, `security-private`, and `blocked`.
4. Publish the private beta release notes and installation guide to testers.
5. Pin the "do not include sensitive data" rule in the channel.
6. Assign a maintainer to review reports at least twice per week during the
   private beta window.
7. Record observed pass/fail/blocked results in `docs/compatibility-matrix.md`
   or linked redacted local notes.

## Invitation Copy

```text
Project Hyphen is looking for a small private beta group of technical Android
and Mac users.

This is a local-first, pre-alpha Android companion layer for macOS. There is no
account, cloud relay, or telemetry by default. The current beta is for evidence:
pairing, notification mirroring, text/link transfer, file transfer, diagnostics,
and compatibility across devices and networks.

Please join only if you are comfortable running local builds, reporting exact
device/OS/network details, and keeping notification text, file contents, URLs,
IP addresses, tokens, private contacts, and other personal data out of reports.

Start with the private beta release notes and installation guide. File issues
with the private beta bug template, and use the security reporting path for any
suspected exploit or privacy leak.
```

## Bug Report Requirements

Every beta bug report should include:

- Hyphen commit or package checksum.
- Platform: Android, macOS, or cross-device.
- Android device model, Android version, OEM skin, and install track where
  applicable.
- Mac model and macOS version where applicable.
- Network case: home Wi-Fi, mesh, hotspot, AP/client isolation, VPN, restricted
  LAN mode, or permission-denied mode.
- Scenario and result: `pass`, `fail`, `blocked`, or `not-run`.
- Steps to reproduce, expected result, observed result, and repeatability.
- Redacted diagnostics export when available.
- Confirmation that the report contains no notification text, file contents,
  URLs, IP addresses, passwords, tokens, private contact details, or personal
  device identifiers.

Security reports must not use the normal issue template if details are
exploitable. Follow `SECURITY.md` instead.

## Triage Rules

- Treat reports as evidence, not broad platform claims.
- Do not mark a matrix row `pass` unless that exact device/OS/network/scenario
  was observed.
- Convert repeated failures into tracker follow-up tasks or issue labels.
- Prefer fallback clarity over permission expansion. Do not add location, SMS,
  Call Log, Accessibility, cloud relay, NAT relay, or telemetry to make a beta
  issue easier.
- Keep raw logs local and redacted. Do not copy secrets or personal data into
  repository docs.

## Current Blocker

This repo now has a release-note draft, feedback plan, and bug-report template,
but HYP-M4-012 is still blocked because recruitment itself requires external
state:

- A maintainer-owned private feedback channel is not configured here.
- No invited tester list exists in the repo.
- No 20-50 person beta cohort has accepted the invitation.
- No beta feedback corpus exists yet for M6 issue review or crash-free metrics.
