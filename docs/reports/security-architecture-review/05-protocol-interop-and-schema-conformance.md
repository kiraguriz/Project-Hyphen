# 05 协议互操作与 Schema 一致性审计

## 范围

本文件只覆盖 wire protocol、schema/runtime decoder conformance、能力协商、互操作漂移。输入文档 `docs/Project-Hyphen-security-architecture-review.md` 只作为未采信线索使用，下面结论均按当前仓库文件重新核验。

不在本文件展开：传输生命周期实现细节、接收文件保存/删除、进度 ACK 语义、配对 trust-state 详细修复。这些可以在相邻分类中处理；本文件只判断它们是否构成协议/互操作一致性问题。

## 覆盖的源发现

- G. 协议与互操作性：schema、能力协商、配对序列、Python validator。
- M-05：schema 与运行时严格性、`text.v1.direction` 能力方向漂移。
- transfer payload unknown-field handling：只作为 schema/interop 问题处理，不评价传输生命周期。
- `pair.request` / `pair.challenge` / `pair.response` / `pair.confirm`：只作为协议文档与生产 wire 状态机是否一致处理。
- Python schema validator 子集实现与 runtime decoder differential test 缺口。
- `HYP-M2-015`、`HYP-M6-006` 的 tracker/doc drift。

## 审计结论表

| ID | 源主张 | 判定 | 当前结论 | 优先级 |
|---|---|---|---|---|
| 05-01 | Envelope、capability、transfer manifest schema 较严格，且 v0 minor-version 策略存在 | confirmed | schema 和 ADR 确实存在；Envelope runtime strictness 也有双端实现和测试。这个正向结论成立。 | - |
| 05-02 | `text.v1.direction` schema 允许 `send-only` / `receive-only`，但 runtime 只要双方声明 `text.v1` 就协商为 `bidirectional` | confirmed | Kotlin/Swift 均验证 direction enum，却在 intersection 中无条件输出 `bidirectional`。当前内置 advertised 默认也是 `bidirectional`，所以现有同版本双端不一定立即坏；第三方或未来单向 peer 会漂移。 | P0 |
| 05-03 | `transfer.manifest` schema 禁止未知字段，但平台 runtime decoder 接受未知 manifest 字段 | confirmed | `transfer-manifest.schema.json` 有 `additionalProperties:false`，但 Android/Swift `TransferManifest` payload initializer 只读取已知字段，不检查 key set。 | P0 |
| 05-04 | 同一 schema 严格性问题可直接套到 `transfer.chunk` | disputed | runtime `TransferChunk` 确实只读已知字段，但仓库当前没有 `transfer-chunk.schema.json`。因此“违反 chunk schema”这句话过宽；准确说法是 transfer chunk payload 目前没有 schema-of-record，也没有 runtime unknown-key policy。 | P1 |
| 05-05 | 协议文档定义 `pair.request/challenge/response/confirm` 和双端确认后存 trust，但生产代码没有该 wire 状态机 | confirmed | 文档写了 pair.* 序列；当前生产路径是本地 SAS confirm 后直接写 trust 并启动 steady session。信任状态的详细修复归 04，本文件只确认协议/代码漂移。 | P0 |
| 05-06 | Python validator 是 JSON Schema 子集，不是完整标准实现或 runtime differential test | confirmed | validator 明确只实现项目当前 schema 用到的关键字，并对 unsupported keyword fail loud；所以“不完整导致当前 schema 静默误判”未被证明。已确认的缺口是：schema fixture 通过不等于双端 runtime decoder 通过。 | P1 |
| 05-07 | `HYP-M2-015`、`HYP-M6-006` 的“docs match code / protocol frozen”声明与当前协议一致性不符 | confirmed | 对本分类而言，这两个 `[x]` 的验收语义过强：pairing 序列、text direction、transfer manifest payload strictness 仍与文档/schema 存在漂移。 | P0 |
| 05-08 | 应仅凭本分类把整个 tracker 行降级 | unsupported | 本分类能证明协议/互操作子集漂移，但不能独立判定整行所有验收面。更稳妥是修复后更新行说明；若暂不修复，再由 tracker owner 拆分或降级对应子项。 | P1 |
| 05-09 | 第三方实现会因严格 decoder 或单向能力发生互操作失败 | environment-only | 现有代码证据证明风险前提成立；仓库内没有独立第三方实现或 live interop run，不能把外部失败当作已实测。 | P2 |

## 证据记录

### 协议与 schema 当前状态

