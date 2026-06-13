# Hyphen Protocol v0

- **Status**: Frozen for the current pre-alpha v0 wire behavior (HYP-M6-006). Changes that alter wire behavior require an ADR and a version note here.
- **Protocol identifier emitted by this build**: `hyphen/0.3` (the `protocol` field below)
- **v0 minor-version policy**: v0 receivers intentionally accept `hyphen/0.x`
  identifiers. Incompatible behavior must use a new major or a new negotiated
  capability, not an incompatible same-major minor. See ADR-0006.
- **Tracker**: HYP-M0-007, HYP-M2-015, HYP-M6-006 · **Sources**: plan v0.3 §6.1, §7.7, §9; ADR-0001
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
| `type` | string | yes | Message type, namespaced `feature.event` (§7). The session layer handles only core `ack`/`heartbeat`; other types are delivered to the negotiated feature/plugin layer, which is responsible for `protocol/unknown-type` or `plugin/unsupported-capability` errors. |
| `capability` | string | for plugin msgs | Capability that governs this message, e.g. `notifications.v1`. Core types (`hello`, `ack`, `error`, `heartbeat`) omit it. |
| `seq` | integer | yes | Per-sender, per-connection, monotonically increasing from 1. The `hello` frame uses `seq: 1`; the session continues at 2 on that connection. Reconnect starts a new connection and resets the sequence. Receivers MAY use gaps for loss diagnostics; ordering authority stays with TCP. |
| `ackOf` | string\|null | no | `messageId` being acknowledged (only in `ack`). |
| `sentAtUnixMs` | integer | yes | Sender wall clock; diagnostics only, never identity (see notification key rule). |
| `requiresAck` | boolean | yes | If true, receiver MUST send `ack` within 10 s or sender treats it as `protocol/ack-timeout`. |
| `payload` | object | yes | Type-specific body (may be `{}`). |
| `trace` | object | no | Local trace context. If present, `localOnly` MUST be `true` and `spanId`, when present, MUST be a ULID. Trace/span ids MUST NOT appear in redacted diagnostics exports unless the user explicitly opts in (HYP-M2-014). |

## 4. Session lifecycle

1. **TLS established** → initiator sends `hello` with device info and offered capabilities (§6), `seq: 1`, `sessionId: null`, and `requiresAck: false`.
2. Responder answers `hello` with its own info and the **intersected/limited** capability set, plus a fresh or resumed `sessionId`; this response is the handshake acknowledgment, so no separate `ack` frame is sent for `hello`.
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
| `hello` | both | no | Session open / resume, capability negotiation; responder `hello` is the handshake acknowledgment |
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
| `transfer.cancel` | both | yes | Either side aborts; partial data kept for resume unless `discard: true` |

Detailed payload schemas are normative in `protocol/schema/` where present (JSON Schema, HYP-M2-001/002/HYP-M3-010); this table is the index.

### 7.1 Notification payloads

`notification.posted` and `notification.updated` carry the same normalized Android notification payload. `notification.removed` carries only `sbnKey`. The `sbnKey` field is exactly `StatusBarNotification.getKey()` and is the only v0 notification identity. Android `postTime` MUST NOT appear in the payload or be used to decide whether a Mac notification is new or updated.

```json
{
  "sbnKey": "0|com.example.chat|7|thread-123|10101",
  "packageName": "com.example.chat",
  "title": "Alice",
  "text": "See you soon",
  "category": "msg",
  "clearable": true,
  "ongoing": false,
  "replyActions": [{ "actionIndex": 2, "label": "Reply" }]
}
```

| Field | Type | Required | Rule |
|---|---|---:|---|
| `sbnKey` | string | yes | Exact Android notification key from `StatusBarNotification.getKey()` |
| `packageName` | string | yes | Android package name that posted the notification |
| `title` | string | no | Trimmed display title; omitted when blank |
| `text` | string | no | Trimmed display summary/body; omitted when blank; privacy filters may omit or replace this field without changing `sbnKey` identity |
| `category` | string | no | Android notification category when present |
| `clearable` | boolean | yes | Whether Android reports the notification as clearable |
| `ongoing` | boolean | yes | Whether Android reports the notification as ongoing |
| `replyActions` | array | no | RemoteInput-capable Android actions only. Each item has non-negative integer `actionIndex` (index in Android `Notification.actions`) and display `label`. Omitted when no compatible reply action exists. |

`notification.dismiss.request` is sent from macOS to Android with:

```json
{ "sbnKey": "0|com.example.chat|7|thread-123|10101" }
```

Android replies with `notification.dismiss.result`:

