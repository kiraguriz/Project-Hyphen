# 01 生命周期与产品连续性审计

## 范围

本文件只覆盖 `docs/Project-Hyphen-security-architecture-review.md` 中与“产品生命周期和连续性”相关的说法。源评审仅作为未信任输入；下面结论基于当前仓库文件的静态核验，不把源评审、旧 tracker 日志或测试文件存在本身当作事实。

覆盖源发现：

- A. 功能性、B. 完整性中“产品路径未闭环”的说法。
- H-01: 常驻连接/自动重连未接入产品路径。
- M-08: manual endpoint 是 TCP probe，不是 manual pairing。
- L-03: 阶段/版本注释过期，且影响生命周期预期。
- HYP-M2-013 及相关 tracker/doc 中“组件完成”和“产品完成”混用的问题。

不覆盖：通知隐私竞态、传输算法复杂度、pairing transcript 安全细节、发布签名/商店审核、许可证审计。它们可能与生命周期有关，但应由对应类别报告承接。

## 源发现覆盖清单

| 源发现 | 本报告处理方式 |
|---|---|
| A 功能性评分称 QR happy path 存在，但常驻、重连、文件接收交付未闭环 | 核验产品路径证据；评分数字不复核 |
| B 完整性评分称多个 v1 P0 仍停留在组件级、内存级或 UI 演示级 | 核验 tracker DoD 与 HYP-M2-013/HYP-M2-015/传输行的产品语义 |
| H-01 常驻连接/自动重连未接入产品路径 | 核验 Android/macOS app 入口、session owner、reconnector call sites |
| M-08 manual endpoint 不是 manual pairing | 核验 Android manual 输入路径和协议/安装文档承诺 |
| L-03 多处版本/阶段注释过期 | 核验会误导生命周期预期的注释/状态文案 |
| tracker/doc drift around HYP-M2-013 | 核验 tracker `[x]` 定义、HYP-M2-013 行、历史日志和当前代码 wiring |

## 审计结论表

