# ADR-0006: Protocol v0 Minor-Version Compatibility

## Status

Accepted

## Context

The current implementation emits `hyphen/0.3`, while the shared envelope schema
and both platform decoders accept `hyphen/0.x`. A review confirmed that this is
a real behavior difference from wording that described only `hyphen/0.3`.

## Decision

Keep the v0 receiver gate as `hyphen/0.x`. The `0.x` identifier family is a
same-major compatibility family for Project Hyphen pre-1.0 protocol work.
Receivers reject other majors, but they do not reject future v0 minor
identifiers at envelope decode time.

Incompatible wire behavior must not be introduced as a bare `hyphen/0.x`
minor. It must use one of these paths:

- a new negotiated capability or capability version,
- strict payload validation under an existing capability when backward
  compatible,
- or a new protocol major if the envelope/session contract changes.

SAS transcripts continue to bind the exact emitted protocol identifier, so a
pairing transcript using `hyphen/0.4` remains distinguishable from one using
`hyphen/0.3`.

## Consequences

- The schema and runtime regex remain unchanged.
- The protocol document must describe `hyphen/0.3` as the current emitted
  identifier, not the only accepted v0 identifier.
- Compatibility review is required before introducing a new v0 minor.