- 协议文档自称当前 v0 wire behavior frozen，并把 tracker 来源指向 `HYP-M2-015` / `HYP-M6-006`：`docs/protocol/hyphen-protocol-v0.md:3-8`。
- pairing wire 序列包含 `pair.request`、`pair.challenge`、`pair.response`、`pair.confirm`，并要求双方确认后才持久化 trust：`docs/protocol/hyphen-protocol-v0.md:91-102`、`docs/protocol/hyphen-protocol-v0.md:112-117`。
- capability negotiation 文档要求 responder 返回 accepted subset/intersection，未知 capability 忽略：`docs/protocol/hyphen-protocol-v0.md:136-140`。
- message catalog 只说“where present”的 schema 是规范来源；当前 transfer chunk 等 payload 没有对应 schema 文件，因此不能把 manifest schema 的 `additionalProperties:false` 自动外推为 chunk schema：`docs/protocol/hyphen-protocol-v0.md:150-162`。
- Envelope schema 顶层 strict：`protocol/schema/envelope.schema.json:17`。Android/Swift Envelope decoder 均拒绝未知字段：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/Envelope.kt:64-65`、`apps/macos/Sources/HyphenTransport/Envelope.swift:117-118`。对应测试覆盖未知字段拒绝：`apps/android/app/src/test/kotlin/dev/hyphen/android/transport/EnvelopeCodecTest.kt:61-66`、`apps/macos/Tests/HyphenTransportTests/EnvelopeAndFrameTests.swift:65-78`。
- Capability schema 顶层和 device strict，但 capability option objects 故意 permissive；`text.v1.direction` enum 包含三种方向：`protocol/schema/capability.schema.json:5-8`、`protocol/schema/capability.schema.json:71-78`。
- Transfer manifest schema 明确 `additionalProperties:false`：`protocol/schema/transfer-manifest.schema.json:6-8`。已有 schema fixture 证明 `destinationPath` smuggling 应被拒绝：`protocol/test-vectors/transfer-manifest/invalid/smuggled-field.json:1-10`。

### Runtime conformance

- Android `NegotiatedCapabilities.intersect()` 在双方包含 `text.v1` 时无条件输出 `{"direction":"bidirectional"}`：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionHandshake.kt:91-93`。Android advertised 默认也固定为 bidirectional：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionHandshake.kt:124-136`。
- Swift 同样在 intersection 中无条件输出 `bidirectional`，advertised 默认也固定为 bidirectional：`apps/macos/Sources/HyphenTransport/SessionHandshake.swift:110-112`、`apps/macos/Sources/HyphenTransport/SessionHandshake.swift:127-131`。
- 双端 payload validator 确实接受 `send-only` / `receive-only` 作为合法 enum，但没有把方向参与协商矩阵：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionHandshake.kt:329-335`、`apps/macos/Sources/HyphenTransport/SessionHandshake.swift:426-432`。
- Android transfer manifest 和 chunk decoder 只读取已知字段：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:104-113`、`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:183-195`。
- Swift transfer manifest 和 chunk decoder 也只读取已知字段：`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:102-111`、`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:208-217`。
- Android/Swift resume/cancel payload decoder 也没有统一 key-set enforcement，说明这是 payload decoder pattern，而不只是 manifest：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:207-209`、`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:231-236`、`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:255-259`、`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:238-240`、`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:260-265`、`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:290-295`。
- Android pairing confirm path调用 `gate.confirm()` 后直接 `startSteadySession(...)`：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:479-483`。Swift confirm path同样 `gate.confirm()` 后直接 `startSteadySession(...)`：`apps/macos/Sources/HyphenApp/PairingController.swift:273-292`。
- `SasConfirmationGate.confirm()` 本身就是 trust-store write gate：Android `apps/android/app/src/main/kotlin/dev/hyphen/android/pairing/SasConfirmationGate.kt:37-41`，Swift `apps/macos/Sources/HyphenCore/SasConfirmationGate.swift:45-60`。
- 全仓库搜索 `pair.request|pair.challenge|pair.response|pair.confirm` 只命中文档和未采信源评审，没有命中生产 runtime message constants/handlers；这支持“wire 状态机未实现”的结论。

### Validator 与 tracker

- Python validator 明确说明只实现项目 schema 当前使用的 JSON Schema 子集，并对 unsupported constraint keyword 抛错：`scripts/validate_protocol_fixtures.py:4-7`、`scripts/validate_protocol_fixtures.py:22-23`、`scripts/validate_protocol_fixtures.py:40-43`。
- validator 会执行 `additionalProperties:false` unknown-field 检查：`scripts/validate_protocol_fixtures.py:80-83`，并要求每个 schema 有 valid/invalid fixtures：`scripts/validate_protocol_fixtures.py:118-143`。
- `scripts/test-protocol.sh` 只运行 schema fixtures、error registry lint、pairing vectors，不运行 Android/Swift runtime decoder differential conformance：`scripts/test-protocol.sh:1-7`。
- Tracker 行 `HYP-M2-015` 的 acceptance criteria 是 docs match code behavior：`docs/project_hyphen_roadmap_tracker_v0_3.md:162`。进度日志也记录它已标 `[x]`：`docs/project_hyphen_roadmap_tracker_v0_3.md:427`。
- Tracker 行 `HYP-M6-006` 是 freeze protocol v0 docs，acceptance criteria 是 docs match release behavior：`docs/project_hyphen_roadmap_tracker_v0_3.md:233`。进度日志记录 freeze 和后续 hardening：`docs/project_hyphen_roadmap_tracker_v0_3.md:449`、`docs/project_hyphen_roadmap_tracker_v0_3.md:458`。

## 修复计划

### P0

| 修复项 | 可能触达文件/模块 | 验收标准 | 验证命令/人工证据 |
|---|---|---|---|
| 明确并闭合 pairing wire truth：要么实现 `pair.request/challenge/response/confirm`，要么用 ADR/协议修订把当前 provisional TLS + SAS UI 流程定为规范 | `docs/protocol/hyphen-protocol-v0.md`、`protocol/schema/`、`protocol/test-vectors/pairing/`、Android `pairing`/`MainActivity`、macOS `HyphenCore`/`PairingController` | 文档、schema/vector、Android/Swift runtime 对同一 pairing 状态机达成一致；若保留 pair.*，双端必须在 accepted confirm 后才提交 trust；若取消 pair.*，文档不再声称有这些 wire frames | `./scripts/test-protocol.sh`；Android pairing/transport JVM tests；`cd apps/macos && swift test --filter Pairing`；真实双设备 SAS drill 或 wire log 证明实际帧序列 |
| 实现 `text.v1.direction` 协商矩阵 | Android/Swift `SessionHandshake`、text sender/receiver capability gates、对应 platform tests、协议 §6 direction 语义说明 | 3x3 direction 组合在 Kotlin/Swift 上结果一致；任何单向 peer 不会被协商成 `bidirectional`；发送或接收超出 negotiated direction 时返回 `plugin/unsupported-capability` 或等价错误 | Android `SessionHandshakeCapabilitiesTest`；Swift `NegotiatedCapabilitiesTests`；text sender/receiver tests；必要时 `./scripts/check.sh --strict` |
| 让 schema-backed transfer manifest runtime decoder 拒绝未知字段 | Android `TransferMessages.kt`、Swift `TransferMessages.swift`、两端 transfer tests | `destinationPath` 或任意额外 manifest 字段在 Android/Swift decoder 中失败；错误不写入 receiver state/storage | Android `TransferMessagesTest` 新增 smuggled manifest case；Swift `TransferMessagesTests` 新增 smuggled manifest case；`./scripts/test-protocol.sh` 保持 green |
| 修复 tracker/doc freeze 语义 | `docs/project_hyphen_roadmap_tracker_v0_3.md`、`docs/protocol/hyphen-protocol-v0.md`、必要 ADR | 修复后 `HYP-M2-015` / `HYP-M6-006` 的说明不再把未闭合子项伪装成全量 done；若代码暂不修，则 tracker 拆分或标出协议/interop residue | 文档 diff review；`./scripts/check.sh` |

### P1

| 修复项 | 可能触达文件/模块 | 验收标准 | 验证命令/人工证据 |
|---|---|---|---|
| 建立 runtime decoder differential conformance | `protocol/test-vectors/`、Android/Swift test loaders、`scripts/test-protocol.sh` 可选扩展 | 每个 schema fixture 至少被 Python validator、Android decoder、Swift decoder 三方验证；三方接受/拒绝结果一致 | `./scripts/test-protocol.sh`；Android fixture-loader JVM tests；Swift fixture-loader tests |
| 补齐或明确非 manifest transfer payload schema | `protocol/schema/transfer-chunk.schema.json` 等，或协议文档 §7 明确哪些 payload strict/permissive | `transfer.chunk`、resume、cancel 的 unknown-field policy 有规范来源；runtime 与规范一致 | 新增 valid/invalid fixtures；Android/Swift payload tests |
| 保持 dependency-free validator，但记录边界 | `scripts/validate_protocol_fixtures.py`、协议/开发文档 | 当前子集 validator 继续 fail loud；如果引入标准 `jsonschema` 依赖，必须先走依赖/许可证审批 | `./scripts/test-protocol.sh`；依赖审计记录 |

### P2

| 修复项 | 可能触达文件/模块 | 验收标准 | 验证命令/人工证据 |
|---|---|---|---|
| 独立 interop harness | `protocol/conformance/`、fixtures、small reference decoder | 第三方或独立 reference implementation 能跑同一套 hello/capability/transfer fixtures | conformance CLI 输出；至少一份非生产 decoder run |
| 能力演进政策文档化 | ADR-0006 或新 ADR、协议 changelog | 新 v0 minor、capability option、strict payload 变更各有明确升级路径 | 文档 review；protocol fixture changelog |

## 开放问题与环境门槛

- `pair.request/challenge/response/confirm` 是必须落地，还是协议应修订为当前 provisional TLS + SAS gate 流程？这需要安全/产品共同决策；本文件只确认现在不一致。
- `text.v1.direction` 的方向是从广告方视角还是接收方视角？修复前必须先在协议中写清，否则矩阵测试会固定错误语义。
- 是否允许为完整 JSON Schema validation 引入外部依赖？当前 repo 规则要求新依赖显式批准和 license review。
- 第三方互操作失败目前是 environment-only：需要独立实现或 conformance harness 才能从风险升级为实测失败。
- 真实双设备 pairing wire proof、单向 text peer proof、transfer smuggled payload over live session proof均未在本次任务执行；本次是静态文件证据审计加修复计划。
