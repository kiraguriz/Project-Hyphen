# 07 架构、异常隔离与测试策略核验

## 范围

本文件只覆盖 `docs/Project-Hyphen-security-architecture-review.md` 中属于架构/代码质量、责任边界、插件异常隔离、以及跨类别测试策略的主张。源评审仅作为未受信输入；以下结论按当前仓库文件重新核验。

不在本文件展开的修复细节：生命周期/FGS 与产品连续性归 01；文件传输生命周期与资源上限归 02；通知隐私与队列归 03；配对/trust 状态归 04；协议/interop/schema 归 05；CI gate、打包、发布外部门槛归 06。本文件只保留这些类别共同依赖的架构边界和回归测试策略。

## 覆盖的源评审主张

- F 架构与代码质量：双端已有模块拆分，但 `MainActivity` 与 `PairingController` 继续承担跨层职责。
- F 架构与代码质量：Kotlin/Swift 手写 schema/能力协商逻辑，存在漂移风险。
- F / H-07：Android `ProtocolSession.readLoop()` 未隔离 listener/plugin 异常，App 插件层只捕获 `IllegalArgumentException`。
- H 可测试性与测试覆盖：已有大量组件/loopback 测试，但缺少产品生命周期、插件异常、跨类别行为的回归测试。
- H 可测试性与测试覆盖：CI strict/平台 runner/打包 smoke 缺口只在此记录边界，具体 gate 归 06。

## 审计结论表

| ID | 源主张或拆分后问题 | 判定 | 结论与收窄 | 后续优先级 |
|---|---|---|---|---|
| 07-01 | macOS 已按 Core/Transport/Notifications/Text/Transfer/Diagnostics 等 target 拆分，Android 也有对应 package | confirmed | 当前模块确实存在；但产品会话 owner 仍集中在 app/controller 层，模块拆分尚未形成清晰运行时所有权边界。 | P1 |
| 07-02 | `MainActivity` 同时负责 UI、worker 生命周期、配对、信任、TLS、session、通知、文本、传输和诊断 | confirmed | 当前 `MainActivity` 为 1187 行，并直接持有 session、workers、UI model、notification outbox、transfer sender/receiver、trust/pairing/diagnostics 等路径。源评审没有夸大。 | P0/P1 |
| 07-03 | `PairingController` 承担类似跨层职责 | confirmed | 当前 `PairingController` 为 973 行，负责 QR/SAS window、provisional TLS、trust write、session start、notification reply/dismiss routing、text、transfer、activity feed 更新。需要收窄为“macOS app-session coordinator 过宽”，不是 SwiftPM target 完全无效。 | P1 |
| 07-04 | Android plugin/listener 异常可杀死 reader thread | confirmed | `ProtocolSession.readLoop()` 在 reader thread 直接调用 `listener.onEnvelope(envelope)`；`MainActivity.handleSessionEnvelope(...)` 只捕获 `IllegalArgumentException`，`IOException`、`RuntimeException` 等非该类型异常会逃逸。 | P0 |
| 07-05 | H-07 “不保证执行正常关闭” | confirmed | 需要收窄：正常 loop 末尾确实调用 `close()`，但如果 `listener.onEnvelope` 抛出未捕获异常，控制流跳过 loop footer，`close()`/`onClosed()` 不会由该路径保证执行。 | P0 |
| 07-06 | macOS 存在同一 H-07 插件异常路径 | disputed | 源 H-07 位置只列 Android；当前 macOS `PairingController.handleSessionEnvelope` 用 broad `catch` 把 handler 抛错转为 protocol error/status。macOS 仍有大 controller 问题，但没有同一个 Android reader-thread uncaught path。 | P2 |
| 07-07 | Kotlin/Swift 手写能力/schema 逻辑已产生漂移风险 | confirmed | 当前 schema 允许 `text.v1.direction` 为 send/receive-only；Android/Swift 协商交集仍在双方都有 `text.v1` 时固定输出 bidirectional。具体协议修复归 05，本文件保留“需要 differential/runtime tests”的架构测试项。 | P1 |
| 07-08 | 测试只覆盖纯组件，不能证明 App 主路径、后台常驻、跨平台互操作和分发产物 | confirmed | 当前确有 transport/transfer/notification/pairing/diagnostics 等组件与 loopback 测试；同时没有 `MainActivity` app-level lifecycle 测试，兼容性矩阵多数真实设备场景为 not-run/blocked。表述应改为“组件测试强，产品级跨层测试弱”。 | P0/P1 |
| 07-09 | 源评审列出的 notification policy ACK、cancel 后不得发 chunk、百万分块复杂度、critical 队列、Activity/FGS、sleep/wake、打包启动测试全部由 07 直接修复 | disputed | 这些是跨类别缺口摘要，不应全部落在 07。07 应建立 shared regression strategy；各具体 bug 的实现验收归 01/02/03/06。 | P1/P2 |
| 07-10 | 测试当前是否全绿 | environment-only | 本次任务是报告拆分与静态核验，未运行 Gradle/Swift/protocol 全量检查；是否全绿需要当前机器 toolchain、时间和并行工作状态确认。 | Gate |

