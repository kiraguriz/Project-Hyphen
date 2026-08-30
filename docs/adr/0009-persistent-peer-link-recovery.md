# ADR-0009: Persistent Peer-Link Recovery

## Status

Accepted

## Context

ADR-0001 requires paired-device continuity across app/process restarts, but the
product connection owners retained their routing state only in process memory.
Android remembered the Mac host, port, and pinned fingerprint inside
`ConnectionSupervisor`; macOS remembered its trusted-listener port and Android
fingerprint inside `PairingController`. A process restart therefore preserved
trust but lost the route needed to reconnect.

Persisting an entire pairing or session record would create unnecessary replay
and lifetime risk. Pairing nonces are one-attempt challenges, while session IDs
and resume tokens belong to live/recent transport state. None is required to
find a previously trusted LAN peer after cold start.

## Decision

Persist one **minimal current peer-link descriptor**, separate from the trust
record and from transfer/session checkpoints:

- Android stores the Mac host, port, and pinned SPKI fingerprint in an
  AES-256-GCM sealed app-private file. It uses the existing non-exportable
  Android Keystore key and remains excluded from backup by the app's
  `allowBackup=false` policy.
- macOS stores the listener port and Android SPKI fingerprint as one generic
  password record in the login Keychain with synchronization explicitly
  disabled. The Android display name continues to come from the trust record.
- Pairing nonces, session IDs, resume tokens, transfer metadata, notification
  content, and activity history are never part of this descriptor.

Cold restore is fail closed. Before either product owner adopts a descriptor,
the lifecycle seam looks up its exact fingerprint in the current trust store.
A missing/revoked fingerprint clears the stale descriptor and starts no network
work; corruption or storage failure also starts no network work and records
only a bounded, redacted diagnostic code. A successful restore begins a fresh
pinned TLS/hello handshake with no previous session ID or resume token.

Trust revocation/reset first removes the authoritative trust record, stops the
live owner, clears session/transfer state through their existing owners, and
invalidates the peer-link descriptor. If descriptor deletion itself fails, the
trust lookup remains the security gate, so the stale route cannot reconnect.
Switching the single current peer is also fail closed at the controller seam:
both product owners retire the previous listener/session, serialize a
clear-before-save route replacement, and do not publish the new peer in memory
until that replacement attempt completes. Once the durable clear succeeds, a
crash or failed new save leaves no current route rather than silently restoring
the superseded historically trusted peer. A storage clear failure is reported
and remains bounded by the authoritative trust lookup; it is not claimed as a
successful durable replacement.
On both platforms, restore, owner/listener installation, accepted connections,
and session activation carry one lock-protected ownership generation;
Android reconnect callbacks additionally carry a unique owner token.
Revocation increments the generation before transport teardown, rejects every
late callback, and serializes descriptor writes/deletion so in-flight storage
work cannot recreate a revoked route. Android's reconnect owner offers the
authenticated session to the supervisor before session I/O starts, allowing
the supervisor to reject and close stale ownership without publishing it.
The macOS owner also assigns an unforgeable ownership ticket to each steady
session. Envelope, acknowledgement, timeout, progress, and close callbacks must
still hold that ticket and generation when they mutate product state. A
handshake may replace the ticket only after its generation is accepted, so a
stale completion cannot disable callbacks for a newer live session. Taking
ownership for a newly paired peer retires the previous peer's listener and live
session. Accepted handshakes are tracked until completion and cancelled on
re-pair/revoke; failed handshakes may reinstall a listener only for their own
still-current generation. Resume-token issuance captures a per-peer/reset epoch
before the handshake starts, so an invalidation that wins the race prevents a
later token insertion. A failed current-generation trusted redial handshake
closes only that connection and reinstalls the remembered listener for
Android's next bounded redial attempt.

The macOS app keeps Local Network onboarding in front of the restored listener.
This decision does not alter protocol v0 or add a dependency.

## Consequences

- Activity/window recreation keeps using the process owner, while a true
  process restart can now recover the last trusted LAN route.
- Storage implementations and product owners share explicit `PeerLinkStore`
  and `PeerLinkLifecycle` seams, with real-store and product-composition tests.
- Host tests exercise controller/store cold-start seams and deterministic
  restore/revoke/callback races; they do not prove an OS-killed app relaunch or
  a live two-device reconnect.
- Only one current peer link is recovered in v0, even if the trust store
  contains more than one historical peer.
- A reused macOS port that is occupied after restart, or an Android route made
  stale by DHCP/network change, still requires user-visible recovery (re-pair
  or future Bonjour-assisted route refresh). Automated host tests do not prove
  those device/network cases.
- Android continuous background ownership remains a separate foreground-service
  decision and implementation; this ADR supplies the persistent descriptor that
  such an owner may consume.
