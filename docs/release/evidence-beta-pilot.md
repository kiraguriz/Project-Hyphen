# Evidence Beta Pilot (3-5 People)

- **Owner**: a maintainer explicitly assigned before invitations are sent.
- **Tracker**: HYP-M4-011, HYP-M4-012, HYP-M6-001, HYP-M6-002.
- **Status**: local pilot kit ready; no participant, invitation, distribution,
  consent, session, or runtime result exists yet.
- **Purpose**: learn whether Project Hyphen's release-critical journeys can be
  executed safely and recorded consistently before considering a 20-50 person
  private beta.

This is an evidence gate, not a launch plan. Creating these documents does not
authorize recruitment, external messages, account or channel setup, signing,
artifact distribution, or collection of personal data.

Use this plan with the [session record template](evidence-beta-session-record.md),
[exit review template](evidence-beta-exit-review.md),
[private beta release notes](private-beta-release-notes.md), and
[feedback plan](private-beta-feedback.md).

## Stage Boundary

Project Hyphen uses two deliberately separate stages:

1. **Stage A — evidence beta**: 3-5 invited technical participants, one
   maintainer-owned private channel, exact build identity, synthetic test data,
   and session-level evidence.
2. **Stage B — expanded private beta**: 20-50 participants only after the
   Stage A exit review records an explicit maintainer `GO`.

Do not count maintainers running local checks as accepted participants unless
they complete the same consent, session, and evidence record as every other
participant. Do not combine two people into one tester ID or one person using
several devices or several pseudonymous IDs into several participants.

## Preconditions Before Any Invitation

The assigned maintainer must record all of the following outside this repo's
tracked participant data:

- The exact candidate commit and clean repository state.
- A passing `./scripts/check.sh --strict` result on that commit.
- Android and macOS artifact filenames, build type, SHA-256 checksums, and how
  they were produced. Every artifact must say `evidence beta`, `pre-alpha`, and
  `not a public/signed/store release` unless its real signing evidence says
  otherwise.
- A private feedback channel, a named coordinator, a security-reporting path,
  and a stop/recall path controlled by the maintainer.
- A 3-5 person candidate list held outside the repository. Do not commit names,
  email addresses, account handles, device serials, advertising IDs, IP
  addresses, or other participant identifiers. The coordinator must privately
  attest that participant IDs map one-to-one to real people and deduplicate any
  reissued IDs.
- The release notes and current known blockers supplied with the artifacts.
- A safe test-data pack: synthetic notifications, synthetic text/link fixture
  IDs, and generated files containing no personal or production data.
- A local encrypted location for raw evidence, plus a redacted export location
  for any non-linkable repository-safe aggregate. Record the evidence owner,
  shortest necessary retention window, hard deletion deadline, and immediate
  deletion path for withdrawn consent.

If a prerequisite is missing, record `BLOCKED` and do not invite or distribute.

## Participant Mix

Select 3-5 technical participants who can follow the redaction and stop rules.
The small pilot is not a compatibility matrix and must not claim broad support.
Within the accepted group, aim for:

| Coverage goal | Pilot minimum | Boundary |
|---|---:|---|
| Distinct people completing a session | 3 | Maximum 5; fewer than 3 cannot exit Stage A. |
| Android device/OEM families | 2 | Record exact model, OS, and OEM skin; no support claim follows. |
| Mac hardware/macOS combinations | 2 | Apple silicon is primary; Intel/older supported macOS is useful but optional. |
| Network cases | 2 | Include normal same-LAN plus one hotspot, mesh, restricted, or fallback case. |
| QR/manual fallback observation | 1 | Must use the real system-camera or manual path, not a parser-only test. |
| Accessibility-assisted session | 1 where available | TalkBack or VoiceOver evidence remains blocked if no participant can run it. |

One participant may cover several device/network goals, but still counts as one
person. Missing mix goals stay explicit in the exit review.

## Consent And Safety Briefing

Before a session, the coordinator must obtain explicit opt-in and record only a
pseudonymous participant ID (`EB-01` through `EB-05`) in the session record.
The participant must be told:

- Participation is voluntary and can stop at any time without explanation.
- Hyphen is pre-alpha, local-first, and has no public release claim.
- Notification Listener access can expose notification titles, bodies, and
  actions. Use synthetic/test notifications and disable access after the run if
  it is not wanted.
- Use generated files and fixture text. Never use personal notifications,
  messages, photos, documents, URLs, contacts, credentials, or production data.
- Diagnostics are off by default. Any opt-in is scoped to the session,
  user-visible, and can be disabled and deleted immediately.
- Raw logs/screenshots remain local. Private issues must be redacted, and any
  repository summary must additionally be aggregate and non-linkable after
  singling-out review. Suspected security/privacy defects use `SECURITY.md`,
  not a normal issue.
