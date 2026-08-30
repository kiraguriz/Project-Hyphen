# Private Beta Recruiting And Feedback Plan

- **Tracker**: HYP-M4-012
- **Status**: local Stage A/Stage B packet ready; no recruitment, channel,
  invitation, artifact distribution, accepted participant, or runtime result
  exists yet.
- **Audience**: maintainers preparing a 3-5 person evidence beta before any
  20-50 person technical private beta.

Use this with the [3-5 person pilot plan](evidence-beta-pilot.md), its
[session record template](evidence-beta-session-record.md), the
[exit review template](evidence-beta-exit-review.md), the
[private beta release notes](private-beta-release-notes.md), and the GitHub
issue form at `.github/ISSUE_TEMPLATE/private_beta_bug.yml`.

This file is preparation only. It does not authorize external messages,
channel or account creation, signing, artifact distribution, or collection of
participant data.

## Required Sequence

1. **Stage A — evidence beta**: after explicit maintainer authorization, run
   the 3-5 person pilot in `evidence-beta-pilot.md` with exact checksums,
   synthetic data, consent, stop/recall controls, and one private session
   record per attempt. Select one final candidate artifact pair; only eligible
   people, coverage, and passes on that pair can justify `GO`, while every
   earlier-build attempt/failure remains in accounting.
2. **Exit review**: count every started session and every fail/blocked/stopped
   result, reconcile every issued attempt ID including preflight blocks and
   restarted runs, complete redaction review, resolve or accept risks, and
   record an explicit maintainer `GO`, `HOLD`, or `STOP`.
3. **Stage B — expanded private beta**: invite 20-50 technical testers only
   after a Stage A `GO`. Local tests, fewer than three completed people, or an
   incomplete Stage A result cannot authorize expansion.

Stage A evidence can inform later triage, but its small sample cannot establish
broad compatibility, a top-10 failure ranking, a 99% crash-free claim, RC
readiness, or release readiness.

## Stage B Recruiting Target (After Pilot GO)

After Stage A records `GO`, recruit 20-50 technical testers who can run approved
private artifacts, capture device/OS details, avoid sensitive data in reports,
and tolerate pre-alpha rough edges.

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

These targets apply only to Stage B. The numbers can overlap. A single tester
may cover multiple cohorts. The private source record must preserve the exact
device, OS, network, build, and scenario without unique identifiers; any
repository-facing summary must use only non-linkable aggregate coverage.

## Channel Setup Checklist

Before inviting anyone into either stage, a maintainer must:

1. Record the stage, explicit authorization, assigned coordinator, exact
   commit, artifact filenames, SHA-256 checksums, build labels, and recall path.
2. Choose the private feedback channel: private GitHub repository/issues,
   GitHub Discussions with limited access, Discord, Slack, or email alias.
3. Pick one public safety path for security reports: GitHub private
   vulnerability reporting, or the fallback described in `SECURITY.md`.
4. Create triage labels or queues for `pairing`, `notifications`, `transfer`,
   `diagnostics`, `compatibility`, `release`, `security-private`, and `blocked`.
5. Publish the private beta release notes, installation guide, synthetic test
   pack, consent/stop briefing, and current blockers to participants.
6. Pin the "do not include sensitive data" rule in the channel.
7. Assign a maintainer to review reports at least twice per week during the
   private beta window.
8. Keep participant identity, consent records, raw logs, screenshots, private
   issue links, and the candidate list outside this repository.
9. Keep participant-level pass/fail/blocked/stopped results in the private
   session records. Only after exit-review redaction and singling-out review
   may a coordinator propose non-linkable aggregate counts or coverage classes
   for repository docs. Never put a participant/session row, private evidence
   link, or linkable small-cohort device/OS/network/scenario combination in
   `docs/compatibility-matrix.md`.

## Invitation Copy (Use Only After Authorization)

For Stage A, say explicitly that this is a 3-5 person evidence pilot. For
Stage B, say that Stage A recorded `GO`; do not imply public availability or a
release-quality build.

```text
Project Hyphen is looking for a small private beta group of technical Android
and Mac users.

This is a local-first, pre-alpha Android companion layer for macOS. There is no
account, cloud relay, or telemetry by default. The current beta is for evidence:
pairing, notification mirroring, text/link transfer, file transfer, diagnostics,
and compatibility across devices and networks.

Please join only if you are comfortable running local builds, reporting exact
build checksums and device/OS/network classes, and keeping notification text,
file contents, URLs, IP addresses, tokens, private contacts, unique identifiers,
and other personal data out of reports. Participation is voluntary and can stop
at any time.

Start with the private beta release notes and installation guide. File issues
with the private beta bug template, and use the security reporting path for any
suspected exploit or privacy leak.
```

## Bug Report Requirements

Every beta bug report in the maintainer-controlled private intake should
include:

- Cohort stage: Stage A evidence beta or Stage B expanded private beta.
- Pseudonymous session ID, never a participant name, email, handle, or device
  identifier.
- Artifact set ID, Hyphen commit, artifact filename, and SHA-256 checksum.
- Platform: Android, macOS, or cross-device.
- Android device model, Android version, OEM skin, and install track where
  applicable.
- Mac model and macOS version where applicable.
- Network case: home Wi-Fi, mesh, hotspot, AP/client isolation, VPN, restricted
  LAN mode, or permission-denied mode.
- Exact `EB-*` scenario ID for Stage A (or a stable named Stage B scenario) and
  result: `pass`, `fail`, `blocked`, `stopped`, or `not-run`.
- Attempt disposition: `preflight-blocked`, `completed`, `stopped`, or
  `incomplete`; scenario results may also be `not-run`.
- Whether the session entered the started-session denominator, whether a stop
  condition ended it, and whether it met the HYP-M6-002 crash-or-hard-failure
  boundary. Multiple qualifying events still count once for the session.
- Whether any journey failed and whether all required evidence is complete;
  `unknown` or incomplete evidence blocks Stage A `GO`.
- Steps to reproduce, expected result, observed result, repeatability, and
  repeat count within the uninterrupted attempt.
- Redacted diagnostics export when available.
- Confirmation that the report contains no notification text, file contents,
  URLs, IP addresses, passwords, tokens, private contact details, or personal
  device identifiers.

Security reports must not use the normal issue template if details are
exploitable. Follow `SECURITY.md` instead.

## Triage Rules

- Treat reports as evidence, not broad platform claims.
- Preserve every started session and every fail/blocked/stopped result in the
  denominator; assign a new session ID to each restarted run and do not replace
  a failed record with a retry.
- Do not mark a matrix row `pass` unless that exact device/OS/network/scenario
  was observed.
- Convert repeated failures into tracker follow-up tasks or issue labels.
- Prefer fallback clarity over permission expansion. Do not add location, SMS,
  Call Log, Accessibility, cloud relay, NAT relay, or telemetry to make a beta
  issue easier.
- Keep raw logs local and redacted. Do not copy secrets or personal data into
  repository docs.

## Current Blocker

This repo now has a staged release-note draft, evidence-pilot plan, session
template, feedback plan, and bug-report template, but HYP-M4-012 is still
blocked because every accepted cohort action requires external maintainer
authorization and state:

- No maintainer has authorized Stage A or accepted its coordinator role.
- No maintainer-owned private feedback channel, candidate list, consent corpus,
  or checksum-bound artifact set is configured here.
- No 3-5 person pilot has accepted, run, or completed a session.
- No Stage A exit review or explicit Stage B `GO` exists.
- No 20-50 person Stage B cohort, beta feedback corpus, failure ranking, or
  crash-free denominator exists.
