# Evidence Beta Session Record Template

Copy this template to a maintainer-controlled private location for each
attempted run, including one blocked during preflight. Do not commit completed
participant records, raw diagnostics,
screenshots, logs, consent forms, or private issue links to this repository.
Only a redacted, non-linkable aggregate may be proposed for repository review
after singling-out review.

Use with the [3-5 person pilot plan](evidence-beta-pilot.md) and
[exit review template](evidence-beta-exit-review.md).

## Record Metadata

| Field | Value |
|---|---|
| Pilot authorization ID | `TBD` |
| Artifact set ID | `AS-0X` |
| Pseudonymous participant ID | `EB-0X` |
| Session ID | `EB-0X-S0Y` |
| Coordinator | `assigned outside repository` |
| Session start/end and timezone | `TBD` |
| Consent confirmed before install/run | `yes / no` |
| Consent record location | `private external pointer; never a participant identifier here` |
| Irreversibly anonymized aggregate eligible after evidence-use withdrawal | `yes / no` |
| Attempt disposition | `preflight-blocked / completed / stopped / incomplete` |
| Started-session denominator | `yes / no / unknown` |
| Any failed journey | `yes / no / unknown` |
| Crash-or-hard-failure under HYP-M6-002 boundary | `yes / no / unknown` |
| Required evidence complete | `yes / no / unknown` |
| Stop-condition event | `none / private incident reference` |
| Raw-evidence owner / deletion deadline | `private role or pointer / date; no identity` |

Issue the session ID before preflight. `Consent confirmed = no` makes this a
`preflight-blocked` attempt and the ID must remain in aggregate accounting. A
changed build/device/network combination, repeated preflight, or restarted run
gets a new session ID; never overwrite the prior record. Do not record a name,
email, handle, device serial, hostname, SSID, IP address, account, or advertising
identifier.

If evidence-use consent is withdrawn, stop collection and follow the deletion
path. Always retain only non-identifying attempt/disposition accounting. Unless
the participant explicitly permitted retention of irreversibly anonymized
aggregates, mark their person, journey-pass, and coverage contributions
ineligible and require the exit review to recompute its decision.

For HYP-M6-002, a user-visible hard failure means the started session or a
required journey cannot safely continue or reach intended closeout without a
process restart/force quit, reinstall, re-pair or trust reset, manual data
repair/restoration, or operator abort for an unresolved hang/loop. A recoverable
visible error or explicit degraded state that still permits safe retry/closeout
is a failed/blocked journey, not automatically a hard failure. Any qualifying
condition sets the flag regardless of attempt disposition; several events in
one session still count once.

## Exact Build Identity

| Field | Android | macOS |
|---|---|---|
| Git commit | `TBD` | `TBD` |
| Artifact filename | `TBD` | `TBD` |
| SHA-256 | `TBD` | `TBD` |
| Build type/channel | `evidence beta / pre-alpha / local or approved private channel` | `evidence beta / pre-alpha / local or approved private channel` |
| Signing/notarization observation | `debug/ad-hoc/signed — record fact only` | `ad-hoc/signed/notarized — record fact only` |
| Checksum matched manifest | `yes / no` | `yes / no` |

If either checksum is missing or mismatched, set attempt disposition to
`preflight-blocked` and stop before behavioral evidence collection.

## Environment (No Unique Identifiers)

| Field | Value |
|---|---|
| Android model family | `TBD` |
| Android version / security patch month | `TBD` |
| OEM skin | `TBD` |
| Mac family / architecture | `TBD` |
| macOS version | `TBD` |
| Network class | `same-LAN / mesh / hotspot / restricted / fallback / other` |
| Assistive technology | `TalkBack / VoiceOver / keyboard / none / not-run` |
| App language(s) | `English / Simplified Chinese / other` |

Do not record serial numbers, hostnames, SSIDs, BSSIDs, IP addresses, MAC
addresses, phone numbers, account names, or precise location.

## Preflight

