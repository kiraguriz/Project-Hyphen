# Evidence Beta Stage A Exit Review Template

Complete this review in a maintainer-controlled private location after the
3-5 person Stage A pilot. Do not commit participant identity, consent records,
raw diagnostics, screenshots, logs, private issue links, or the candidate list.
Only a redacted, non-linkable aggregate may be proposed for repository review
after singling-out review.

Use with the [pilot plan](evidence-beta-pilot.md) and
[session record](evidence-beta-session-record.md).

## Decision Metadata

| Field | Value |
|---|---|
| Review ID / date | `TBD` |
| Authorized coordinator | `private role or pointer; no participant identity` |
| Pilot authorization reference | `private pointer` |
| Final candidate commit | `TBD` |
| Final Android artifact / build type / SHA-256 | `TBD` |
| Final macOS artifact / build type / SHA-256 | `TBD` |
| Collection window | `TBD` |
| Decision | `GO / HOLD / STOP` |
| Decision owner | `maintainer role` |

A missing authorization, artifact identity, checksum, or decision owner forces
`HOLD` or `STOP`.

## Artifact-Set Inventory

Inventory every tested pair. Exactly one final candidate pair may contribute
people, coverage, or journey passes to `GO`; earlier/alternate sets remain in
attempt and failure accounting.

| Set ID | Commit | Android artifact / type / SHA-256 | macOS artifact / type / SHA-256 | Attempts | Eligible for GO |
|---|---|---|---|---:|---|
| `AS-01` | `TBD` | `TBD` | `TBD` | `TBD` | `yes — final / no` |

The row marked final must exactly match Decision Metadata. More than one
`Eligible for GO = yes` row, an unlisted artifact set, or a pass/coverage count
stitched across sets blocks `GO`.

## Attempt Reconciliation

Every session ID is issued before preflight. A changed build/device/network
combination, repeated preflight, or restarted run uses a new ID. Do not include
participant identity or list the private session IDs in a repository summary.

| Count | Value | Definition |
|---|---:|---|
| Distinct opted-in real people eligible for GO | `TBD` | Coordinator attests IDs map one-to-one to 3-5 real people on the final candidate; reissued IDs are deduplicated. |
| Evidence-use withdrawals | `TBD` | Count and whether retained-aggregate consent permits any contribution beyond anonymous attempt/disposition accounting. |
| Withdrawn attempts with journey aggregates removed | `TBD` | Explicit count; their people, coverage, passes, and journey details are ineligible, but attempt/disposition accounting remains. |
| Attempted runs | `TBD` | Every issued session ID. |
| Scenario-record denominator | `TBD` | Consent-eligible attempts with one retained result for every exact scenario ID. |
| Accounting-only attempts | `TBD` | No consent or withdrawn journey aggregates; only anonymous attempt/disposition accounting remains. |
| Missing scenario-record attempts | `TBD` | Expected scenario rows are absent; cross-cutting missing evidence that blocks `GO`. |
| Preflight-blocked attempts | `TBD` | Consent/checksum/other preflight prevented a required journey from starting. |
| Started sessions | `TBD` | Preflight passed and first required journey began; crash denominator. |
| Completed started sessions | `TBD` | Planned closeout reached, even if journeys failed or blocked. |
| Stopped started sessions | `TBD` | Participant or stop condition ended the run before closeout. |
| Incomplete started sessions | `TBD` | Started but neither completed nor stopped. |
| Failed started sessions | `TBD` | Cross-cutting count; may overlap completed or stopped. |
| Unreconciled attempts | `TBD` | Issued ID cannot be classified as preflight-blocked or started. |
| Missing-required-evidence attempts | `TBD` | Cross-cutting count; expected record/evidence is absent. |
| Retained terminating stopped-scenario results | `TBD` | Exactly one per stopped session whose scenario aggregate remains consent-eligible. |
| Accounting-only stopped dispositions | `TBD` | Anonymous checked count after consent/withdrawal removes scenario detail. |
| Crash-or-hard-failure started sessions | `TBD` | Deduplicated union of crash, failure-driven forced termination, unrecoverable protocol failure, or user-visible hard failure. |

