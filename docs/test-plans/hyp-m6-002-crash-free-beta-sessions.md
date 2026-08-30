# HYP-M6-002 Crash-Free Beta Sessions Metrics Review

Status: blocked / not measurable yet

## Goal

HYP-M6-002 asks Hyphen to achieve at least 99% crash-free beta sessions where
measurable. Because Hyphen has no telemetry by default, this metric requires a
complete consented private session ledger, corroborated where available by
explicit opt-in diagnostics or manually submitted beta logs.

## Metric Definition

Draft definition for the eventual beta review:

- A beta session enters the denominator once its preflight passes and its first
  required journey begins. It remains counted if it later fails, stops, or is
  retried. Every attempted run receives a unique session ID before preflight;
  a preflight-blocked run is counted separately and cannot be overwritten by a
  restarted attempt.
- A session is crash-free when neither side reports an app/process crash,
  failure-driven forced termination, unrecoverable protocol failure, or
  user-visible hard failure before the user disconnects or the session closes normally. The
  union is deduplicated by session: several qualifying events still count as
  one crash-or-hard-failure session.
- A user-visible hard failure means the started session or a required journey
  cannot safely continue or reach intended closeout without a process restart
  or force quit, reinstall, re-pair or trust reset, manual data
  repair/restoration, or operator abort for an unresolved hang/loop. A
  recoverable visible error or explicit degraded state that permits safe retry
  and closeout is a failed/blocked journey, not automatically a hard failure.
  Any qualifying condition sets the flag regardless of session disposition.
- The denominator is every started session in the review window, reconciled
  from the consented private session ledger. Diagnostics and issue reports are
  evidence sources, not eligibility filters; a session without an export still
  counts.
- The numerator is `started sessions - unique crash-or-hard-failure sessions`.
  It cannot be calculated while any attempt is unreconciled or required outcome
  evidence is missing.
- The claim must include the source window, exact build/checksum set, started,
  completed, failed, stopped, and missing-evidence counts, deduplication rules,
  sample size, and known coverage gaps.

## Evidence Available Today

No crash-free beta-session claim is currently supportable:

- HYP-M4-011/HYP-M4-012 now have a staged 3-5 person evidence-beta plan,
  session record, release notes, recruiting plan, and bug template, but no real
  authorized pilot, recruited cohort, feedback channel, or session exists.
- HYP-M4-004 implemented the default-off beta diagnostics toggle, but Android manual preview/export/disable acceptance is still blocked by lack of an attached device/emulator.
- The Android app module currently has no crash-reporting SDK, analytics SDK, telemetry endpoint, account system, or cloud service.
- Repository search found no existing beta session logs, crash ledger, issue export, or user-submitted diagnostics corpus.

`adb devices -l` currently lists no Android targets, so a new paired-device beta session cannot be generated in this environment.

## Required Evidence To Unblock

Before marking HYP-M6-002 complete, collect one of:

- A complete consented private session ledger from a real beta cohort, with
  session counts and explicit crash/hard-failure labels, supported where
  available by opt-in diagnostics exports.
- A reconciled manual beta issue/log ledger that records every started session,
  start/end, platform versions, crash/hard-failure outcome, and whether
  diagnostics were attached.

Minimum review packet:

- Beta commit, artifact filenames, build labels, and SHA-256 checksums.
- Collection window.
- Number of distinct beta participants and device/network combinations without
  participant or unique-device identifiers.
- Number of issued attempt IDs, preflight-blocked attempts, started, completed,
  failed, stopped, incomplete, unreconciled, and missing-evidence sessions.
- Reconciliation showing `attempted = preflight-blocked + started +
  unreconciled` and `started = completed + stopped + incomplete`; failed and
  missing-evidence are cross-cutting flags.
- Number of crash/hard-failure sessions.
- Deduplicated crash-or-hard-failure union and `numerator = started - union`
  calculation.
- Exclusions, missing logs, and duplicated-session handling.

The 3-5 person Stage A pilot may validate this accounting and report a
descriptive percentage, but its small sample does not by itself establish the
HYP-M6-002 99% beta claim.

## Current Decision

Do not claim "99% crash-free beta sessions" yet. The task is blocked until real
beta sessions exist and the complete private ledger, opt-in diagnostics where
available, and manual reports reconcile to a measurable denominator.