| ID | 源主张 | verdict | 收窄后的结论 | 关键证据 |
|---|---|---|---|---|
| A-1 | QR 路径可展示配对后会话，但不构成持续产品闭环 | confirmed | QR/manual 输入框中 QR payload 会进入 TLS/SAS/steady session；这只能证明初次会话路径，不证明生命周期连续性 | `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:399`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:435`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:479`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:494`, `apps/macos/Sources/HyphenApp/PairingController.swift:284`, `apps/macos/Sources/HyphenApp/PairingController.swift:397` |
| A-2 | Android manual endpoint 只做 TCP probe | confirmed | 非 QR 输入会 parse 为 endpoint 并调用 `EndpointConnectProbe().probe(...)`，成功时只输出 `connected`；没有 TLS provisional pairing、SAS、trust write 或 session start | `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:399`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:409`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:413`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:417` |
| A-3 | Android session 由 Activity 持有，Activity 销毁会断开产品路径 | confirmed | `activeSession`、`resumeToken`、`lastSessionId` 是 `MainActivity` 字段；`onDestroy()` interrupt workers、清 active state、清 notification outbox、stop session 和 discovery manager | `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:89`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:93`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:97`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:1167` |
| A-4 | Android 没有承载持续连接/大文件传输的前台服务产品路径 | confirmed | manifest 只有 `MainActivity`、CompanionDeviceService 和 NotificationListenerService；没有 transport/dataSync/connectedDevice FGS declaration 或 foreground service type | `apps/android/app/src/main/AndroidManifest.xml:20`, `apps/android/app/src/main/AndroidManifest.xml:25`, `apps/android/app/src/main/AndroidManifest.xml:36`, `apps/android/app/src/main/AndroidManifest.xml:48` |
| A-5 | macOS wake reconnect 不是产品级重连 | confirmed | AppDelegate 创建的是 `ReconnectStateMachine`，`startConnect` 只写日志；UI 仍显示 `no transport until M2` | `apps/macos/Sources/HyphenApp/main.swift:163`, `apps/macos/Sources/HyphenApp/main.swift:167`, `apps/macos/Sources/HyphenApp/main.swift:510`, `apps/macos/Sources/HyphenApp/main.swift:515` |
| A-6 | macOS session close 后只清状态，没有 app-level redial | confirmed | `PairingController` 的 `onClosed` 清 `activeSession`、capabilities、transfer sender、notification handlers，然后发 suspended event；没有调用 `SessionReconnector` | `apps/macos/Sources/HyphenApp/PairingController.swift:435`, `apps/macos/Sources/HyphenApp/PairingController.swift:437`, `apps/macos/Sources/HyphenApp/PairingController.swift:446`, `apps/macos/Sources/HyphenApp/PairingController.swift:447` |
| A-7 | 成功接收文件后没有用户可用交付物 | confirmed | Android/macOS 在 receiver 完成并校验后都删除 temp file，只留下状态/ActivityEvent；这属于产品路径未闭环，但详细传输设计应由传输类别继续拆 | `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:661`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:664`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:668`, `apps/macos/Sources/HyphenApp/PairingController.swift:727`, `apps/macos/Sources/HyphenApp/PairingController.swift:729`, `apps/macos/Sources/HyphenApp/PairingController.swift:731` |
| B-1 | HYP-M2-013 `[x]` 误导为产品级自动重连完成 | confirmed | tracker 的 `[x]` 定义要求验收/测试/失败路径/文档都处理；HYP-M2-013 行是 `[x]`，但 app code 没有实例化 reconnectors，Android/macOS reconnectors 当前只在测试中被构造 | `docs/project_hyphen_roadmap_tracker_v0_3.md:13`, `docs/project_hyphen_roadmap_tracker_v0_3.md:19`, `docs/project_hyphen_roadmap_tracker_v0_3.md:30`, `docs/project_hyphen_roadmap_tracker_v0_3.md:160` |
| B-2 | HYP-M2-013 的组件测试存在，但产品路径未接入 | confirmed | 源主张应收窄为“组件完成，不等于产品完成”。`SessionReconnector` 类确实存在并能在 session close 后 schedule retry；当前 `rg "SessionReconnector\\(" apps/android apps/macos` 只命中 transport 类和 tests，没有 app production call site | `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionReconnector.kt:16`, `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionReconnector.kt:168`, `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionReconnector.kt:222`, `apps/macos/Sources/HyphenTransport/SessionReconnector.swift:11`, `apps/macos/Sources/HyphenTransport/SessionReconnector.swift:65`, `apps/macos/Sources/HyphenTransport/SessionReconnector.swift:136` |
| B-3 | HYP-M2-015/docs 声称 manual endpoint 与 reconnect 行为匹配当前产品 | confirmed | 协议文档和安装文档仍把 manual IP/endpoint 描述成 SAS pairing fallback；当前 Android UI manual path 没有进入该流程 | `docs/protocol/hyphen-protocol-v0.md:89`, `docs/protocol/hyphen-protocol-v0.md:112`, `docs/protocol/hyphen-protocol-v0.md:113`, `docs/install/installation_en.md:150`, `docs/install/installation_zh.md:133` |
| B-4 | HYP-M3 transfer rows `[x]` 可被误读为完整产品交付 | confirmed | HYP-M3-011/012 `[x]` 有组件/loopback语义，但当前接收完成后文件被删除，说明“用户可取用交付”不应从这些行自动推断 | `docs/project_hyphen_roadmap_tracker_v0_3.md:180`, `docs/project_hyphen_roadmap_tracker_v0_3.md:181`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:664`, `apps/macos/Sources/HyphenApp/PairingController.swift:729` |
| H-01-overbroad | 若把源主张理解成“项目没有 reconnect 实现” | disputed | 代码中有 Android/macOS reconnectors 和测试，问题不是组件不存在，而是未被 app lifecycle owner 使用 | `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionReconnector.kt:9`, `apps/macos/Sources/HyphenTransport/SessionReconnector.swift:5`, `docs/project_hyphen_roadmap_tracker_v0_3.md:403`, `docs/project_hyphen_roadmap_tracker_v0_3.md:458` |
| L-03 | 影响生命周期判断的阶段/版本注释过期 | confirmed | Android manifest 仍称 future TLS transport；macOS wake path 仍称 transport 将来落地；Bonjour advertiser 仍称 incoming refused until M2。它们不是运行时 bug，但会误导 owner/wiring 判断 | `apps/android/app/src/main/AndroidManifest.xml:4`, `apps/macos/Sources/HyphenApp/main.swift:163`, `apps/macos/Sources/HyphenApp/main.swift:168`, `apps/macos/Sources/HyphenApp/main.swift:515`, `apps/macos/Sources/HyphenDiscovery/BonjourAdvertiser.swift:6`, `apps/macos/Sources/HyphenDiscovery/BonjourAdvertiser.swift:42` |
| SCORE | A=3.5、B=3.0 等数字评分 | unsupported | 本报告没有复算权重或分数；只确认/否定底层事实。数字可作为源评审意见，不应作为本 category 的事实结论 | `docs/Project-Hyphen-security-architecture-review.md` |
| ENV-1 | Activity 回收、Mac 睡眠、Wi-Fi 切换、OEM battery 等真实设备表现 | environment-only | 静态代码足以确认没有产品级自动 reconnect owner；但实际重现率、OS/OEM 影响、20 次 sleep/wake 或 restricted LAN 表现需要设备矩阵和原始日志 | `docs/project_hyphen_roadmap_tracker_v0_3.md:198`, `docs/project_hyphen_roadmap_tracker_v0_3.md:456` |