Required arithmetic:

- `attempted = preflight-blocked + started + unreconciled`
- `attempted = scenario-record denominator + accounting-only attempts + missing
  scenario-record attempts`
- `started = completed + stopped + incomplete`
- `failed`, `missing-required-evidence`, and `crash-or-hard-failure` are
  cross-cutting and are not subtracted from either equation.
- `retained terminating stopped-scenario results + accounting-only stopped
  dispositions = stopped started sessions`.

The scenario-record, accounting-only, and missing-scenario-record buckets are
mutually exclusive and exhaustive. Withdrawn/no-consent rows with intentionally
removed journey detail are accounting-only, not silently missing; an expected
record that cannot be found is missing, not accounting-only.

Any mismatch, unreconciled attempt, missing required evidence, or omitted
stopped/failed run or stopped-journey mismatch blocks `GO`. When evidence is
complete, `crash-free numerator = started - unique crash-or-hard-failure
sessions`; multiple qualifying events in one session count once. Stage A may
report the resulting descriptive percentage over all started sessions, but
cannot establish the HYP-M6-002 99% claim.

Every `unknown` intake/reconciliation field and every attempt whose
`required evidence complete` value is not `yes` enters the missing-required-
evidence count. A `GO` packet has zero such attempts.

Here, user-visible hard failure means a started session or required scenario
cannot safely continue or reach intended closeout without a process restart or
force quit, reinstall, re-pair or trust reset, manual data repair/restoration,
or operator abort for an unresolved hang/loop. A recoverable visible error or
explicit degraded state that permits safe retry/closeout is not automatically a
hard failure. Any qualifying condition sets the flag regardless of disposition.

The coordinator privately attests that pseudonymous IDs map one-to-one to real
people and that reissued IDs are deduplicated. If evidence-use consent is
withdrawn, retain only anonymous attempt/disposition accounting by default.
Unless prior consent explicitly permits retention of irreversibly anonymized
aggregates, remove that person's contribution from the distinct-person,
journey-pass, and coverage gates and recompute the decision. Before deleting
journey detail, reconcile its disposition/stopped rules and retain only the
anonymous checked count needed for accounting integrity.

If a started attempt's crash-or-hard-failure flag or other required evidence is
removed, that attempt also enters missing-required-evidence and blocks `GO`;
recomputation cannot turn an unavailable outcome into `no`.

## Observed Coverage

| Coverage | Final-candidate observed count / cases | Gap or accepted cut |
|---|---|---|
| Android model/OEM families | `TBD` | `TBD` |
| Mac hardware/macOS combinations | `TBD` | `TBD` |
| Network classes | `TBD` | `TBD` |
| Real QR/manual fallback | `TBD` | `TBD` |
| Accessibility/localization sessions | `TBD` | `TBD` |

The pilot does not complete the five-Android, three-Mac, 20-cycle sleep/wake,
1 GiB multi-combination, TalkBack, VoiceOver, or broad compatibility gates
unless their separate acceptance evidence is actually met and linked.

## Journey Aggregate

Record all consent-eligible outcome counts, not participant identity or
payloads. Use the exact session-template scenario IDs: do not collapse a safe
mismatch into a pairing pass or a diagnostics-disable failure into a preview
pass. The first column identifies eligible passes from the single final
candidate; earlier artifact-set outcomes remain visible but cannot supply it.

