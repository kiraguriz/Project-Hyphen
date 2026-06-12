# Hyphen Protocol v0 (draft)

- **Status**: Draft — normative for M2 implementation; expect revisions until Gate B. Changes that alter wire behavior require an ADR and a version note here.
- **Protocol identifier**: `hyphen/0.3` (the `protocol` field below)
- **Tracker**: HYP-M0-007 · **Sources**: plan v0.3 §6.1, §7.7, §9; ADR-0001
- **Security analysis**: `threat-model.md` (HYP-M0-008)

The words **MUST**, **SHOULD**, and **MAY** are used as in RFC 2119.

## 1. Design invariants

1. Small, stable core: pairing, session, capability, envelope, ack, error, diagnostics. Features (notifications, text, transfer) are plugins behind capabilities.
2. **Discovery is not trust.** Bonjour/NSD results are hints only. Trust is established exclusively by fingerprint pinning plus user SAS confirmation, and persists only in the local trust stores.
3. Local-first: peers connect directly over the LAN. There is no cloud relay, rendezvous server, or NAT traversal in v0.
4. Auditability over compactness: frames are UTF-8 JSON. Binary encodings (CBOR/Protobuf) are out of scope for v0 (P2 backlog).

## 2. Transport binding

- TCP over the local network, wrapped in **TLS 1.3**.
- Each device generates a long-lived self-signed identity certificate. Peers authenticate by **SPKI pinning**: the SHA-256 digest of the peer certificate's DER-encoded SubjectPublicKeyInfo MUST match the pinned fingerprint in the local trust store. Certificate renewal that keeps the key pair does not break the pin; a changed key MUST trigger explicit re-pairing (re-confirmation by the user).
- Both directions use mutual TLS: each side presents its identity certificate and verifies the peer against the trust store. During first pairing only, the connection is provisional until SAS confirmation completes (§5).

### 2.1 Framing

Each protocol message is one frame:

```text
frame := length(4 bytes, big-endian uint32) || payload(UTF-8 JSON, `length` bytes)
```

- `length` counts payload bytes only. Receivers MUST reject frames larger than **4 MiB** with `transport/frame-too-large` and MAY close the connection.
- One frame contains exactly one JSON envelope. No batching.
- File chunk data travels base64-encoded inside envelopes in v0 (simple, auditable; ~33% overhead accepted). A binary side-channel is a v1+ option, not v0.

## 3. Envelope

```json
{
  "protocol": "hyphen/0.3",
  "messageId": "01JZX2J9Q4R8Z6K1T0B7E5M3NC",
  "sessionId": "s_7f3a…",
  "type": "notification.updated",
  "capability": "notifications.v1",
  "seq": 42,
  "ackOf": null,
  "sentAtUnixMs": 1781020800000,
  "requiresAck": true,
  "payload": {},
  "trace": { "localOnly": true, "spanId": "…" }
}
```

| Field | Type | Req | Semantics |
|---|---|---|---|
| `protocol` | string | yes | Protocol id + version. Receivers MUST reply `protocol/version-unsupported` and close if they cannot speak it. |
| `messageId` | string (ULID) | yes | Unique per message; used for dedupe and ack correlation. |
| `sessionId` | string | yes after hello | Identifies the logical session (§4). `null` only in `hello`. |
| `type` | string | yes | Message type, namespaced `feature.event` (§7). Unknown types → `protocol/unknown-type` error reply; connection stays open. |
| `capability` | string | for plugin msgs | Capability that governs this message, e.g. `notifications.v1`. Core types (`hello`, `ack`, `error`, `heartbeat`) omit it. |
| `seq` | integer | yes | Per-sender, per-session, monotonically increasing from 1. Receivers MAY use gaps for loss diagnostics; ordering authority stays with TCP. |
| `ackOf` | string\|null | no | `messageId` being acknowledged (only in `ack`). |
| `sentAtUnixMs` | integer | yes | Sender wall clock; diagnostics only, never identity (see notification key rule). |
| `requiresAck` | boolean | yes | If true, receiver MUST send `ack` within 10 s or sender treats it as `protocol/ack-timeout`. |
| `payload` | object | yes | Type-specific body (may be `{}`). |
| `trace` | object | no | `localOnly: true` by default. Trace/span ids MUST NOT leave the device unless the user opted into diagnostics sharing (HYP-M2-014). |

## 4. Session lifecycle

1. **TLS established** → initiator sends `hello` with device info and offered capabilities (§6).
2. Responder answers `hello` with its own info and the **intersected/limited** capability set, plus a fresh `sessionId`.
3. Steady state: any plugin messages permitted by negotiated capabilities; `heartbeat` every **10 s** each way (`requiresAck: false`).
4. **Degraded**: two consecutive missed heartbeats (>20 s silence). The UI may show "reconnecting"; senders SHOULD queue idempotent messages.
5. **Reconnect**: backoff at 1 s / 5 s / 15 s / 30 s (then every 30 s). A reconnect is a new TLS connection followed by `hello` carrying `resumeToken` from the previous session. The responder MAY resume (same `sessionId`, transfer checkpoints valid) or reject (fresh session; transfers restart from last durable checkpoint).
6. Resume tokens are single-peer, single-use, expire after 10 minutes, and MUST be invalidated on trust revocation. They are session continuity hints, never authentication: the TLS pin check always runs first.