## 证据说明

### 产品承诺和 DoD

- README 把 Hyphen 描述为 continuously connected companion，并明确 v1 包含双向文件传输和 sleep/wake/network reconnect：`README.md:3`, `README.md:9`, `README.md:21`, `README.md:22`。
- ADR-0001 把 v1 moat 定义为 persistent paired cross-device continuity，must-have 包含 QR/manual/remembered endpoint fallback、双向文件传输 resume/progress/cancel、跨 Mac sleep/wake/network/Android background 的 reconnect：`docs/adr/0001-product-scope.md:10`, `docs/adr/0001-product-scope.md:23`, `docs/adr/0001-product-scope.md:27`, `docs/adr/0001-product-scope.md:28`。
- tracker 的 `[x]` 语义不是“组件存在”，而是验收满足、测试运行、失败路径处理、相关 docs/ADR 更新：`docs/project_hyphen_roadmap_tracker_v0_3.md:13`, `docs/project_hyphen_roadmap_tracker_v0_3.md:19`, `docs/project_hyphen_roadmap_tracker_v0_3.md:30`。

### Android 当前产品路径

- `MainActivity` 同时持有 discovery、active session、resume token、transfer sender、notification outbox 绑定和 UI model：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:82`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:89`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:91`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:94`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:97`。
- active state 安装会检查 `activityDestroyed`，说明 session 生命周期被 Activity 存活状态约束：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:206`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:211`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:212`。
- `onClosed()` 只清 state/outbox 并发 suspended event，没有进入 reconnector：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:530`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:531`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:535`。
- `onDestroy()` 会 interrupt workers、clear active state、clear outbox、stop session、stop discovery manager：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:1167`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:1172`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:1173`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:1175`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:1176`, `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:1177`。
- Android `SessionReconnector` 自身会在 session close 后 schedule retry，但当前不是 app owner：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionReconnector.kt:168`, `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionReconnector.kt:222`, `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionReconnector.kt:227`, `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionReconnector.kt:231`。

### macOS 当前产品路径

- `PairingController` 持有 active session/capabilities/transfer sender/token store，但不是通过 app-level reconnector owner 建立：`apps/macos/Sources/HyphenApp/PairingController.swift:32`, `apps/macos/Sources/HyphenApp/PairingController.swift:34`, `apps/macos/Sources/HyphenApp/PairingController.swift:35`, `apps/macos/Sources/HyphenApp/PairingController.swift:36`。
- SAS confirm 后会直接用 provisional connection 进入 steady session；所以“没有初次 steady session”是错误说法：`apps/macos/Sources/HyphenApp/PairingController.swift:284`, `apps/macos/Sources/HyphenApp/PairingController.swift:288`, `apps/macos/Sources/HyphenApp/PairingController.swift:397`, `apps/macos/Sources/HyphenApp/PairingController.swift:407`。
- session close 只清状态并上报 suspended：`apps/macos/Sources/HyphenApp/PairingController.swift:435`, `apps/macos/Sources/HyphenApp/PairingController.swift:437`, `apps/macos/Sources/HyphenApp/PairingController.swift:440`, `apps/macos/Sources/HyphenApp/PairingController.swift:447`。
- macOS `SessionReconnector` 可以执行 TLS connect、handshake 和 session close retry，但没有被 AppDelegate/PairingController 当前产品路径实例化：`apps/macos/Sources/HyphenTransport/SessionReconnector.swift:65`, `apps/macos/Sources/HyphenTransport/SessionReconnector.swift:79`, `apps/macos/Sources/HyphenTransport/SessionReconnector.swift:101`, `apps/macos/Sources/HyphenTransport/SessionReconnector.swift:136`, `apps/macos/Sources/HyphenTransport/SessionReconnector.swift:140`。

## 修复计划

### 实施状态（2026-06-18）

| 任务 | 状态 | 证据 |
|---|---|---|
| P0-1 产品级连接 owner（Android `ConnectionSupervisor` + macOS reconnect listener） | [x] 已实现 | `apps/android/.../session/ConnectionSupervisor.kt`, `PairingController.requestReconnect()`, `main.swift` wake hook |
| P0-2 manual endpoint → manual SAS pairing | [x] 已实现 | `EndpointParser` optional `fp`, `host:port?n=`, Android provisional TLS + SAS |
| P0-3 tracker/doc 产品完成语义 | [x] 已实现 | `HYP-M2-013/015`→`[~]`, 新增 `HYP-M2-016`/`HYP-M3-016`, install docs |
| P1-1 文件接收交付路径 | [x] 已实现（基础） | Android `received/`, macOS `~/Downloads`; 见 02 维度继续深化 |
| P1-2 清理过期生命周期注释 | [x] 已实现 | manifest, `main.swift`, `BonjourAdvertiser.swift` |
| P2-1 设备矩阵与外部门槛 | [ ] 仍 blocked | environment-only；无真机/sleep-wake 原始日志 |

### P0-1 建立真正的产品级连接 owner

目标：把“初次会话”和“自动重连/后台连续性”从 Activity/PairingController 的临时状态提升为明确的 product lifecycle owner。

可能触达文件/模块：

- Android: `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt`, `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionReconnector.kt`, 新增 `dev.hyphen.android.session` 或 `dev.hyphen.android.connection` supervisor, `apps/android/app/src/main/AndroidManifest.xml`, notification outbox runtime, transfer sender/receiver wiring。
- macOS: `apps/macos/Sources/HyphenApp/main.swift`, `apps/macos/Sources/HyphenApp/PairingController.swift`, `apps/macos/Sources/HyphenTransport/SessionReconnector.swift`, `HyphenCore` trust/remembered-endpoint model。
- tests: Android reconnect/product lifecycle tests；macOS app/reconnector integration tests；protocol loopback tests保留为组件证据。

实现顺序：

1. 定义单一 `ConnectionSupervisor` 职责：持有 active session、resume token、last session id、negotiated capabilities、notification outbox binding、transfer sender、trust revoke hooks。
2. Android 先把 `MainActivity` 改为 UI client，只订阅 supervisor state，不直接拥有 session。Activity destroy/recreate 不得 stop active session；trust reset/revoke 才能 stop。
3. Android 决策 foreground policy：如果目标仍是常驻连接/大文件传输，补 manifest/service/notification；如果采用 reconnect-on-use，必须同步 README/ADR/tracker，降低 always-connected 承诺。
4. macOS 在 initial pairing 成功后把 endpoint/fingerprint/session handoff 给 `SessionReconnector` 或等价 supervisor；`onClosed` 必须触发 retry，而不是只清状态。
5. sleep/wake、network change、manual disconnect、trust revoke 都走同一个 supervisor state machine，避免多个 owner。

验收标准：

- Android Activity destroy/recreate 后，已配对 session 不因 UI teardown 自动丢失；如果 OS 杀进程，恢复路径和用户可见状态明确。
- Android/macOS 模拟 socket drop 后会按 1/5/15/30s backoff 重连，并保留或明确重建 session/resume state。
- Mac sleep/wake 后 `startConnect` 进入真实 dial/handshake，而不是日志。
- notification/text/transfer 在一次断线重连后仍可用，或在无法恢复时显示明确错误并可重新配对。
- trust revoke/reset 立即停止 supervisor、清 outbox、清 resume token，并阻止自动重连。

验证命令/手工证明：

- `cd apps/android && ./gradlew test assembleDebug`
- `cd apps/macos && swift build && swift test`
- `./scripts/test-protocol.sh`
- `./scripts/check.sh`
- 手工：一台 Android 设备/模拟器加 macOS app，完成 QR pairing，触发 Activity recreate/background/foreground、拔网/切 Wi-Fi、Mac sleep/wake，各记录状态、trace id、重连耗时，并在重连后发送 text 和小文件。

### P0-2 关闭 manual endpoint 与 manual pairing 的产品差距

目标：manual endpoint 要么成为真实 manual pairing，要么从 UI/文档中降级为 connectivity probe。

可能触达文件/模块：

- Android: `EndpointParser`, `EndpointConnectProbe`, `MainActivity.probeManualEndpoint`, pairing UI copy。
- macOS: pairing listener/endpoint display, remembered endpoint model。
- docs: `docs/protocol/hyphen-protocol-v0.md`, `docs/install/installation_en.md`, `docs/install/installation_zh.md`, ADR/tracker rows。

实现选项：

1. 推荐产品实现：manual IP 无 QR fingerprint 时建立 provisional TLS，双方显示 SAS，SAS 是主要 MITM 防线；确认后写 trust 并启动 session。
2. 保守文档实现：如果短期不做 manual pairing，则把 UI label 和安装文档改为“TCP connectivity probe / diagnostic”，并从 v1 fallback 承诺中拆出 blocker。

验收标准：

- 用户输入 `host:port` 后，不再只看到 `connected`；要么进入 SAS pairing，要么文案明确不是配对。
- manual pairing 路径 reject/mismatch 不写 trust，不留下 resume token。
- 协议、安装文档、tracker 同步同一语义。

验证命令/手工证明：

- `cd apps/android && ./gradlew test assembleDebug`
- `cd apps/macos && swift test`
- 手工：不用 QR，只用 IP/port 完成或拒绝配对；记录两端 SAS、trust store 状态、session 是否启动。

### P0-3 修正 tracker/doc 的产品完成语义

目标：恢复 `[x]` 的 DoD 含义，避免 HYP-M2-013 这类组件完成被读成产品完成。

可能触达文件/模块：

- `docs/project_hyphen_roadmap_tracker_v0_3.md`
- `docs/protocol/hyphen-protocol-v0.md`
- `README.md`
- 相关 release/readiness docs

实现顺序：

1. 将 HYP-M2-013 拆分或补注：`SessionReconnector` 组件/loopback 已完成；产品级 lifecycle owner、Activity/FGS、sleep/wake/network reconnect 未完成。
2. 检查 HYP-M2-015/HYP-M6-006 中“docs match behavior”的范围，明确 manual pairing 与 app-level reconnect 不匹配。
3. 对 HYP-M3-011/012/013 添加“receiver core 完成，不等于用户交付路径完成”的注记，或拆出 file delivery row。
4. 对所有受影响 `[x]` 行使用 `[~]`/`[?]` 或拆分新 row，保留历史日志，不伪造已完成产品证据。

验收标准：

- tracker 中组件完成、产品接入、设备矩阵、外部门槛四类证据分开。
- 每个 `[x]` 行都能追到当前 DoD 证据；没有“测试文件存在 == 产品可用”的暗示。
- README/ADR/protocol/install docs 不再承诺当前 UI 无法完成的 manual pairing 或 continuous companion 行为，除非明确标为 v1 target/pre-alpha gap。

验证命令：

- `./scripts/check.sh`
- 人工 review：逐行检查 HYP-M2-013、HYP-M2-015、HYP-M3-011/012/013、HYP-M4-007、HYP-M6 release/readiness rows。

### P1-1 文件接收交付路径与持久 resume

目标：把 transfer receiver 从“校验后删除 temp file”升级为用户可用交付物。该项与传输类别重叠，本报告只记录生命周期依赖。

可能触达文件/模块：

- Android: `MainActivity.handleSessionEnvelope`, `dev.hyphen.android.transfer`, Storage Access Framework 或 app-private downloads handoff。
- macOS: `PairingController.handleSessionEnvelope`, `HyphenTransfer`, save panel/downloads handoff。
- docs/test plans: 1 GiB interrupt/resume, disk full, cancel, partial cleanup。

验收标准：

- 小文件和大文件成功接收后，用户能打开或保存；hash 校验结果可见。
- cancel、disk full、duplicate filename、process death 不留下不可解释状态。
- resume checkpoint 行为与 protocol doc 一致；如果仍是内存级，tracker 不标产品完成。

验证命令/手工证明：

- `cd apps/android && ./gradlew test assembleDebug`
- `cd apps/macos && swift test`
- 手工：双向小文件、1 GiB 文件、断线恢复、取消、重启后状态记录。

### P1-2 清理会误导生命周期判断的 stale comments/copy

目标：删除或更新“future TLS transport”“no transport until M2”“transport lands later”等已经不准确的注释/文案。

可能触达文件/模块：

- `apps/android/app/src/main/AndroidManifest.xml`
- `apps/macos/Sources/HyphenApp/main.swift`
- `apps/macos/Sources/HyphenDiscovery/BonjourAdvertiser.swift`
- `apps/macos/Sources/HyphenApp/PairingController.swift`
- `docs/project_hyphen_roadmap_tracker_v0_3.md`

验收标准：

- 注释描述当前事实：transport component exists；product lifecycle owner/wiring may still be pending。
- 搜索 `future TLS transport`, `no transport until M2`, `transport lands` 不再命中误导性产品路径。

验证命令：

- `rg -n "future TLS transport|no transport until M2|transport lands|arrives with M2" apps docs`
- `./scripts/check.sh`

### P2-1 设备矩阵和外部门槛证明

目标：把静态“可推断缺口”和真实环境行为分离。

验收标准：

- HYP-M4-007 的 20 次 sleep/wake 有原始日志。
- Android background/battery/restricted LAN 覆盖 Pixel/Samsung/OEM 或明确缩小支持矩阵。
- release/readiness docs 继续把 local green、device proof、external release gate 分开。

## 开放问题 / environment-only gates

- **实测（2026-06-18）**：Mac 重连 listener 复用配对时记录的端口；若 Mac 在进程重启后端口被占用或 DHCP 变更 Android 记住的 endpoint，仍需用户重新配对或 Bonjour 辅助发现。【来源：实测】
- **评估**：Android 仍未采用 always-connected FGS；当前策略是 reconnect-on-use + Activity 无关的进程内 supervisor，与 ADR-0001 “continuous companion” 文案仍有张力，需在发布前明确。【来源：评估】
- Android 产品策略：v1 是否仍要求 always-connected foreground companion，还是改为 conservative reconnect-on-use？这会影响 ADR-0001/0003、manifest、Play FGS declaration 和 README。
- Manual endpoint 的信任模型：无 QR fingerprint 时是否接受 TOFU + mandatory SAS？是否需要同时在 Mac UI 显示 inbound manual pairing 状态？
- remembered endpoint 存储位置：是否进入 trust store 记录，还是独立的 encrypted/keychain endpoint store？撤销时如何同步清 endpoint、resume token 和 transfer checkpoint？
- Device matrix：本次未运行 Android 设备、macOS sleep/wake、Wi-Fi 切换、Doze/OEM battery、restricted LAN 或 1 GiB 文件传输；这些结论只能标为 environment-only。
- Tracker 修改需要单独授权。本文件只给修复计划，没有直接修改 tracker。

**更新（2026-06-18）**：应安全架构全量实施请求，tracker 已同步 `HYP-M2-013/015`、`HYP-M2-016`、`HYP-M3-011/012/016` 与进度日志；历史 `[x]` 组件证据保留在进度日志中。

## 本次审计验证状态

- 已执行：静态文件核验、`rg` call-site 搜索、`nl -ba` 行号核验、repo status 确认；**2026-06-18 实施验证**：Android `./gradlew test assembleDebug`（272 tests green）、macOS `swift build && swift test`（171 tests green）、`rg` 确认误导性 lifecycle 注释已移除。
- 未执行：真机/模拟器配对、Activity recreate 手工证明、20× sleep/wake、Wi‑Fi 切换、1 GiB 传输、打包/签名/商店流程。
- 2026-06-18 实施轮次已落地 P0/P1 代码与 tracker/doc 修订；P2 设备矩阵仍 environment-only。
