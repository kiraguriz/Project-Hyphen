# Hyphen Threat Model v0

- **Status**: Living document — re-review scheduled before Private Beta (M4) and finally at HYP-M6-007.
- **Tracker**: HYP-M0-008 · **Sources**: plan v0.3 §11, `hyphen-protocol-v0.md`, ADR-0001
- Mechanism references below point at `hyphen-protocol-v0.md` sections.

## 1. Assets

| Asset | Why it matters |
|---|---|
| Notification content (titles, bodies, sender names) | Most sensitive routine data: 2FA codes, messages, health/finance alerts |
| Transferred files and text/links | Arbitrary user data |
| Identity private keys + trust stores (Keychain / Android encrypted store) | Whoever holds these *is* the device, protocol-wise |
| Pairing integrity (fingerprint ↔ device binding) | A poisoned pairing silently routes everything to the attacker |
| Device/network metadata (names, IPs, presence patterns) | Lower sensitivity, but leaks routine/location-on-LAN information |

## 2. Adversary model

| ID | Adversary | Capabilities |
|---|---|---|
| A1 | Passive LAN observer | Sniffs Wi‑Fi/Ethernet traffic, records flows |
| A2 | Active LAN attacker | Spoofs mDNS/ARP/DHCP, runs rogue services, attempts MITM, floods listeners |
| A3 | Malicious pairing peer | A device on the LAN that tries to get itself paired, or poses as the user's Mac/phone during pairing |
| A4 | Holder of a lost/stolen *paired* device | Has a device with valid keys and trust entries |
| A5 | Person with physical access to an unlocked Mac or phone | Can read mirrored notifications, send replies, initiate transfers |
| A6 | Diagnostics recipient | Receives an exported bundle (maintainer, forum post) |

**Out of scope for v0**: attackers with root/jailbreak on the user's own devices; OS supply-chain compromise; coercion of the user; traffic-analysis-resistant anonymity. Dependency supply chain is handled as process (license/dependency audit, HYP-M6-008), not runtime defense.

## 3. Threats and mitigations

### 3.1 Discovery and pairing (LAN spoofing, MITM)

| Threat | Adversary | Mitigation | Status |
|---|---|---|---|
| mDNS/Bonjour spoofing: fake `_hyphen._tcp` service lures the phone | A2 | **Discovery is not trust** (§1): discovery results only feed an endpoint candidate list; TLS SPKI pin (§2) must match before any payload flows | Designed; negative tests in HYP-M2-007/008 |
| MITM during QR pairing | A2/A3 | Fingerprint travels inside the QR (out-of-band channel); connection to a key not matching `fp` aborts with `trust/fingerprint-mismatch` | Designed |
| MITM during manual-IP pairing (no QR fingerprint) | A2/A3 | Mandatory, non-skippable SAS comparison on both devices (§5.3); transcript binds nonce + both SPKI fps + version, so a relay attacker cannot present matching codes | Designed; SAS vectors in HYP-M2-004 |
| Attacker tricks user into pairing with attacker's device (social engineering, swapped QR) | A3 | Both-sides SAS confirm + device names shown at confirm time; pairing requires explicit user action on both devices; residual risk documented in onboarding copy | Partial — residual |
| Protocol downgrade during pairing | A2 | `protocolVersion` is part of the SAS transcript; mismatch changes the SAS | Designed |
| MITM after pairing (key substitution) | A2 | mTLS with SPKI pinning both directions; changed key ⇒ hard failure + prominent re-verify UX, never silent re-pin | Designed |
| Replay of captured frames / stolen resume token | A1/A2 | TLS 1.3 anti-replay; resume tokens single-use, 10-min expiry, pin-check-first (§4.6) | Designed |
| Evil-twin AP / hostile network operator | A2 | All payload inside pinned TLS; attacker still sees flow metadata (sizes, timing) | Accepted residual |

### 3.2 Notification privacy