```json
{ "sbnKey": "0|com.example.chat|7|thread-123|10101", "success": true }
```

If Android cannot cancel the notification, the result MUST carry `success: false` and an error code from §8, for example `permission/notifications-denied` when notification-listener access is unavailable or `plugin/notification-key-not-found` when the key cannot be cancelled.

`notification.reply.request` is sent from macOS to Android only for mirrored notifications that advertised at least one `replyActions` item:

```json
{ "sbnKey": "0|com.example.chat|7|thread-123|10101", "actionIndex": 2, "text": "On my way" }
```

Android replies with `notification.reply.result`:

```json
{ "sbnKey": "0|com.example.chat|7|thread-123|10101", "success": true }
```

If Android cannot send the RemoteInput reply, the result MUST carry `success: false` and an error code from §8, for example `permission/notifications-denied` when notification-listener access is unavailable, `plugin/notification-key-not-found` when the Android key no longer exists, or `plugin/reply-unavailable` when the action index is absent, has no RemoteInput, or its PendingIntent is no longer sendable. Quick Reply remains beta in v0 and must be advertised/tested only for compatible app families.

### 7.2 `text.send` payload

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

### 7.3 `transfer.manifest` payload

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

Schema validation intentionally does not encode cross-field arithmetic such as `chunkCount == ceil(sizeBytes / chunkSizeBytes)`. Sender and receiver implementations MUST enforce that relationship when creating chunk state.

### 7.4 `transfer.chunk` payload

`transfer.chunk` carries one base64-encoded chunk for a previously acknowledged `transfer.manifest`. It is always sent under capability `transfer.v1` and requires an ack.

```json
{
  "fileId": "f_01JZ0000000000000000000000",
  "chunkIndex": 0,
  "dataBase64": "aGVsbG8=",
  "chunkSha256": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
}
```

| Field | Type | Required | Rule |
|---|---|---:|---|
| `fileId` | string | yes | Matches a received/accepted manifest |
| `chunkIndex` | integer | yes | Zero-based chunk index, less than manifest `chunkCount` |
| `dataBase64` | string | yes | Base64 chunk bytes; decoded byte count must match the manifest-derived expected size |
| `chunkSha256` | string | yes | SHA-256 of decoded chunk bytes, lowercase hex |

Receivers MUST reject chunks for unknown `fileId`, out-of-range `chunkIndex`, invalid base64, chunk-hash mismatch, or decoded-size mismatch. For every non-final chunk, decoded byte count MUST equal `manifest.chunkSizeBytes`; for the final chunk it MUST equal `manifest.sizeBytes - chunkIndex * manifest.chunkSizeBytes`. Whole-file verification against manifest `sha256` happens when all chunks are assembled; HYP-M3-013 covers corrupted whole-file and corrupted-chunk rejection on both platforms.

### 7.5 `transfer.resume.request` / `transfer.resume.info` payloads

`transfer.resume.request` asks the receiver for its current checkpoint for a `fileId`. `transfer.resume.info` reports the next chunk the sender should transmit. Both messages are sent under capability `transfer.v1` and require an ack.

```json
{
  "fileId": "f_01JZ0000000000000000000000"
}
```

```json
{
  "fileId": "f_01JZ0000000000000000000000",
  "nextChunkIndex": 2,
  "needsManifest": false
}
```

| Field | Type | Required | Rule |
|---|---|---:|---|
| `fileId` | string | yes | Existing manifest id |
| `nextChunkIndex` | integer | `resume.info` only | Highest contiguous verified chunk index plus one |
| `needsManifest` | boolean | no | `resume.info` only; omitted or `false` means the receiver has active/completed manifest state, `true` means the sender must retransmit the original `transfer.manifest` before sending chunks |

If the receiver has no active or completed checkpoint for `fileId`, it MUST report `nextChunkIndex: 0` with `needsManifest: true`, or return `plugin/transfer-cancelled` if the partial transfer was explicitly discarded. The sender MUST retransmit the original `transfer.manifest` before chunk 0 when `needsManifest` is true; it MUST NOT send bare chunks for an unknown receiver state. If the receiver already completed the transfer, it SHOULD report `nextChunkIndex == chunkCount` and `needsManifest` omitted/false so the sender sends no more chunks. In v0, chunk bytes are written to receiver-owned temporary files and the checkpoint is in memory; persistent partial checkpoints across app restarts are outside HYP-M3-012's MVP and belong to later hardening.

The implemented wire path is: receiver handles `transfer.resume.request`, returns `transfer.resume.info(fileId,nextChunkIndex,needsManifest?)` from its current checkpoint, and the sender resumes from an outbound registry keyed by `fileId` that holds the original manifest plus streaming byte source. A sender that has no registered source for `fileId` MUST reject the resume rather than fabricating data.

