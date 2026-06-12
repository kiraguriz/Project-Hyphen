# HYP-M6-002 Crash-Free Beta Sessions Metrics Review

Status: blocked / not measurable yet

## Goal

HYP-M6-002 asks Hyphen to achieve at least 99% crash-free beta sessions where measurable. Because Hyphen has no telemetry by default, this metric can only be claimed from explicit opt-in diagnostics or manually submitted beta logs.

## Metric Definition

Draft definition for the eventual beta review:

- A beta session starts when a paired Android/macOS connection reaches steady-state protocol session setup.
- A session is crash-free when neither side reports an app/process crash, forced termination, unrecoverable protocol failure, or user-visible hard failure before the user disconnects or the session closes normally.
- The denominator is the count of beta sessions represented in opt-in diagnostics exports or manual beta issue/log submissions.
- The numerator is denominator minus sessions with crash/hard-failure evidence.
- The claim must include the source window, sample size, and known coverage gaps.

## Evidence Available Today

No crash-free beta-session claim is currently supportable:

- HYP-M4-012, recruiting 20-50 technical beta users, is still unchecked.
- No beta release notes or beta feedback channel are complete yet (HYP-M4-011 is unchecked).
- HYP-M4-004 implemented the default-off beta diagnostics toggle, but Android manual preview/export/disable acceptance is still blocked by lack of an attached device/emulator.
- The Android app module currently has no crash-reporting SDK, analytics SDK, telemetry endpoint, account system, or cloud service.
- Repository search found no existing beta session logs, crash ledger, issue export, or user-submitted diagnostics corpus.

`adb devices -l` currently lists no Android targets, so a new paired-device beta session cannot be generated in this environment.

## Required Evidence To Unblock

Before marking HYP-M6-002 complete, collect one of:

- Opt-in diagnostics exports from a real beta cohort, with session counts and explicit crash/hard-failure labels.
- A manual beta issue/log ledger that records session start/end, platform versions, crash/hard-failure outcome, and whether diagnostics were attached.

Minimum review packet:

- Beta build identifier and commit.
- Collection window.
- Number of beta users and devices.
- Number of represented sessions.
- Number of crash/hard-failure sessions.
- Crash-free percentage calculation.
- Exclusions, missing logs, and duplicated-session handling.

## Current Decision

Do not claim "99% crash-free beta sessions" yet. The task is blocked until beta sessions exist and opt-in diagnostics or manual logs provide a measurable denominator.