## 5. Pairing

### 5.1 QR payload

The Mac displays (and the Android app scans):

```text
hyphen://pair?v=0&ep=192.168.1.20:48273&fp=<base64url(SHA-256(SPKI))>&n=<base64url(16-byte nonce)>&dn=<urlencoded device name (optional)>
```

Parsers MUST reject payloads with unknown scheme, missing `v`/`ep`/`fp`/`n`, malformed fields, or `v` they cannot speak — silently safe, no crash (HYP-M2-010). Manual-IP pairing is the same flow with `fp` absent: trust then rests entirely on SAS confirmation.

### 5.2 Sequence

```text
Mac:      generate identity key/cert, pairing nonce → show QR
Android:  scan QR → TLS connect to ep, verify presented cert against fp (when present)
Android → Mac: pair.request  { nonce, androidSpkiFp, deviceInfo }
Mac → Android: pair.challenge { transcriptHash }
Android → Mac: pair.response  { transcriptHash }     # both computed independently
both:     display SAS; user confirms match on BOTH devices
Mac → Android: pair.confirm   { accepted: true }
both:     persist peer fingerprint in trust store (Keychain / encrypted store)
```

### 5.3 Transcript and SAS

```text
transcript    = "hyphen-pair-v0" || nonce || macSpkiFp || androidSpkiFp || protocolVersion
transcriptHash = SHA-256(transcript)         # fixed field order, raw bytes
SAS            = uint64_be(transcriptHash[0..7]) mod 10^6, zero-padded to 6 digits
```

- Both devices MUST compute the SAS independently and display it; trust is stored only after the user confirms on **both** sides (HYP-M2-011).
- With QR, the fingerprint is pre-shared and SAS is defense-in-depth; with manual IP, SAS is the primary MITM defense and MUST NOT be skippable.
- Mismatched `transcriptHash` values or a rejected SAS MUST abort pairing with `trust/sas-rejected` and persist nothing.
- Encodings are exact: the label is ASCII, `nonce` is 16 raw bytes, each SPKI fingerprint is 32 raw bytes, and `protocolVersion` is the UTF-8 protocol identifier string (e.g. `hyphen/0.3`). All fields are fixed-length except the version, which is last — the concatenation is injective without length prefixes.
- SAS rendering MUST zero-pad to exactly 6 digits (leading-zero cases are pinned by vectors).
- Normative deterministic vectors: `protocol/test-vectors/pairing/sas-vectors.json` (HYP-M2-004), verified by `scripts/verify_pairing_vectors.py`; both platform implementations MUST reproduce every case.

## 6. Capability negotiation

`hello` payload:

```json
{
  "device": { "kind": "android", "appVersion": "0.1.0", "osVersion": "Android 16", "deviceName": "Pixel" },
  "resumeToken": null,
  "capabilities": {
    "notifications.v1": { "reply": "beta", "dismiss": true },
    "transfer.v1": { "resume": true, "maxChunkBytes": 1048576 },
    "text.v1": { "direction": "bidirectional" },
    "diagnostics.v1": { "redactedExport": true }
  }
}
```

- Capability names are `feature.v<major>`. Adding optional fields is non-breaking; semantic changes bump the major.
- The responder replies with the subset (possibly with reduced options) it accepts; that intersection is the session contract. Messages outside it → `plugin/unsupported-capability`.
- Unknown capabilities MUST be ignored, not rejected (forward compatibility).
- Effective transfer chunk size = min of both sides' `maxChunkBytes`. The capability schema caps `maxChunkBytes` at 2 MiB (and floors it at 1 KiB) so a base64-inflated chunk plus envelope overhead always fits the 4 MiB frame limit (§2.1).

## 7. Message catalog (v0)

| Type | Dir | Ack | Purpose |
|---|---|---|---|
| `hello` | both | yes | Session open / resume, capability negotiation |
| `heartbeat` | both | no | Liveness (§4) |
| `ack` | both | no | Acknowledges `messageId` in `ackOf` |
| `error` | both | no | Carries an error code (§8), optionally `regarding: messageId` |
| `notification.posted` | A→M | yes | New notification; key = `StatusBarNotification.getKey()` — `postTime` MUST NOT be part of identity |
| `notification.updated` | A→M | yes | Same key reposted; Mac updates in place (no duplicate) |
| `notification.removed` | A→M | yes | Key removed on Android; Mac closes its mapped notification |
| `notification.dismiss.request` / `.result` | M→A / A→M | yes | Mac asks Android to cancel a key; result carries success or error code |
| `notification.reply.request` / `.result` | M→A / A→M | yes | RemoteInput reply, only where capability advertises it (beta) |
| `text.send` | both | yes | Text/link with `kind: "text"\|"url"`; receiver confirms before open (J4) |
| `transfer.manifest` | both | yes | `fileId`, name, size, mime, sha256, chunk size/count (HYP-M3-010) |
| `transfer.chunk` | both | yes | `fileId`, `chunkIndex`, base64 data, `chunkSha256` |
| `transfer.resume.request` / `.info` | both | yes | Receiver reports highest contiguous verified chunk per `fileId` |
| `transfer.complete` | both | yes | Whole-file SHA-256 verification result |
| `transfer.cancel` | both | yes | Either side aborts; partial data kept for resume unless `discard: true` |