- [ ] Participant explicitly opted in before installation or launch.
- [ ] Participant selected whether irreversibly anonymized aggregates remain
      eligible after evidence-use withdrawal and understands the default
      exclusion/recalculation rule.
- [ ] Exact commit, filenames, and both SHA-256 values match the pilot manifest.
- [ ] Artifacts are labelled pre-alpha evidence beta and are not presented as a
      public, store-ready, RC, signed, or notarized build without matching proof.
- [ ] `./scripts/check.sh --strict` passed on the candidate commit.
- [ ] Release notes, known blockers, private feedback path, security path, and
      recall/stop instructions are available.
- [ ] Only the supplied synthetic notification/text/link/file fixtures will be
      used.
- [ ] Diagnostics are off before the run.
- [ ] No forbidden permission or surface is present.

Any unchecked item makes the attempt `preflight-blocked`, not a partial pass.
Any `unknown` reconciliation field or `required evidence complete != yes`
counts as missing required evidence and blocks Stage A `GO`.

## Journey Results

Use stable scenario IDs. Evidence pointers must identify a redacted local
artifact without embedding its contents.

| Scenario ID | Journey / action | Expected | Observed (redacted) | Result | Repeat count | Evidence / private issue pointer |
|---|---|---|---|---|---:|---|
| EB-INSTALL-01 | Install, launch, first-use explanation | Local-only and optional-permission copy appears before action. | `TBD` | `pass/fail/blocked/stopped/not-run` | 0 | `TBD` |
| EB-PAIR-01 | QR or manual pairing and bilateral SAS | Both sides show matching SAS; trust waits for both acceptance events. | `TBD` | `pass/fail/blocked/stopped/not-run` | 0 | `TBD` |
| EB-PAIR-02 | Safe mismatch/reject/cancel path | No trust persists and retry starts cleanly. | `TBD` | `pass/fail/blocked/stopped/not-run` | 0 | `TBD` |
| EB-RECOVER-01 | Controlled disconnect and explicit retry/recovery | Degraded state is visible; stale callbacks do not claim connection. | `TBD` | `pass/fail/blocked/stopped/not-run` | 0 | `TBD` |
| EB-NOTIFY-01 | Synthetic post/update/remove | One fixture key maps consistently; removal clears it. | `TBD` | `pass/fail/blocked/stopped/not-run` | 0 | `TBD` |
| EB-PRIVACY-01 | Tighten/disable notification policy | Restricted content does not cross the selected boundary; local rows purge. | `TBD` | `pass/fail/blocked/stopped/not-run` | 0 | `TBD` |
| EB-TEXT-01 | Fixture text/link in both directions | Explicit confirmation/copy/open state appears. | `TBD` | `pass/fail/blocked/stopped/not-run` | 0 | `TBD` |
| EB-FILE-01 | Generated small and multi-file transfer | Progress/destination/hash are correct; no overwrite occurs. | `TBD` | `pass/fail/blocked/stopped/not-run` | 0 | `TBD` |
| EB-FILE-02 | Cancel and controlled interruption/resume | Cancellation is per file; resume or blocker is explicit. | `TBD` | `pass/fail/blocked/stopped/not-run` | 0 | `TBD` |
| EB-DIAG-01 | Preview/export or cancel/delete | Preview and export are the same redacted snapshot; delete clears it. | `TBD` | `pass/fail/blocked/stopped/not-run` | 0 | `TBD` |
| EB-DIAG-02 | Default-off opt-in/disable | Opt-in requires action; disable is immediate and visible. | `TBD` | `pass/fail/blocked/stopped/not-run` | 0 | `TBD` |
| EB-TRUST-01 | Forget peer or reset all | Reconnect requires fresh pairing; stale state does not return. | `TBD` | `pass/fail/blocked/stopped/not-run` | 0 | `TBD` |
| EB-A11Y-01 | Available accessibility/localization checks | Focus/order/labels/actions and large text are usable in the named setup. | `TBD` | `pass/fail/blocked/stopped/not-run` | 0 | `TBD` |