| Threat | Adversary | Mitigation | Status |
|---|---|---|---|
| Notification bodies sniffed in transit | A1 | TLS 1.3 only; no plaintext mode exists | Designed |
| Mirrored notifications visible on shared/unlocked Mac | A5 | Privacy modes: hide body per-app, "a notification exists" mode; macOS lock-screen preview settings documented in onboarding | Designed (HYP-M3-005) |
| Anyone at the Mac can reply/dismiss on the phone | A5 | Documented explicitly: a paired Mac session ≈ phone notification access. Mitigations: per-feature capability toggle, per-app filters, instant peer revoke on the phone | Accepted + controls |
| Notification history accumulates on Mac | A5 | **No persistent notification-history database**; mirror state is in-memory + OS notification center only | Frozen default (plan §7.7) |
| Error/log strings leak notification content | A6 | Protocol rule: error `message` MUST NOT contain bodies/paths/addresses (§8); log redaction by default (HYP-M4-001) | Designed |

### 3.3 Trust lifecycle

| Threat | Adversary | Mitigation | Status |
|---|---|---|---|
| Lost/stolen phone keeps receiving Mac-side data | A4 | Peer revoke on the Mac removes pin + invalidates resume tokens; revocation works offline (local trust store) | Designed (HYP-M4-010) |
| Lost/stolen Mac can read phone notifications | A4 | Peer revoke on Android; FGS connection notification keeps the link user-visible on the phone | Designed |
| Trust store tampering by other local software | A4/A5 | macOS Keychain ACLs; Android EncryptedSharedPreferences/Keystore-backed storage | Platform-delegated |

### 3.4 Diagnostics and data-at-rest

| Threat | Adversary | Mitigation | Status |
|---|---|---|---|
| Diagnostics bundle leaks bodies, filenames, IPs | A6 | Redaction **by default**: notification text, file names, IP suffixes stripped; user previews bundle before export; export is manual, never automatic | Designed (HYP-M4-002/003) |
| Trace/span IDs exfiltrated silently | A6 | `trace.localOnly: true` default; transmission only under explicit opt-in (HYP-M2-014) | Designed |
| "Telemetry creep" over releases | — | No-telemetry-by-default is frozen in ADR-0001; any change requires a superseding ADR + privacy-policy update | Governance |

### 3.5 Availability

| Threat | Adversary | Mitigation | Status |
|---|---|---|---|
| Frame floods / oversized frames against listeners | A2 | 4 MiB frame cap with `transport/frame-too-large`; unauthenticated connections dropped after TLS pin failure; per-source connection rate limiting is an M2 open question | Partial — open question §9 |
| Notification storms amplify into Mac UI flood | — | Same-key updates coalesce (no duplicates by design); storm test HYP-M4-008 | Designed |

DoS by a determined on-LAN attacker is **accepted** for v0: Hyphen treats availability on a hostile LAN as best-effort; integrity and confidentiality are never traded for availability.

## 4. Derived security requirements (test hooks)

1. TLS pinning negative tests: wrong cert, swapped peer certs, renewed-cert-same-key (must pass), new-key (must fail) — HYP-M2-007/008.
2. SAS/transcript deterministic vectors, including downgrade-attempt vectors — HYP-M2-004.
3. QR parser fuzz/negative cases: unknown scheme, missing fields, oversized values — HYP-M2-010.
4. Redaction unit tests: synthetic bundle contains no notification bodies/file names/IPs — HYP-M4-002/003.
5. Resume-token reuse and expiry tests — HYP-M2-013.
6. Frame-cap enforcement test — HYP-M2-007/008.

## 5. Residual risks (accepted, must stay documented)

- Physical access to an unlocked paired device equals access to mirrored content and actions (A5).
- Traffic metadata (timing, sizes, presence) is visible to LAN observers (A1).
- Social-engineered pairing remains possible if a user confirms a SAS for a device they don't control; UX copy is the only defense (A3).
- Hostile-LAN denial of service degrades availability (A2).