- The coordinator will state the raw-evidence retention window and deletion
  date before testing; withdrawal stops collection and follows the recorded
  deletion path without erasing the pseudonymous aggregate attempt count.
- The participant chooses whether irreversibly anonymized results may remain
  eligible after evidence-use consent is withdrawn. Without explicit retained-
  aggregate consent, withdrawal removes that person's contribution from the
  distinct-person, journey-pass, and coverage gates; only non-identifying
  attempt/disposition counts remain, and any `GO` calculation is rerun. Before
  deleting journey detail, the coordinator reconciles the disposition rules
  and retains only an anonymous attestation/count of that completed check.
  Removing a started attempt's hard-failure flag or other required evidence
  makes it missing-required-evidence and blocks `GO`; absence is never `no`.
- The coordinator may stop the run immediately under the stop conditions below.

Consent is not inferred from artifact download, app launch, or a submitted bug.
Do not store signed consent forms or participant identity in this repository.

## Session Protocol

Each accepted participant gets one stable pseudonymous participant ID such as
`EB-01`. Every attempted run gets a new session ID (`EB-01-S01`, then
`EB-01-S02`) before preflight and one
[session record](evidence-beta-session-record.md). A changed build, device,
network combination, repeated preflight, or restarted run is a new attempt and
must not overwrite the prior record. Scenario repetition inside an uninterrupted
started session may use the repeat-count field; it does not create a new run.
Mixed repeats roll up to the non-pass result (`fail` before `blocked`, then
`pass`, then `not-run`), while the terminating scenario is always `stopped`, so
a later pass cannot erase an earlier failure.

A Stage A exit review selects one final candidate Android/macOS artifact pair.
All people, coverage, and journey passes used to justify `GO` must come from
that exact commit/build/checksum pair. Earlier or alternate-build attempts stay
in failure and attempt accounting but cannot be stitched together to satisfy
the final-candidate gates.

### 1. Identity And Preflight

- Match the commit, artifact names, and SHA-256 checksums to the pilot manifest.
- Record Android model/OS/OEM, Mac family/macOS, and network class without
  serial numbers, hostnames, SSIDs, IP addresses, or account names.
- Confirm the release notes, security path, stop path, and synthetic test pack
  are available.
- Confirm diagnostics are off before testing and no forbidden permission or
  service appears.

### 2. Required Journeys

Record `pass`, `fail`, `blocked`, `stopped`, or `not-run` for each journey. Use
`stopped` for the journey that ended the run and `not-run` for later journeys.
A pass applies only to the exact session combination.

A stopped started session has exactly one terminating `stopped` journey and
all later required journeys are `not-run`. A completed, incomplete, or
preflight-blocked attempt cannot contain a `stopped` journey; all journey rows
for a preflight-blocked attempt are `not-run`.

| Journey | Minimum observation | Safety/evidence note |
|---|---|---|
| Install and first use | Both apps launch; local-only scope and optional permissions are explained before action. | Record artifact identity and visible state, not participant identity. |
| Pairing and bilateral SAS | QR or manual entry reaches both SAS views; trust is stored only after both accept. | Also perform one safe mismatch/reject or cancel path in the pilot set. |
| Fallback and recovery | At least one session uses manual/QR fallback; one controlled disconnect exposes degraded/retry/recovery state. | Do not alter routers or enterprise networks without owner authorization. |
| Notification privacy | Synthetic notification post/update/remove plus one privacy tightening/disable path. | No personal notification content; record fixture IDs only. |
| Text and link | One send in each direction with explicit confirmation/copy/open state. | Use supplied fixture text/link IDs; do not paste the payload into the report. |
| File transfer | Generated small file, multi-file progress/cancel, and one controlled interruption/resume or explicit blocker. | Verify destination and hash/checksum without attaching file contents. |
| Diagnostics | Preview, user-triggered export/cancel where available, delete, and default-off opt-in/disable behavior. | Inspect redaction locally; attach only a redacted derivative. |
| Revoke/reset | Forget the peer or reset trust; reconnect must require re-pairing. | Confirm stale peer state does not return. |
| Accessibility/localization | Run the available TalkBack/VoiceOver/keyboard/large-text/bilingual checks. | `not-run` is honest; it does not satisfy the existing runtime gate. |

The 1 GiB, 20-cycle sleep/wake, five-Android, and three-Mac matrices remain
separate evidence plans. Private pilot evidence may contribute only a reviewed,
non-linkable aggregate toward those plans and cannot silently close the gates.

### 3. Closeout

- Disable any session-only diagnostics opt-in and delete its local bundle.
- Remove synthetic notifications/files when safe and revoke the test peer if
  the participant is not continuing.
- Record crashes, hangs, privacy events, trust failures, transfer corruption,
  and recovery failures as counts with exact scenario IDs.
- Redact evidence, route security-sensitive material privately, and obtain a
  coordinator completeness review.