## 证据记录

### 模块拆分存在，但运行时 owner 仍过宽

- macOS `Package.swift` 已将 app 拆成 `HyphenCore`、`HyphenDiagnostics`、`HyphenDiscovery`、`HyphenNotifications`、`HyphenPower`、`HyphenText`、`HyphenTransport`、`HyphenTransfer`，并有对应 test targets：`apps/macos/Package.swift:18`、`apps/macos/Package.swift:21`、`apps/macos/Package.swift:28`、`apps/macos/Package.swift:58`、`apps/macos/Package.swift:93`。
- Android 也存在 `transport`、`transfer`、`notifications`、`pairing`、`diagnostics`、`trust`、`text`、`discovery`、`ui`、`lan`、`companion` 等 package：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/ProtocolSession.kt:1`、`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:1`、`apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationPayload.kt:1`、`apps/android/app/src/main/kotlin/dev/hyphen/android/pairing/SasConfirmationGate.kt:1`、`apps/android/app/src/main/kotlin/dev/hyphen/android/ui/HyphenUiState.kt:1`。
- 但是 Android `MainActivity` 的 imports 和字段显示它直接装配 companion/discovery/diagnostics/notifications/pairing/text/transfer/transport/trust/UI：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:16`、`:19`、`:22`、`:29`、`:38`、`:44`、`:50`、`:61`、`:68`、`:70`。它直接持有 `activeSession`、`activeCapabilities`、`activeTransferSender`、`resumeToken`、`lastSessionId`、`textReceiver`、`diagnosticLogs`、`workerLock` 和 `model`：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:91`、`:94`、`:96`、`:97`、`:99`、`:100`、`:105`、`:109`。
- `MainActivity` 还负责 install/start/clear active state 与 outbox binding：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:192`、`:206`、`:214`、`:225`、`:235`；负责 QR pairing、trust gate 和 steady session：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:435`、`:454`、`:479`、`:494`；`onDestroy()` 会 interrupt workers、clear active session、clear notification outbox、stop session 和 stop discovery：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:1167`、`:1172`、`:1173`、`:1175`、`:1176`、`:1177`。
- macOS `PairingController` 直接持有 listener/window/provisional state/session/capabilities/transfer sender/token store/text receiver/transfer receiver/notification presenter/diagnostics/activity callbacks：`apps/macos/Sources/HyphenApp/PairingController.swift:21`、`:27`、`:30`、`:32`、`:34`、`:35`、`:36`、`:37`、`:39`、`:46`、`:68`、`:69`、`:70`。它同时负责 begin/end pairing、trust confirm、session respond/start、notification reply/dismiss、privacy sync、text send、file send、incoming envelope dispatch：`apps/macos/Sources/HyphenApp/PairingController.swift:99`、`:173`、`:273`、`:397`、`:407`、`:421`、`:477`、`:496`、`:562`、`:572`、`:605`、`:643`。

结论：模块/target 拆分是事实，但 `MainActivity` 与 `PairingController` 仍是跨层 orchestrator。源评审的“生命周期、线程和协议状态耦合”成立，特别是未来引入 FGS、reconnector、persistent transfer supervisor 后，当前 owner 边界会放大竞态。

### H-07 Android plugin 异常隔离

