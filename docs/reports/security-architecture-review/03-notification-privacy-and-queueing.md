# 03 通知隐私、源端过滤与队列审计

## 范围

本文件只审计 `docs/Project-Hyphen-security-architecture-review.md` 中与通知隐私、源端过滤、通知队列和相关威胁模型隐私承诺有关的 claim。源评审仅作为不可信输入；结论以当前仓库文件为准。

覆盖的源 finding：

- I. Privacy and Threat Model 中的通知相关 claim：诊断不含通知正文、通知内容是敏感资产、隐藏内容应在源端过滤、Mac 侧无持久通知历史。
- H-05：通知策略存在“先发完整 snapshot，后收到 policy”的竞态。
- M-04：critical notification queue 的 128 容量不是硬上限。
- “默认 snapshot/body 行为已有测试证明”的 claim。
- HYP-M3-005、HYP-M4-008、HYP-M6-005 相关 tracker/doc 漂移。

## 审计结论表

| Finding | 结论 | 核实结果 |
|---|---|---|
| Android 默认通知策略为 `SHOW_FULL`，outbox 绑定会立即重放 active snapshot | confirmed | 当前默认策略仍是 `SHOW_FULL`，`bindNotificationOutbox()` 立即调用 snapshot 提交路径；现有测试证明默认 active snapshot 含 `text`。 |
| H-05：Mac policy 到达/处理前，Android 可能先发送完整通知内容 | confirmed | 需缩窄：源端过滤已存在；风险不是“完全没有源端过滤”，而是 negotiated policy 生效前的 fail-open 窗口。 |
| “已协商 `privacyPolicy` 后隐藏内容永不跨 LAN”的威胁模型表述 | disputed | 过宽。只有在 Android 已应用 policy 或本地模式已预设为隐藏后才成立；仅“双方协商成功”不足以证明首批 snapshot 已被过滤。 |
| tests 证明默认 snapshot/body 行为 | confirmed | `connected runtime snapshots active payloads when outbox binds` 断言默认 snapshot payload 包含 `text`。 |
| source-side filtering / `hideBody` / `existsOnly` 已实现 | confirmed | Android send boundary 会按 policy strip body/title/actions；macOS receiver 也有本地 scrubber 作为 defense-in-depth。 |
| M-04：critical queue capacity 不是硬上限 | confirmed | critical submit 在队列满时只尝试丢 best-effort；若队列全是 critical，仍会 `addLast`，因此可超过 capacity。 |
| HYP-M4-008 / HYP-M6-005 可证明 queue hard limit | unsupported | 这两个 tracker/test-log 当前只证明 duplicate-prevention / coalescing invariant，不证明 dispatch queue 有硬容量上限。 |
| HYP-M3-005 完成状态覆盖当前源端 privacy 语义 | confirmed | 存在 tracker/doc drift：行级任务仍只写 hidden-body mode 和 manual blocker；进度日志补充了 source-side policy，但未记录 H-05 的首帧 fail-open 风险。 |
| 通知诊断不含正文、错误日志只保留受控 token | confirmed | 当前 structured log 只接受 code/component/operation token；exporter 输出这些字段和可选 traceId，不输出 envelope payload。 |
| Mac 无持久通知历史数据库 | confirmed | 当前 feed 注释和模型均为 bounded in-memory；代码扫描只发现通知设置写入 `UserDefaults`，未发现正文/历史持久化路径。 |
| 真实 paired device 下 hideBody/existsOnly 无内容泄漏 | environment-only | 需要真实 Android 通知、paired Mac session、Notification Center/线缆或日志取证；本次文件级审计不能完成。 |

## 证据记录

- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationPrivacy.kt:32`：`NotificationPrivacyPolicy.defaultMode` 默认为 `SHOW_FULL`。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationPrivacy.kt:39`：`apply()` 在 `HIDE_BODY` 删除 `text/replyActions`，在 `EXISTS_ONLY` 删除 `title/text/category/replyActions`。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycle.kt:51`：runtime 初始 `privacyPolicy = NotificationPrivacyPolicy()`，因此继承 `SHOW_FULL`。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycle.kt:102`：`bindNotificationOutbox()` 新建 sender 并套用当前 policy。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycle.kt:114`：绑定 outbox 后立即 `submitSnapshotsIfConnectedLocked()`。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycle.kt:225`：connected 状态下遍历 active payload 并提交 snapshot。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationMirrorEvents.kt:92`：发送前调用 `privacyPolicy.apply(payload)`，这是源端过滤边界。
- `apps/android/app/src/test/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycleTest.kt:199`：默认 outbox bind snapshot 测试。
- `apps/android/app/src/test/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycleTest.kt:212`：该测试断言默认 snapshot payload 包含 `active before bind`。
- `apps/android/app/src/test/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycleTest.kt:217`：hidden-body 模式下 rebind snapshot 不含 secret body。
- `apps/android/app/src/test/kotlin/dev/hyphen/android/notifications/NotificationMirrorEventSenderTest.kt:127`：`existsOnly` sender 测试断言 visible content 和 reply action 不上 wire。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:553`：Android 安装 active session 时绑定 notification outbox。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:564`：Android 在绑定 outbox 之后才 start session。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:640`：Android 收到 `notification.privacy.policy` 后才 parse/apply policy。
- `apps/macos/Sources/HyphenApp/PairingController.swift:496`：macOS `session.start(replaying:)`。
- `apps/macos/Sources/HyphenApp/PairingController.swift:499`：macOS session start 后才 `syncNotificationPrivacyPolicy()`。
- `apps/macos/Sources/HyphenApp/PairingController.swift:562`：policy 同步仅在 active session 且 negotiated `privacyPolicy` 为 true 时发送。
- `apps/macos/Sources/HyphenNotifications/NotificationMirrorReceiver.swift:298`：Mac policy sender 发送 `notification.privacy.policy` 且 `requiresAck: true`。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationDispatchQueue.kt` 不存在；当前 queue 定义在 `NotificationListenerLifecycle.kt`。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycle.kt:266`：dispatch queue 默认 capacity 为 128。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycle.kt:292`：critical 满载时只调用 `dropOldestBestEffortLocked()`。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycle.kt:295`：无论是否真的 drop，之后仍 `queue.addLast(...)`。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycle.kt:331`：若没有 best-effort 项，drop 函数直接返回且不减少队列。
- `apps/android/app/src/test/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycleTest.kt:113`：现有 queue 测试只覆盖“best-effort backlog 满时保留 critical removal”，未覆盖全 critical 满载。
- `docs/protocol/hyphen-protocol-v0.md:140`：协议写明 `privacyPolicy` 是 negotiated additive option。
- `docs/protocol/hyphen-protocol-v0.md:199`：Mac 可发送 policy，让 Android 源端过滤；但协议没有要求 snapshot 等待 policy ACK。
- `docs/protocol/threat-model.md:53`：当前威胁模型声称 negotiated policy 后 hidden content never leaves phone；该表述缺少“policy 已处理/首帧 gated”的前置条件。
- `docs/project_hyphen_roadmap_tracker_v0_3.md:174`：HYP-M3-005 顶层 acceptance 仍是 hidden-body mode leaks no body text。
- `docs/project_hyphen_roadmap_tracker_v0_3.md:199`：HYP-M4-008 只承诺 duplicate-prevention invariant 和 live matrix residue。
- `docs/project_hyphen_roadmap_tracker_v0_3.md:232`：HYP-M6-005 只承诺 duplicate prevention，live matrix 仍 open。
- `docs/project_hyphen_roadmap_tracker_v0_3.md:461`：进度日志记录了 source-side privacy 追加实现，同时明确真实设备 payload proof 仍未做。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/diagnostics/StructuredLog.kt:35`：structured log 只记录受控 code/component/operation。
- `apps/android/app/src/main/kotlin/dev/hyphen/android/diagnostics/RedactedDiagnosticsExporter.kt:33`：exporter 只导出 timestamp/level/category/code/attributes 和可选 traceId。
- `apps/macos/Sources/HyphenApp/Model/ActivityFeed.swift:11`：通知 feed 明确为 in-memory only。
- `apps/macos/Sources/HyphenApp/Model/HyphenAppModel.swift:52`：通知写入当前 app model feed，不是持久存储。

## 修复计划

### 实施清单（2026-06-18）

