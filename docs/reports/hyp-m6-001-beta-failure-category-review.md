# HYP-M6-001 Beta Failure Category Review

- **Date**: 2026-06-12
- **Tracker**: HYP-M6-001
- **Status**: blocked; no beta issue/failure corpus exists yet.

## Goal

HYP-M6-001 asks Hyphen to fix the top 10 beta crash/failure categories. This
requires a ranked beta corpus: issues, diagnostics exports, crash/hard-failure
logs, or a manual ledger from the private beta cohort.

## Evidence Search

Current local evidence does not contain a top-10 failure list:

- `find . -maxdepth 4 -type f | rg -i "issue|crash|failure|beta|diagnostic|feedback|log|report"`
  finds release notes, feedback templates, M6 reports, and diagnostics plans,
  not beta issue exports or session logs.
- `rg "crash|failure category|top 10|beta session|issue export|feedback corpus"`
  confirms that the repo repeatedly records the same blocker: no beta feedback
  corpus exists yet.
- `.github/ISSUE_TEMPLATE/private_beta_bug.yml` and
  `docs/release/private-beta-feedback.md` are ready for future intake, but they
  have not produced reports.

## Current Known Risk Buckets

The repo does contain known blockers and unverified areas. These are not a
ranked beta top-10 list, but they should be used as labels when real reports
arrive:

| Candidate bucket | Current source | Current state |
|---|---|---|
| Android device/OEM compatibility | HYP-M4-005 | Blocked: no attached Android devices/emulators. |
| macOS OS/device compatibility | HYP-M4-006 | Blocked: no three-combo Mac matrix. |
| Sleep/wake reconnect | HYP-M4-007 / HYP-M6-003 | Blocked: 20-cycle run not scheduled. |
| Notification end-to-end mirror/dismiss | HYP-M3-004..006 | Implementation exists; paired-device manual checks blocked. |
| Quick Reply compatibility | HYP-M3-007 | Implementation exists; three app-family matrix blocked. |
| 1GB transfer resume | HYP-M3-015 / HYP-M6-004 | Scaffold exists; three real combo runs unavailable. |
| Android diagnostics UI | HYP-M4-002 / HYP-M4-004 | Implementation exists; attached-device UI verification blocked. |
| Peer management revoke/reset | HYP-M4-010 | Implementation exists; real paired-session verification blocked. |
| Public packaging/signing | HYP-M5-002 / HYP-M5-004 / HYP-M5-010 | External signing/notary/channel gates blocked. |
| License release readiness | HYP-M6-008 | Audit done; root license/map/notice policy incomplete. |

## Required Evidence To Unblock

Before marking HYP-M6-001 complete:

1. Run a private beta cohort through HYP-M4-012.
2. Export or summarize private beta issues using the bug template fields:
   scenario, platform, device/OS/network, result, repeatability, and redacted
   diagnostics availability.
3. Group failures by cause, not by symptom text.
4. Rank categories by severity first, then affected users/devices, then
   reproduction quality.
5. For each top-10 category, either fix the concrete issue, document the
   limitation with a cut decision, or open a follow-up tracker task.

## Review Table Template

| Rank | Category | Severity | Evidence count | Affected devices/networks | Decision | Fix/Doc link |
|---:|---|---|---:|---|---|---|
| 1 | TBD | TBD | TBD | TBD | fix / document / cut | TBD |
| 2 | TBD | TBD | TBD | TBD | fix / document / cut | TBD |
| 3 | TBD | TBD | TBD | TBD | fix / document / cut | TBD |
| 4 | TBD | TBD | TBD | TBD | fix / document / cut | TBD |
| 5 | TBD | TBD | TBD | TBD | fix / document / cut | TBD |
| 6 | TBD | TBD | TBD | TBD | fix / document / cut | TBD |
| 7 | TBD | TBD | TBD | TBD | fix / document / cut | TBD |
| 8 | TBD | TBD | TBD | TBD | fix / document / cut | TBD |
| 9 | TBD | TBD | TBD | TBD | fix / document / cut | TBD |
| 10 | TBD | TBD | TBD | TBD | fix / document / cut | TBD |

## Decision

Do not infer or "pre-fix" a top-10 beta list from internal blocker notes. The
task is blocked until real beta feedback exists.