Every scenario has exactly one aggregate result in this attempt. For repeated
observations, a later pass cannot erase an earlier non-pass: `stopped` is used
only for the terminator; otherwise roll up `fail` before `blocked`, `blocked`
before `pass`, and `pass` before `not-run`. Record repeat counts and details
privately. Restarting the run creates a new session ID instead of a repeat.

If attempt disposition is `stopped`, exactly one journey is the terminating
`stopped` result and every later required journey is `not-run`. Completed,
incomplete, and preflight-blocked attempts contain no `stopped` result; every
journey in a preflight-blocked attempt is `not-run`.

## Failure And Session Accounting

| Metric | Count / definition |
|---|---|
| Attempted runs | `1 once this unique session ID is issued before preflight` |
| Preflight-blocked attempts | `0 or 1; no required journey began` |
| Started-session denominator | `0 or 1; preflight passed and the first required journey began` |
| Completed started sessions | `0 or 1; planned closeout reached even if a journey failed/blocked` |
| Stopped started sessions | `0 or 1; participant/stop condition ended before planned closeout` |
| Incomplete started sessions | `0 or 1; neither completed nor stopped, including lost follow-up` |
| Failed started sessions | `0 or 1; cross-cutting, may overlap completed or stopped` |
| Missing required evidence | `0 or 1; cross-cutting and blocks Stage A GO` |
| Crash-or-hard-failure started sessions | `0 or 1; union of crash, failure-driven forced termination, unrecoverable protocol failure, or user-visible hard failure` |
| App crashes | `TBD` |
| Hangs/forced restarts | `TBD` |
| Pairing/trust failures | `TBD` |
| Privacy/redaction failures | `TBD` |
| Transfer corruption/data-loss events | `TBD` |
| Recovery failures | `TBD` |
| Other failures | `TBD with stable category` |

For a complete record, `attempted = preflight-blocked + started` and `started =
completed + stopped + incomplete`. `failed`, `missing evidence`, and
`crash-or-hard-failure` are cross-cutting flags. Multiple qualifying hard-
failure events in one session count once in the union. The exit review also
counts issued IDs with a missing record as `unreconciled`; those block `GO`.
Count every started session in the crash denominator, including stopped and
failed sessions. `crash-free numerator = started - unique crash-or-hard-failure
sessions`. A retry gets a new record and cannot erase the prior attempt. This
small sample cannot establish the HYP-M6-002 99% claim.

## Safety And Redaction Closeout

- [ ] No personal notification, message, file, URL, contact, credential, or
      production content was used.
- [ ] Raw evidence stayed in the approved local encrypted location.
- [ ] The evidence owner and deletion deadline are recorded; expired or
      withdrawn-consent raw evidence was deleted without erasing aggregate
      attempt accounting.
- [ ] Evidence-use withdrawal eligibility was applied; absent explicit retained-
      aggregate consent, this person's pass/coverage/person contributions are
      excluded from `GO` and the decision is recomputed.
- [ ] Repository-facing evidence removes payloads, identifiers, addresses,
      account data, private issue URLs, and participant identity.
- [ ] Security/privacy exploit details, if any, were routed through
      `SECURITY.md` and are absent here.
- [ ] Session-only diagnostics were disabled and deleted after the run.
- [ ] Synthetic notifications/files were removed where safe.
- [ ] Peer trust was revoked if the participant is not continuing.
- [ ] Every fail/blocked/stopped result has an owner and private disposition.

## Coordinator Review

| Review item | Decision |
|---|---|
| Record complete and checksum-bound | `yes / no` |
| Redaction review passed | `yes / no` |
| Stop condition triggered | `no / private incident reference` |
| Results accepted into aggregate | `yes / no / needs correction` |
| Follow-up owner and due date | `TBD` |

Coordinator notes must separate observed facts, participant report, and
maintainer inference. Acceptance into the aggregate does not turn a session
result into broad device, beta, or release proof.