| 任务 | 状态 | 证据 |
|---|---|---|
| P0 消除首帧/重绑 fail-open 隐私窗口 | [x] 已实现 | `NotificationListenerLifecycle.kt` `requireRemotePrivacyPolicy` + `existsOnly` fail-closed；`ConnectionSupervisor` 传入 negotiated flag；policy 到达后 refresh |
| P0 removal 有界、去重、不丢失 | [x] 已实现 | removal 建模为运行时 `pendingRemovedKeys` 状态集合,由 dispatch worker 经 `requestRemovalSweep`/`drainPendingRemovals` 排空(集合天然去重、内存按活动通知数有界);best-effort 队列保留 drop-oldest + `droppedCount`,合并计数 `removalCoalescedCount` |
| P1 竞态与协议级测试 | [x] 已实现 | `NotificationListenerLifecycleTest` fail-closed bind/rebind/policy-refresh + removal-sweep/合并测试 |
| P2 真实设备证据 | [ ] 仍 blocked | environment-only；见开放问题 |

**代码审查跟进（2026-06-18，已修复）**：

- （评估）本地 `setNotificationPrivacyMode` 曾可绕过 fail-closed → 已改为 `privacyPolicyAwaitingRemote` 时 no-op。
- （评估）reconnect `bindNotificationOutbox` 曾重置已应用的 Mac policy → 已用 `remotePrivacyPolicyApplied` 保留。
- （阻断,已修复）队列 `ArrayList.addLast`（SequencedCollection,API 35+）在 minSdk 26 无 desugaring 下会 `NoSuchMethodError`,崩溃所有 Android 8–14 设备的通知分发;宿主 JDK 单测无法暴露 → 改用 `ArrayList.add`。
- （重构,治本）removal 原本既是 `pendingRemovedKeys` 状态、又是队列中的 critical task(双事实来源),其 `onEvicted` 重试在 worker 阻塞、队列被异 key removal 占满时会同步递归重入 → StackOverflow。改为单一事实来源:removal 只存于集合,worker 经 coalesced sweep 排空,彻底消除 eviction/onEvicted/递归,并顺带消解“满载保留策略”这个开放问题。

### P0：消除首帧/重绑 fail-open 隐私窗口

可能触及文件：

- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationPrivacy.kt`
- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycle.kt`
- `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt`
- `apps/android/app/src/test/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycleTest.kt`
- `apps/android/app/src/test/kotlin/dev/hyphen/android/notifications/NotificationMirrorEventSenderTest.kt`
- `docs/protocol/hyphen-protocol-v0.md`
- `docs/protocol/threat-model.md`
- `docs/project_hyphen_roadmap_tracker_v0_3.md`

修复方向：

- 对已协商 `notifications.v1.privacyPolicy` 的 session，在 Android 处理 policy 前不得发送 full active snapshot。
- 最小安全策略可以是：policy 未到达时 snapshot 按 `existsOnly` 发送，或暂存 snapshot 直到 `notification.privacy.policy` 被处理；二者必须有明确协议/UX 语义。
- 如果选择暂存，必须处理 policy 丢失/peer 断开/legacy peer 场景，避免通知管线永久卡住。
- Mac 侧 `requiresAck` 只能证明 policy envelope 被传输层确认；验收应以 Android 已执行 `setNotificationPrivacyPolicy(...)` 后才允许 full/hideBody 语义为准。

验收标准：

- 新连接时，active notification 的第一批 `notification.posted` 不包含用户选择隐藏的 `text/title/replyActions`。
- outbox rebind、session reconnect、policy 更新三种路径均无 full snapshot 先发。
- policy 未协商或 malformed 时有确定行为：拒绝 policy 并报 `plugin/unsupported-capability` / `protocol/invalid-envelope`，且不会因默认 full 泄露已声明隐藏的内容。
- threat model 的 “hidden content never leaves phone” 改为带条件表述：已处理 policy 或 fail-closed fallback 生效后成立。

建议验证：

- `cd apps/android && ./gradlew :app:testDebugUnitTest --tests dev.hyphen.android.notifications.NotificationListenerLifecycleTest --tests dev.hyphen.android.notifications.NotificationMirrorEventSenderTest --tests dev.hyphen.android.notifications.NotificationPrivacyPolicyHandlerTest`
- `cd apps/android && ./gradlew test assembleDebug`
- `cd apps/macos && swift test --filter NotificationMirrorReceiverTests`
- `cd apps/macos && swift test --filter NegotiatedCapabilitiesTests`
- `./scripts/test-protocol.sh`
- `./scripts/check.sh --strict`

手工证明：