- Record the raw-evidence deletion deadline and delete on schedule or earlier
  withdrawal; preserve only the non-identifying aggregate accounting required
  to keep attempted and stopped runs visible.

## Stop Conditions

Stop the affected session immediately, preserve only minimal local evidence,
and notify the maintainer through the assigned path if any of these occurs:

- Trust is stored before bilateral SAS acceptance, or a rejected/mismatched
  peer reconnects as trusted.
- Notification/file/text content appears where the selected privacy policy says
  it must not, or unredacted content enters diagnostics or a report.
- A forbidden permission/surface appears: SMS, Call Log, Accessibility service,
  location, contacts, background clipboard, private API, root, cloud/NAT relay,
  default telemetry, or automatic crash upload.
- File loss, overwrite, corruption, path escape, cross-peer resume, or stale
  transfer publication is observed.
- Diagnostics enable without explicit action, cannot be disabled/deleted, or
  export different data from the preview.
- The artifact identity/checksum is wrong, or a local/ad-hoc build is presented
  as signed, notarized, store-ready, RC, or public.
- A crash loop, unrecoverable pairing loop, or other behavior makes continued
  testing unsafe.

A suspected vulnerability follows `SECURITY.md`. Do not copy exploit details
into the normal session record or issue form.

## Stage A Exit Review

The coordinator prepares the redacted aggregate using the
[exit review template](evidence-beta-exit-review.md). Stage A can record `GO`
for considering Stage B only when every required item is true:

- The coordinator attests from the private identity map that 3-5 participant
  IDs represent 3-5 distinct real people, with reissued IDs deduplicated. Each
  eligible person explicitly opted in and completed at least one final-candidate
  checksum-bound started session through planned closeout.
- At least two Android/OEM families, two Mac/macOS combinations, and two network
  cases were actually observed, or the maintainer records a specific accepted
  coverage cut and its risk.
- Every core journey from install through revoke/reset has at least one
  consent-eligible `pass` on the single final candidate artifact pair. Every
  consent-eligible `fail`, `blocked`, `stopped`, and `not-run` from every
  artifact set remains visible and cannot be converted to a pass or discarded
  after a successful retry. Evidence-use withdrawal without retained-aggregate
  consent removes journey detail under the withdrawal rule, records that
  removal explicitly, and still preserves anonymous attempt/disposition counts.
- Accessibility/localization has at least one named-setup pass, or the
  maintainer records a specific accepted coverage cut and risk. Either outcome
  leaves the separate TalkBack, VoiceOver, keyboard, large-text, bilingual, and
  device-matrix runtime gates open until their own evidence is complete.
- No unresolved stop-condition event, P0 trust/privacy/data-loss issue, or
  security report remains open.
- Every failure is assigned a stable category, severity, owner, and disposition;
  repeated failures are ranked without calling them the v1 “top 10” unless the
  corpus supports that claim.
- Every issued session ID is reconciled. `attempted = preflight-blocked +
  started + unreconciled`, and `started = completed + stopped + incomplete`.
  `failed` and `missing-evidence` are cross-cutting flags, not categories that
  can be subtracted. `GO` requires zero unreconciled attempts and zero missing
  required evidence.
- Retained terminating `stopped` scenario results plus anonymously attested
  accounting-only stopped dispositions equal stopped started sessions, with
  exactly one checked terminator per stopped session and none in any other
  disposition.
- Every started session is counted in the crash denominator. Crash-free
  numerator is `started - unique crash-or-hard-failure sessions`, using the
  exact HYP-M6-002 boundary and deduplicating multiple events in one session.
  The percentage may be calculated only after evidence reconciliation, and the
  small pilot cannot by itself establish the HYP-M6-002 99% beta claim.
- All repository-facing evidence passed redaction review; raw data remains local
  and participant identities are absent from the repo.
- The exact artifact set can be recalled, and the coordinator records an
  explicit `GO`, `HOLD`, or `STOP` with date and unresolved risks.
- Every raw-evidence item has an owner and deletion deadline; expired or
  withdrawn-consent data is deleted without removing aggregate attempt counts.
- Evidence-use withdrawals have been applied: unless explicit consent permits
  retention of irreversibly anonymized aggregates, withdrawn contributions are
  removed from people/pass/coverage gates and the decision is recomputed.

`GO` permits planning or inviting Stage B; it does not create a release claim.
If any condition is false, remain at `HOLD` or `STOP` and do not expand.

## Current External Blockers

At the time this kit was written:

- No maintainer has authorized a pilot or accepted the coordinator role.
- No private channel, participant list, consent records, or synthetic data pack
  has been created for a real session.
- No checksum-bound pilot artifact set has been distributed.
- No participant has accepted, run, or completed a session.
- No Stage A exit review or Stage B `GO` exists.

Therefore this document proves Implementation of the pilot process only. It is
not Runtime, beta-cohort, distribution, signing, or Release evidence.