### 7.6 `transfer.cancel` payload and local progress

`transfer.cancel` is sent by either side when the user cancels an active transfer. It is sent under capability `transfer.v1` and requires an ack.

```json
{
  "fileId": "f_01JZ0000000000000000000000",
  "discard": true
}
```

| Field | Type | Required | Rule |
|---|---|---:|---|
| `fileId` | string | yes | Transfer id being cancelled |
| `discard` | boolean | yes | `true` means the receiver drops any in-memory checkpoint; `false` lets the receiver keep partial state for resume |

Transfer progress is local UI state derived from `transfer.manifest` and accepted/sent chunks. It is not a separate wire message in v0.

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

## 9. v0 implementation decisions

These decisions document the Android and macOS behavior frozen for the current v0 implementation.

### 9.1 Strict envelope and hello validation

- Envelope JSON is strict on both platforms: unknown envelope fields are rejected as `protocol/invalid-envelope`.
- `trace` is strict: only `localOnly` and `spanId` are allowed; `localOnly` must be `true`; `spanId`, when present, must be a ULID.
- `hello` payloads are strict: only `device`, `resumeToken`, and `capabilities` are accepted; `device.kind` must be `android` or `macos`; `device.appVersion` must match the semantic-version pattern in `capability.schema.json`; capability names must match `feature.vN`; `transfer.v1.maxChunkBytes` must be 1024..2097152; `resumeToken` must be a string or `null`; `capabilities` must be an object.
- Malformed envelopes are surfaced to diagnostics and skipped; the connection stays open unless the frame layer or transport fails.
- The session layer treats `ack` and `heartbeat` as core. All other valid envelope types are delivered to the feature/plugin layer, which gates them with the negotiated capabilities from the handshake result. Unsupported capabilities return `plugin/unsupported-capability`; decoded but unhandled plugin types are acked if requested by the session layer and then answered with `protocol/unknown-type`.

### 9.2 Session and resume behavior

- Heartbeat interval defaults to 10 s; two missed intervals move the liveness monitor to degraded.
- `requiresAck` envelopes use a 10 s ack timeout; each timeout fires once.
- Session `seq` is per sender and per connection. The handshake `hello` consumes `seq: 1`; `ProtocolSession` starts subsequent traffic at `seq: 2`; reconnecting starts a new connection at `seq: 1` and preserves the logical `sessionId` only when resume succeeds.
- Resume tokens are responder-side random 32-byte handles, base64url encoded without padding, bound to one session and one peer SPKI fingerprint.
- Resume tokens are single-use, consumed on failed redemption, expire after 10 minutes, are invalidated on peer trust revocation, and are in-memory only.

### 9.3 TLS and platform floor

- v0 transport uses mutual TLS 1.3 with SPKI pinning on both platforms.
- Android API 26-28 lack platform TLS 1.3 support; the current implementation fails loudly rather than downgrading to TLS 1.2 or bundling a TLS provider.
- Changing the TLS floor, raising `minSdk`, or adding a TLS dependency requires a new ADR because it changes the security/dependency tradeoff.

### 9.4 Diagnostics and trace handling

- Structured diagnostics log taxonomy codes, components, operations, and optional local trace ids only.
- Redacted diagnostics exports omit trace ids by default on both platforms.
- Trace ids are included in an export only when the user explicitly enables the default-off beta diagnostics opt-in (HYP-M4-004). Disabling the toggle returns exports to the default trace-free shape.

### 9.5 Plugin behavior frozen in v0

- Notification identity is `StatusBarNotification.getKey()` only. `postTime` is not a wire identity field.
- Mac-side dismiss and Quick Reply use request/result pairs under `notifications.v1`; result payloads return explicit v0 error codes when Android cannot act.
- `text.send` is bidirectional under `text.v1` and requires receiver-side user confirmation before copy/open.
- Transfer resume checkpoints are in-memory in v0; persistent checkpoints across app restarts are post-v0 hardening.
- Transfer progress is local UI state, not a wire message.

## 10. Post-v0 protocol questions

These questions are explicitly outside the frozen v0 wire behavior:

1. Whether `hello` should carry a protocol feature bitmap separate from capabilities for faster version gating.
2. Heartbeat interval adaptivity on battery saver (Android FGS constraints may force >=15 s).
3. Max in-flight unacked messages and flow control. Current v0 code tracks pending ack ids but does not enforce a 64-message cap yet.
4. Persistent transfer checkpoints across app restarts.
