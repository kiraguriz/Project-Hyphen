# ADR-0008: Android TLS Floor and Minimum SDK

## Status

Accepted

## Context

Protocol v0 transport is mutual **TLS 1.3 only** on both platforms, and the
Android `HyphenTls` layer fails loudly rather than downgrading
(`SSLContext.getInstance("TLSv1.3")`). Android added platform TLS 1.3 support in
**API 29 (Android 10)**; on API 26–28 that call throws, so the app would install
on Android 8.0/8.1/9 but every pairing/transport attempt would fail.

The Gradle config carried `minSdk = 26` (originally the CompanionDeviceManager
floor, ADR-0003), which contradicted the TLS 1.3 requirement. A security
architecture review (dim 06-02) flagged the install-then-fail gap and required a
recorded decision. A stale code comment in `TlsEndpoint.kt` referenced a
non-existent "ADR-0002" as the placeholder for this decision (dim 06-10).

Two routes were considered:

- **Route A — raise `minSdk` to 29.** Drop API 26–28; keep a clean TLS 1.3-only
  posture with no downgrade surface and no new dependency.
- **Route B — add a TLS 1.2 fallback / bundled TLS provider** for API 26–28.
  Keeps older devices but widens the security surface (downgrade protection,
  cipher/profile policy) and likely adds a dependency requiring license review.

## Decision

Adopt **Route A**: set `minSdk = 29`.

Project Hyphen v1 is a local-first companion for macOS power users; the
incremental reach of Android 8.0–9 does not justify weakening the transport
security posture or carrying a TLS-provider dependency. TLS 1.3-only stays the
floor; the transport layer continues to fail loudly and never downgrades.

Changing this floor again — lowering `minSdk`, adding a TLS 1.2 path, or bundling
a TLS provider — requires a superseding ADR, because it changes the
security/dependency tradeoff.

## Consequences

- `apps/android/app/build.gradle.kts` sets `minSdk = 29`; API 26–28 devices can
  no longer install the app (Play/F-Droid enforce the floor at install time).
- ADR-0003's `minSdk 26` statement is superseded by this ADR; the CDM
  self-managed association range narrows from "API 26–32" to "API 29–32".
- The `TlsEndpoint.kt` comment now references this ADR instead of the dangling
  ADR-0002 placeholder.
- The protocol doc's TLS-floor note records that the floor is now enforced by
  `minSdk`, not just documented.
- API 29+ pairing/transport remains the only supported path; the compatibility
  matrix should target API 29+ devices.