| Scenario ID | Final-candidate pass | All-set pass | All-set fail | All-set blocked | All-set stopped | All-set not-run | Row total | Expected denominator | Evidence gap / owner |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| EB-INSTALL-01 | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `scenario-record denominator` | `TBD` |
| EB-PAIR-01 | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `scenario-record denominator` | `TBD` |
| EB-PAIR-02 | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `scenario-record denominator` | `TBD` |
| EB-RECOVER-01 | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `scenario-record denominator` | `TBD` |
| EB-NOTIFY-01 | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `scenario-record denominator` | `TBD` |
| EB-PRIVACY-01 | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `scenario-record denominator` | `TBD` |
| EB-TEXT-01 | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `scenario-record denominator` | `TBD` |
| EB-FILE-01 | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `scenario-record denominator` | `TBD` |
| EB-FILE-02 | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `scenario-record denominator` | `TBD` |
| EB-DIAG-01 | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `scenario-record denominator` | `TBD` |
| EB-DIAG-02 | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `scenario-record denominator` | `TBD` |
| EB-TRUST-01 | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `scenario-record denominator` | `TBD` |
| EB-A11Y-01 | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `TBD` | `scenario-record denominator` | `TBD` |

For every scenario row, `row total = pass + fail + blocked + stopped + not-run
= scenario-record denominator`, and `final-candidate pass <= all-set pass`.
Any mismatch or explicit N/A denominator blocks `GO`. Accounting-only and
missing-record attempts stay in Attempt Reconciliation, outside these rows;
they cannot be used as passes or silently omitted from attempted-run totals.

`Blocked`, `stopped`, and `not-run` remain visible. They cannot be rewritten as
passes or discarded because a later attempt succeeded.

Each stopped session contributes exactly one terminating `stopped` journey and
all later required journeys are `not-run`. Completed, incomplete, and
preflight-blocked attempts contribute no stopped journey; preflight-blocked
attempts contribute `not-run` for every scenario. The retained all-set stopped
column plus anonymously attested accounting-only stopped dispositions must
equal the stopped-session count above.

For `GO`, every core scenario from EB-INSTALL-01 through EB-TRUST-01 needs at
least one pass on the single final candidate artifact pair.
EB-A11Y-01 needs a final-candidate named-setup pass or an explicit accepted
coverage cut and risk; neither closes the separate runtime
accessibility/localization gates.

## Safety, Failures, And Evidence Handling

- [ ] No unresolved stop-condition, P0 trust/privacy/data-loss issue, or private
      security report remains open.
- [ ] Every failure has a stable category, severity, owner, and disposition.
- [ ] The corpus is not called the v1 top 10 unless it actually supports ten
      ranked categories.
- [ ] Repository-facing evidence passed redaction review and contains no
      participant identity, payload, private pointer, or unique identifier.
- [ ] Raw evidence has an owner and deletion deadline; withdrawn or expired
      evidence was deleted without erasing aggregate attempt counts.
- [ ] Evidence-use withdrawals were applied and the GO-eligible people,
      coverage, journey passes, and decision were recomputed; contributions
      remain eligible only under explicit irreversibly-anonymized aggregate
      consent.
- [ ] The exact artifact set has a tested recall path.

## Decision Record

| Question | Answer |
|---|---|
| Private identity-map attestation confirms 3-5 distinct people after deduplication/withdrawal? | `yes / no` |
| All 3-5 eligible people completed a final-candidate checksum-bound session through closeout? | `yes / no` |
| Exactly one final artifact set supplies all GO people/coverage/passes? | `yes / no` |
| Attempt arithmetic reconciles with zero unreconciled/missing evidence? | `yes / no` |
| Scenario-record/accounting-only/missing partition reconciles to attempts? | `yes / no` |
| Every exact scenario row conserves and final passes are a valid subset? | `yes / no` |
| Stopped sessions and retained/accounting-only terminators reconcile exactly? | `yes / no` |
| Every core journey has a pass; accessibility pass/cut is explicit? | `yes / no` |
| Safety/security stop conditions resolved? | `yes / no` |
| Coverage gaps and accepted cuts explicit? | `yes / no` |
| Retention/deletion and recall controls complete? | `yes / no` |
| Decision and unresolved risks | `GO / HOLD / STOP — TBD` |

`GO` permits planning or invitations for Stage B only. It does not establish
compatibility, a 99% crash-free claim, a top-10 failure ranking, signing,
notarization, store readiness, RC readiness, public beta, or release readiness.