- Android `ProtocolSession.readLoop()` 在 reader thread 中读取 frame、decode、校验 seq 后直接调用 `listener.onEnvelope(envelope)`：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/ProtocolSession.kt:128`、`:138`、`:144`、`:153`。
- `readLoop()` 的 loop footer 正常会调用 `close()`：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/ProtocolSession.kt:157`；`close()` 会 `shutdownNow()`、`listener.onClosed()`、再 best-effort 关闭 socket：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/ProtocolSession.kt:184`、`:187`、`:189`、`:190`。
- 但 `listener.onEnvelope(...)` 外层没有 `try/finally`；如果 listener 抛出未捕获异常，线程在 `:153` 处退出，无法到达 `:157` 的 `close()`。这就是“close 不保证”的准确边界。
- Android app listener 把 `onEnvelope` 转给 `handleSessionEnvelope(...)`：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:520`、`:521`。该函数只在外围捕获 `IllegalArgumentException` 并发送 `protocol/invalid-envelope`：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:703`、`:705`、`:708`、`:712`；非 `IllegalArgumentException` 的 plugin/runtime 异常不会被该 catch 处理。
- `DiagnosticProtocolSessionListener` 也不隔离 `onEnvelope`；它直接转发 delegate：`apps/android/app/src/main/kotlin/dev/hyphen/android/diagnostics/StructuredLog.kt:94`、`:98`。诊断 wrapper 只对记录 failure 的内部逻辑用 `runCatching`：`apps/android/app/src/main/kotlin/dev/hyphen/android/diagnostics/StructuredLog.kt:116`、`:117`。
- macOS 不应被同样定性为 H-07：`ProtocolSession` 也直接触发 callback：`apps/macos/Sources/HyphenTransport/ProtocolSession.swift:225`、`:229`，但 `PairingController.handleSessionEnvelope` 用 `do/catch` 包住 notification/transfer/text handler，并在 catch 中发送 `protocol/invalid-envelope`：`apps/macos/Sources/HyphenApp/PairingController.swift:643`、`:784`、`:785`、`:789`、`:794`。这不等于没有架构问题，只是源 H-07 的 Android uncaught path 不能扩大到 macOS。

### 手写协议/能力逻辑与测试策略边界

- `capability.schema.json` 允许 `text.v1.direction` 为 `bidirectional`、`send-only`、`receive-only`：`protocol/schema/capability.schema.json:71`、`:74`、`:75`。
- Android runtime 解析时接受这些值：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionHandshake.kt:329`、`:331`、`:333`，但交集逻辑在双方都包含 `text.v1` 时固定输出 `bidirectional`：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionHandshake.kt:91`、`:92`。
- Swift 交集逻辑同样固定输出 `bidirectional`：`apps/macos/Sources/HyphenTransport/SessionHandshake.swift:110`、`:111`。
- 该问题的协议定义和 runtime 修复应归 05；07 的直接结论是：需要能同时喂给 Kotlin、Swift 和 schema fixture 的 differential/runtime test，避免手写约束继续漂移。

### 现有测试强项与缺口

- Android 现有 transport TLS/heartbeat/ack/session tests：`apps/android/app/src/test/kotlin/dev/hyphen/android/transport/ProtocolSessionTest.kt:13`、`:18`、`:181`、`:194`、`:208`。
- Android 现有 notification runtime snapshot/rebind/privacy-mode tests：`apps/android/app/src/test/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycleTest.kt:199`、`:200`、`:216`、`:217`、`:330`、`:340`。
- Android 现有 transfer sender/receiver/window tests，但主要是组件级：`apps/android/app/src/test/kotlin/dev/hyphen/android/transfer/TransferMessagesTest.kt:500`、`:511`、`:516`、`:526`。
- macOS 现有 protocol/transfer/notification/app-model tests；例如 transfer oversize/1GiB source guard：`apps/macos/Tests/HyphenTransferTests/TransferMessagesTests.swift:550`、`:573`、`:587`，app localization/model tests：`apps/macos/Tests/HyphenAppTests/LocalizationTests.swift:4`、`:9`、`apps/macos/Tests/HyphenAppTests/ActivityModelTests.swift:6`、`:31`。
- Android `src/test` 中没有 `MainActivity`、`ActivityScenario` 或 Robolectric app-lifecycle test；搜索到的 `onDestroyed` 测试属于 notification runtime，而不是 Activity session owner：`apps/android/app/src/test/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycleTest.kt:340`。
- Android 根包 `SkeletonSmokeTest` 仍只是 pipeline smoke：`apps/android/app/src/test/kotlin/dev/hyphen/android/SkeletonSmokeTest.kt:6`、`:9`、`:10`。
- 兼容性矩阵的真实端到端场景仍是 `not-run`，包括 first QR pairing、file transfer interruption/resume、1GB transfer、Mac sleep/wake、Android battery saver/background restriction：`docs/compatibility-matrix.md:56`、`:63`、`:64`、`:65`、`:66`。历史 evidence log 也记录 Android devices/emulators unavailable 与 macOS matrix blocked：`docs/compatibility-matrix.md:73`、`:74`。
- `scripts/check.sh` 会运行 Android unit、macOS test、protocol fixtures，但在非 strict 输出中明确“platform checks may be SKIPped”；CI gate 细节归 06：`scripts/check.sh:64`、`:73`、`:88`、`:97`、`:101`。

结论：测试覆盖不是“没有”，而是层级不够。当前测试能证明组件和部分 loopback 语义，但不能证明 Activity/FGS 生命周期、插件异常隔离、真实跨平台 session、打包后启动、以及跨类别竞态已经关闭。

## 修复计划

### P0：隔离 Android session reader 与 plugin/controller 异常

可能触达文件/模块：

- `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/ProtocolSession.kt`
- `apps/android/app/src/main/kotlin/dev/hyphen/android/diagnostics/StructuredLog.kt`
- `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt`
- `apps/android/app/src/test/kotlin/dev/hyphen/android/transport/ProtocolSessionTest.kt`
- 必要时新增小型 `PluginEnvelopeDispatcher` / `SessionErrorMapper`，但不要先做大重构。

工作内容：

1. 在 `ProtocolSession.readLoop()` 的 listener boundary 加 `try/catch/finally` 或等价封装，确保任意 plugin/listener 异常都会映射为受控 error、记录诊断、并最终 close session。
2. 区分协议 decode/validation 错误与 plugin handler runtime 错误：前者继续使用 `protocol/invalid-envelope`；后者优先使用现有 registry 中合适的 `plugin/*` 或新增受控 code 时同步更新 error schema/vector/lint。
3. `onClosed()` 必须在 listener 异常路径触发一次且仅一次；notification outbox、active session state、UI suspended event 不得假保持 connected。
4. Android app 层 `handleSessionEnvelope` 不再只捕获 `IllegalArgumentException`；对 transfer/notification/text handler 的 runtime failure 要转成受控 error 或 terminal plugin failure，不向 reader thread 泄漏。

验收标准：

- 人为构造 `listener.onEnvelope` 抛出 `RuntimeException`/`IOException`/transfer storage failure 时，reader thread 不静默死亡。
- `ProtocolSession.Listener.onClosed()` 触发；`closed` 状态阻止后续 send；socket best-effort close 被执行。
- 发送给 peer 或记录到 diagnostics 的错误码来自 registry，不包含原始 exception、文件路径、正文、URL 或敏感 detail。
- 既有 heartbeat、ack、frame-too-large、invalid-envelope 测试继续通过。

验证命令/人工证明：

- `cd apps/android && ./gradlew test --tests dev.hyphen.android.transport.ProtocolSessionTest`
- `cd apps/android && ./gradlew test --tests dev.hyphen.android.diagnostics.StructuredLogTest`
- `cd apps/android && ./gradlew test --tests dev.hyphen.android.diagnostics.RedactedDiagnosticsExporterTest`
- `./scripts/test-protocol.sh`（如果新增/调整 error code）
- 手工或临时 debug build：注入 transfer storage failure，确认 Android UI 从 connected 变 suspended，并且 diagnostics preview 只展示受控 code。

### P1：明确 session owner / app controller 边界，收窄巨型 controller

可能触达文件/模块：

- Android：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt`，新增或扩展 `dev.hyphen.android.session` / `dev.hyphen.android.connection` / `dev.hyphen.android.transfer` app-level coordinator。
- macOS：`apps/macos/Sources/HyphenApp/PairingController.swift`，可能拆出 `SessionCoordinator`、`TransferCoordinator`、`NotificationSessionBridge`。
- Shared component tests：Android `src/test/.../session/`，macOS `HyphenAppTests` 或新 target-level tests。

工作内容：

1. Android `MainActivity` 退为 UI host：订阅 model/state、发起用户意图，不直接拥有 session lifecycle、worker set、notification outbox、transfer sender/receiver、resume token。
2. macOS `PairingController` 保留 pairing UI orchestration，但 session/notification/transfer/text dispatch 迁到可测试 coordinator；`PairingController` 不再成为所有 plugin 的唯一运行时 owner。
3. 统一 active session install/replace/close/revoke 语义：旧 session 的 callbacks 不能清掉新 session；trust reset/revoke、Activity destroy、session close、network loss 的状态转换要有单一 owner。
4. 与 01 协同决定 Android v1 是 FGS 常驻连接还是 reconnect-on-use；本文件不单独强制 FGS 方案。

验收标准：

- Activity destroy/recreate 不再因为 UI host 销毁而无条件 stop active session，除非 01 明确选择 conservative reconnect-on-use 并更新 ADR/产品语义。
- 所有 notification outbox bind/clear、transfer sender ack routing、resume token 更新都通过 session owner 发生。
- `MainActivity` 和 `PairingController` 的主要职责可从文件结构读出：UI/controller 只处理 UI 意图，session coordinator 处理 transport/plugin dispatch，feature modules 处理 payload。
- 新旧 session race 有测试：旧 session `onClosed` 不能清掉新 session state。

验证命令/人工证明：

- `cd apps/android && ./gradlew test`
- `cd apps/macos && swift test`
- `./scripts/check.sh --strict`
- Android manual proof：连接后旋转/重建 Activity 或后台前台切换，session owner 状态不出现假 connected 或无故断开。
- macOS manual proof：断开、重连、pairing window close、notification reply/dismiss、file send 仍走同一 session owner 状态机。

### P1：建立跨语言协议/能力 differential 测试，而不是继续手写漂移

可能触达文件/模块：

- `protocol/test-vectors/capability/`
- `protocol/schema/capability.schema.json`
- `apps/android/app/src/test/kotlin/dev/hyphen/android/transport/SessionHandshakeCapabilitiesTest.kt`
- `apps/macos/Tests/HyphenTransportTests/NegotiatedCapabilitiesTests.swift`
- `scripts/validate_protocol_fixtures.py`

工作内容：

1. 对 `text.v1.direction`、`transfer.v1.maxChunkBytes`、`notifications.v1.reply/dismiss/privacyPolicy` 添加同一组 cross-runtime fixtures。
2. Kotlin/Swift 都从 fixtures 读 expected negotiated result，或至少保持同名测试用例和相同 inputs/outputs。
3. 对 unknown capability keys、known capability unknown options、strict envelope fields 的期望写清楚，避免 schema、Python validator、runtime decoder 各自解释。

验收标准：

- `send-only` + `receive-only`、`send-only` + `send-only`、missing direction、unknown capability 等组合在 schema、Android、Swift 之间结果一致。
- 若产品决定只支持 bidirectional，则 schema 和 docs 也收窄，不再允许 runtime 永远不会正确协商的方向。

验证命令/人工证明：

- `./scripts/test-protocol.sh`
- `cd apps/android && ./gradlew test --tests dev.hyphen.android.transport.SessionHandshakeCapabilitiesTest`
- `cd apps/macos && swift test --filter NegotiatedCapabilitiesTests`

### P1：补齐跨类别回归测试矩阵

可能触达文件/模块：

- Android tests under `apps/android/app/src/test/kotlin/dev/hyphen/android/`
- macOS tests under `apps/macos/Tests/`
- `docs/test-plans/`
- `docs/compatibility-matrix.md`
- category owners may touch 01/02/03/04/05/06 docs and tracker separately；07 不直接修改这些文件。

工作内容：

1. 将源评审列出的缺口拆成“组件回归”“app-coordinator 回归”“真实设备/人工 proof”三层。
2. 每个类别保留自己的 bug 修复测试，但 07 维护一条 shared acceptance：同一 session owner 下，lifecycle/privacy/transfer/protocol 错误不会互相绕过。
3. 增加 hostile plugin/feature handler regression：plugin handler 抛错后 session terminal、outbox clear、UI suspended、diagnostic redacted。
4. 增加 app-coordinator tests：Activity destroy/recreate、session replace race、trust revoke during transfer、privacy policy pending while active notifications exist、transfer cancel after outstanding ACK。

验收标准：

- 01：Activity/FGS 或 chosen lifecycle strategy 有自动化或手工 proof，不再只有 component reconnection test。
- 02：cancel 后原 sender 不再 pump chunk；资源上限/大 chunk count 不产生 O(n²) 依赖；progress 层次清楚。
- 03：policy ACK/ready 前 active snapshot 不泄露正文；critical queue 有硬上限或显式 backpressure 证明。
- 04：双端 confirm/trust commit 或协议修订有对应 wire/runtime tests。
- 05：schema/runtime strictness 与 capability direction 有 differential tests。
- 06：打包/CI strict/smoke 由 release gate 文件验收。

验证命令/人工证明：

- `cd apps/android && ./gradlew test`
- `cd apps/macos && swift test`
- `./scripts/test-protocol.sh`
- `./scripts/check.sh --strict`
- 手工矩阵：`docs/compatibility-matrix.md` 中 QR pairing、file transfer interruption/resume、1GB transfer、Mac sleep/wake、Android background restriction 至少有真实设备或明确 blocked 证据，不能把 not-run 当 pass。

### P2：架构文档和 code ownership 清理

可能触达文件/模块：

- `docs/adr/`
- `docs/protocol/hyphen-protocol-v0.md`
- `docs/project_hyphen_plan_v0_3_en.md` / `_zh.md`（只做事实修正并注明）
- package/module README 或 lightweight code comments。

工作内容：

1. 写清运行时 ownership：UI host、session owner、feature plugin handler、transport、diagnostics 的责任边界。
2. 写清 plugin exception policy：哪些错误继续 session、哪些错误 terminal close、哪些错误只回 peer error。
3. 给新增 coordinator 建最小 ownership README 或 ADR，防止后续又把 session/transfer/notification 状态塞回 UI controller。

验收标准：

- 新开发者能从 docs/ADR 和模块名判断连接、outbox、transfer、privacy、diagnostics 的 owner。
- root `AGENTS.md`/`CLAUDE.md` 不写当前实现快照，只保留 sources/workflow/safety；进度仍回 tracker。

验证命令/人工证明：

- `rg -n "SessionCoordinator|session owner|plugin exception|outbox" docs apps`
- code review checklist：新增 session/transfer/privacy 代码不得直接扩大 UI controller 的跨层职责。

## 未支持、争议或需环境证明的点

- disputed：不能把 H-07 Android reader-thread uncaught plugin exception 扩大为 macOS 同类缺陷。macOS 当前有 broad catch；它的问题是 `PairingController` 过宽，而不是同一条 uncaught Android path。
- disputed：源评审列出的所有测试缺口不应由 07 独占修复。07 负责共享测试策略和跨层异常/lifecycle 证明；隐私、传输、协议、CI/打包的具体修复归各自类别。
- environment-only：本次未运行全量 `./scripts/check.sh --strict`、Gradle、Swift 或真机矩阵；“当前全绿”“真实设备通过”“打包后启动”必须由对应命令输出、CI run、screenshot/log 或矩阵证据单独证明。

## 开放问题

- Android v1 产品策略是 FGS 常驻连接，还是 reconnect-on-use 的保守模型？这决定 session owner 是否必须离开 Activity 并绑定 foreground notification。
- Plugin handler 抛出非协议错误时，默认策略应是关闭整个 session，还是发送 plugin error 后继续？当前 H-07 修复可先按 terminal close 处理以避免假连接；长期策略需要 ADR/协议错误语义确认。
- `text.v1.direction` 是真的要支持单向能力，还是 schema 应在 v0 收窄到 `bidirectional`？该决定应由 05 协议类别落地。
- 真实设备矩阵、sleep/wake、Android background restriction、打包后启动属于外部/人工 proof；没有设备、签名、CI runner 或发布凭据时只能保持 blocked/environment-only，不能用本地组件测试替代。