Detailed payload schemas are normative in `protocol/schema/` (JSON Schema, HYP-M2-001/002/HYP-M3-010); this table is the index.

### 7.1 `text.send` payload

`text.send` is the v0 text/link plugin message (HYP-M3-008/009). It is always sent under capability `text.v1`, requires an ack, and the receiver MUST present the content for explicit user confirmation before copying or opening it.

```json
{
  "kind": "text",
  "value": "hello from Android"
}
```

| Field | Type | Required | Rule |
|---|---|---:|---|
| `kind` | string | yes | `text` or `url` |
| `value` | string | yes | Non-empty after trimming; max 8192 Unicode scalar values |

URL values are limited to `http` and `https` in v0. Other schemes are rejected rather than opened or copied implicitly.

### 7.2 `transfer.manifest` payload

`transfer.manifest` is sent before any `transfer.chunk` payload. It is always sent under capability `transfer.v1`, requires an ack, and lets the receiver allocate local state, show the user a receive prompt, and verify the completed file.

The normative schema is `protocol/schema/transfer-manifest.schema.json`.

```json
{
  "fileId": "f_01JZ0000000000000000000000",
  "filename": "notes.txt",
  "sizeBytes": 1234,
  "mimeType": "text/plain",
  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "chunkSizeBytes": 1024,
  "chunkCount": 2
}
```

| Field | Type | Required | Rule |
|---|---|---:|---|
| `fileId` | string | yes | Sender-generated opaque id matching `^f_[A-Za-z0-9_-]{8,128}$`; stable across resume attempts |
| `filename` | string | yes | Display filename only, max 255 chars, no `/` or `\`; receivers choose the destination path |
| `sizeBytes` | integer | yes | Whole-file size in bytes; minimum 0 |
| `mimeType` | string | yes | Lowercase normalized media type; use `application/octet-stream` if unknown |
| `sha256` | string | yes | Whole-file SHA-256, lowercase hex |
| `chunkSizeBytes` | integer | yes | Effective chunk size after capability negotiation; 1024..2097152 |
| `chunkCount` | integer | yes | Number of `transfer.chunk` payloads expected; empty files use 0 |

Schema validation intentionally does not encode cross-field arithmetic such as `chunkCount == ceil(sizeBytes / chunkSizeBytes)`. Sender and receiver implementations MUST enforce that relationship when HYP-M3-011 creates chunk state.

## 8. Error taxonomy

Error payload: `{ "code": "category/short-code", "message": "human readable, no sensitive content", "regarding": "<messageId|null>", "retryable": true|false }`

| Category | Codes (initial set) |
|---|---|
| `protocol/` | `version-unsupported`, `invalid-envelope`, `unknown-type`, `ack-timeout` |
| `transport/` | `tls-failure`, `heartbeat-timeout`, `frame-too-large`, `malformed-frame`, `connection-lost` |
| `trust/` | `unknown-peer`, `fingerprint-mismatch`, `sas-rejected`, `peer-revoked`, `resume-token-invalid` |
| `permission/` | `local-network-denied`, `notifications-denied`, `storage-denied`, `background-restricted` |
| `plugin/` | `unsupported-capability`, `notification-key-not-found`, `reply-unavailable`, `checksum-mismatch`, `transfer-cancelled`, `disk-full` |

Rules: error `message` strings MUST NOT contain notification bodies, file contents, full paths, or addresses beyond what the user already sees. `fingerprint-mismatch` MUST tear down the connection and surface a prominent re-verify prompt — it is the MITM signal.

The normative registry is the `code` enum in `protocol/schema/error.schema.json` (linted by `scripts/lint_error_registry.py`); this table is the human-readable index. Messages are capped at 256 characters by schema.

## 9. Open questions (to resolve in M2 with ADRs)

1. ~~Exact `resumeToken` construction~~ — **Resolved (HYP-M2-013)**: a random 32-byte handle (base64url, unpadded), stored responder-side in memory only, bound to one session and one peer SPKI fingerprint, single-use (consumed even on a failed redeem), 10-minute expiry, invalidated on trust revocation. Tokens do not survive an app restart; the worst case is a fresh session.
2. Whether `hello` should carry a protocol feature bitmap separate from capabilities for faster version gating.
3. Heartbeat interval adaptivity on battery saver (Android FGS constraints may force ≥15 s).
4. Max in-flight unacked messages (flow control) — v0 implementations SHOULD cap at 64.
5. TLS 1.3 floor vs. Android API 26–28, which lack platform TLS 1.3 (it arrived in API 29). Current implementations (HYP-M2-008) fail loudly on those devices rather than downgrade; options for ADR-0002 are raising minSdk to 29 or permitting TLS 1.2 + pinning on legacy API levels. Bundling a TLS library is dispreferred (dependency policy).