- 准备一台 Android 设备，在连接前保留含明显 secret 字符串的 active notification。
- 连接到 Mac，默认策略设为 `existsOnly` 或目标 app 设为 `hideBody`。
- 捕获 Android sender test outbox、session debug envelope，或受控 LAN 流量解码前的应用层日志，证明首批 posted/updated payload 不含 secret 字符串、title 或 reply label。
- Mac Notification Center 截图/录屏证明 full、hideBody、existsOnly 三档显示符合策略。

### P0：给 critical removal queue 加硬上限并按 key 合并

可能触及文件：

- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycle.kt`
- `apps/android/app/src/test/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycleTest.kt`
- `docs/project_hyphen_roadmap_tracker_v0_3.md`

修复方向：

- queue size 必须在所有任务类型下保持 `<= capacity`。
- critical removal 不能无限逐事件排队；应按 `sbnKey` 合并最终 removed 状态，或使用 bounded map + worker drain。
- 满载时不得沉默增长；应记录 redacted diagnostic counter，例如 dropped/coalesced/removal-overflow，不包含通知正文。

验收标准：

- 全 critical 队列在 worker 阻塞时提交 `capacity * 2` 个 removal，内存队列仍不超过 capacity。
- 同一 `sbnKey` 的重复 removal 合并为一个最终状态。
- 不同 key 的 removal 在容量压力下有确定策略：保留最近状态、记录 coalesced/dropped 计数，不抛出 caller-blocking 异常。

建议验证：

- `cd apps/android && ./gradlew :app:testDebugUnitTest --tests dev.hyphen.android.notifications.NotificationListenerLifecycleTest`
- `cd apps/android && ./gradlew test assembleDebug`
- `./scripts/check.sh --strict`

### P1：补齐竞态和协议级测试

可能触及文件：

- `apps/android/app/src/test/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycleTest.kt`
- `apps/android/app/src/test/kotlin/dev/hyphen/android/transport/SessionReconnectTest.kt`
- `apps/macos/Tests/HyphenNotificationsTests/NotificationMirrorReceiverTests.swift`
- `apps/macos/Tests/HyphenTransportTests/NegotiatedCapabilitiesTests.swift`
- `protocol/test-vectors/`

修复方向：

- 增加“连接建立第一帧”“policy envelope 先/后于 snapshot”“outbox rebind”“旧 active notifications”测试。
- 增加 malformed policy、privacyPolicy 未协商、legacy peer 的负例测试。
- 协议文档补充首帧 gating 或 fail-closed fallback 规则，避免实现和威胁模型再次漂移。

验收标准：

- 自动化测试能复现旧风险：默认 full snapshot 在 policy 前发送。
- 修复后同一测试断言 first posted payload 已被 existsOnly scrub 或未发送。
- `HYP-M3-005` 的 tracker 状态能区分 automated source-side proof 与 manual device proof。

### P2：真实设备和发布前证据

可能触及文件/材料：

- `docs/test-plans/` 下新增或更新通知隐私矩阵记录。
- `docs/project_hyphen_roadmap_tracker_v0_3.md`
- 设备截图/录屏/测试日志附件路径。

验收标准：

- 至少一台 Android 设备 + 一台 Mac 的真实 paired session 证明 hideBody/existsOnly 首批 snapshot 无泄漏。
- 10-app live Notification Center matrix 记录 app、Android 版本、Mac 版本、策略、结果、失败原因。
- beta/release 文案只声称已经被真实证据覆盖的隐私行为。

## 开放问题与环境门槛

- environment-only：真实 NotificationListener 行为、OS Notification Center 展示、paired-device 首帧顺序和 10-app matrix 需要设备/权限/录屏证据。
- environment-only：当前仓库自动测试可证明 sender/receiver shape，但不能证明 OEM notification listener delivery 顺序。
- open question：对 legacy peer 或未协商 `privacyPolicy` 的 session，产品是否接受 Android 端默认 `existsOnly` 的保守降级，还是只承诺 Mac-side scrubber。
- open question：policy ACK 语义是否需要应用层确认，而不只依赖 transport ack。
- open question：critical queue 满载时不同 `sbnKey` removal 的保留策略应偏向“最新 N 个 key”还是“所有已知 active key 的最终状态 map”。

## 本次审计未运行的命令

为遵守本子任务“只创建/更新本文件”的 ownership，本次没有运行 Gradle/Swift 测试，避免生成 build/cache 输出。上面的命令是修复落地后的验收清单。
