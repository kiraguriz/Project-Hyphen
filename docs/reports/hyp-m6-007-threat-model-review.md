# HYP-M6-007 Threat-Model Review Notes

- **Date**: 2026-06-12
- **Scope reviewed**: `docs/protocol/threat-model.md`, frozen protocol v0 docs,
  ADR-0001 scope, ADR-0003 Android permissions, public beta checklist, current
  diagnostics/export behavior, and release blockers.
- **Outcome**: review complete for the local repo state. No protocol or
  permission expansion is needed. Public beta remains blocked by release and
  device-evidence gates.

## Confirmed Controls

- Discovery remains non-trust: only pinned SPKI fingerprints plus SAS
  confirmation establish trust.
- v0 keeps JSON framing with a 4 MiB cap and strict envelope/schema validation.
- `hello` handshake behavior is now documented as implemented:
  `requiresAck: false`, responder `hello` as the handshake acknowledgment, and
  per-connection sequence numbers.
- Notification identity remains `StatusBarNotification.getKey()` only.
- Redacted diagnostics omit trace IDs by default; trace IDs require explicit
  beta diagnostics opt-in.
- v1 non-goals remain frozen: no cloud relay, NAT relay, SMS, Call Log,
  Accessibility, background clipboard listening, screen mirroring, or remote
  control.

## New Or Updated Risks Captured

| Risk | Status after review |
|---|---|
| Local dry-run macOS/Android artifacts could be mistaken for public beta release artifacts | Added to threat model under distribution/release integrity; public checklist has stop conditions for notarization, signing, checksums, and placeholder metadata |
| Trace IDs in beta diagnostics can correlate sessions/events even without payload contents | Added as residual diagnostics risk; default-off opt-in and preview/export remain required controls |
| Physical access to an unlocked paired Mac now covers text/link/file actions, not only notification reply/dismiss | Clarified as accepted A5 residual risk |
| Per-source listener rate limiting is still not implemented | Kept as accepted hostile-LAN availability residual and post-v0 question; frame cap and pin failure handling remain current controls |
| Device/beta matrix gaps are not proof of security | Captured in public beta checklist and tracker blockers; release notes must disclose unverified matrices |

## No-Go Conditions For Release

Treat these as security no-go, not mere paperwork:

- Public macOS package is ad-hoc signed, unsigned, or not notarized.
- Android release APK/AAB is unsigned or signed with an unapproved test key.
- F-Droid metadata still points at placeholder URLs or draft commits.
- Play/F-Droid policy statements no longer match the manifest or runtime
  behavior.
- Release notes omit blocked compatibility, wake/reconnect, transfer-resume, or
  crash-free-session evidence.
- Any artifact, fixture, diagnostics bundle, or release note contains secrets or
  user payload data.

## Residual Risks

The following are accepted for v0 and must stay visible:

- Physical access to an unlocked paired device enables mirrored content and
  cross-device actions.
- LAN observers can still see timing, sizes, and presence metadata.
- A hostile LAN can deny service; Hyphen prioritizes confidentiality and
  integrity over availability in that environment.
- Beta diagnostics trace IDs, when explicitly included, can aid correlation
  across a user-submitted bundle.
- Public beta cannot be considered trustworthy until signing/notarization and
  channel evidence are present.

## Verification

- Manual review of the documents listed above.
- `./scripts/check.sh` after updating review notes and the threat model.
