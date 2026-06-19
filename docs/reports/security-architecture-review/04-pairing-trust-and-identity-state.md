# 04 配对、信任与身份状态审计

## 范围

本文件只覆盖配对、信任持久化、身份绑定、provisional pairing state、resume token 的信任/会话失效清理，以及 Android trust store 的事务性。源输入 `docs/Project-Hyphen-security-architecture-review.md` 仅作为未可信线索；以下结论以当前仓库文件为准。

判定标签：`confirmed` 表示当前代码/文档证实；`unsupported` 表示当前证据不足；`disputed` 表示源说法与当前代码不符或过宽；`environment-only` 表示需要真机、并发运行形态或外部环境证明。

## 覆盖的源报告结论

- E Security 中与配对/信任状态相关的正向基础：SPKI pin、Android AES-GCM trust store、SAS transcript/test vectors。
- M-01：配对没有等待协议级双端 confirm 后再持久化 trust。
- M-02：macOS provisional fingerprint 槽在 connection attach 前可被覆盖。
- M-03：resume token 的过期、撤销和 trust reset 清理。
- M-07：Android 加密 trust 文件的锁只保护单个 store 实例。
- Tracker/文档漂移：HYP-M2-011、协议配对序列、troubleshooting 中 trust reset/resume token 的表述。

## 审计结论表

| 源主张 | 判定 | 当前结论 | 修复优先级 |
|---|---|---|---|
| E Security：身份基于 SPKI pin，trust data 使用 AES-256-GCM，SAS transcript 绑定 nonce/双方指纹/版本 | confirmed | 这些基础控制存在，源报告的正向描述基本成立。它们降低 MITM 风险，但不等于协议级双端 confirm 已接入。 | 保持 |
| M-01：一端本地确认即可写 trust，未等待协议 `pair.confirm accepted` | confirmed | 协议要求 `pair.request/challenge/response/confirm` 且双方确认后才落盘；当前产品路径是双方各自本地 UI 确认后直接写本地 trust。 | P0 |
| M-02：macOS provisional fingerprint 在 attach 前可被覆盖 | confirmed | 当前 `claimFingerprint` 在 `connectionID == nil` 时允许第二次 claim 覆盖旧 fingerprint；单元测试也把该行为作为“retry reclaim”固定下来。 | P0 |
| M-03：expired resume token 会被继续接受 | disputed | token 在 redeem 时会校验 TTL 并拒绝过期 token；不能说“过期 token 可继续建立会话”。 | 无 |
| M-03：未兑换的过期 token 没有全局 purge | confirmed | store 只有 `issue`/`redeem`/`invalidatePeer`/`liveCount`，没有主动 purge；未被 redeem 的过期 entry 会留在内存表。 | P1 |
| M-03：trust revoke/reset 未统一失效 responder-side resume tokens | confirmed | macOS 可见 forget/reset 路径停止 active session，但没有调用 `ResumeTokenStore.invalidatePeer`；`tokenStore` 是 `PairingController` 私有对象。 | P1 |
| M-03：Android revoke/reset 未统一调用 `invalidatePeer` | disputed | 当前 Android 产品路径主要作为 initiator，trust reset 会清空当前 client-side `resumeToken`/`lastSessionId` 并停止 session；Android responder token store 目前只在组件/测试路径出现。若未来 Android server/FGS 接入，该风险需要重新拉入生产路径。 | P1 条件项 |
| M-07：Android trust store 锁只保护单实例，跨实例 load-mutate-persist 可能丢更新 | confirmed | `EncryptedFilePeerTrustStore` 使用实例内 `lock`；`AndroidTrustStores.openDefault()` 每次构造新 store。原子 move 保护单次写入，不保护跨实例读改写事务。 | P1 |
| M-07：当前生产必然存在两个并发 writer 可复现 revoke 被写回 | environment-only | 源报告的未来 FGS/管理 UI 场景合理，但当前仓库没有证明生产已存在两个并发 store writer。需要并发调用图或真机/集成复现。 | P1/P2 |
| HYP-M2-011 `[x]` 与协议级双端确认一致 | confirmed | tracker 对“UI 两端存在/本地确认后落盘”基本成立，但对协议级 remote accepted confirm 来说过度完成。应拆分本地 SAS UI 与协议 confirm。 | P2 |
| troubleshooting “trust reset 后旧 resume token/session 应被丢弃”已由代码完整保证 | disputed | 文档表达的是正确安全目标；Android 当前 client token 会清掉，macOS responder token store 没有随 trust reset invalidate，因此“已完整保证”不成立。 | P1/P2 |

## 证据笔记

### 正向安全基础

- Android `PinnedTrustManager` 明确用 SPKI fingerprint 回调替代 CA/hostname 信任；未知 pin 抛 `CertificateException`：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/PinnedTrustManager.kt:7`、`:25`、`:33`。
- macOS `SPKIPinVerifier` 从 leaf certificate 提取 SPKI fingerprint 并调用 `isTrusted`，注释明确 discovery/CA 不是 trust：`apps/macos/Sources/HyphenTransport/SPKIPinVerifier.swift:4`、`:24`、`:30`。
- Android trust cipher 是 AES/GCM/NoPadding，provider 生成 IV，解密失败抛异常：`apps/android/app/src/main/kotlin/dev/hyphen/android/trust/TrustCipher.kt:16`、`:30`、`:38`。
- Android production trust key 走 Android Keystore，store 文件在 app-private `filesDir`；Manifest `allowBackup=false`：`apps/android/app/src/main/kotlin/dev/hyphen/android/trust/AndroidTrustStores.kt:11`、`:32`；`apps/android/app/src/main/AndroidManifest.xml:18`。
- 双端 SAS transcript 都按 `label || nonce || macSpkiFp || androidSpkiFp || protocolVersion` 计算，并保持 6 位补零：`apps/android/app/src/main/kotlin/dev/hyphen/android/pairing/PairingTranscript.kt:5`、`:46`、`:60`；`apps/macos/Sources/HyphenCore/PairingTranscript.swift:4`、`:37`、`:51`。向量脚本校验 tamper 与 leading-zero：`scripts/verify_pairing_vectors.py:25`、`:50`、`:57`。

### M-01：协议级双端 confirm 未接入

- 协议文档定义 `pair.request/challenge/response/confirm`，并写明双方确认后才 persist trust：`docs/protocol/hyphen-protocol-v0.md:91`、`:96`、`:100`、`:112`。
- Android 当前路径：扫描 QR 后建立 provisional TLS、构造 `SasConfirmationGate`，点击确认即 `gate.confirm()` 并启动 steady session：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:428`、`:454`、`:479`、`:483`。`SasConfirmationGate.confirm()` 直接 `trustStore.add(...)`：`apps/android/app/src/main/kotlin/dev/hyphen/android/pairing/SasConfirmationGate.kt:32`、`:37`、`:39`。
- macOS 当前路径：接入 provisional connection 后展示 SAS，点击确认后 `gate.confirm()`、取走 connection 并启动 session：`apps/macos/Sources/HyphenApp/PairingController.swift:238`、`:256`、`:273`、`:279`、`:288`。macOS gate 也在 confirm 内写 trust store：`apps/macos/Sources/HyphenCore/SasConfirmationGate.swift:42`、`:52`。
- 当前 app 源码没有 `pair.request` / `pair.challenge` / `pair.response` / `pair.confirm` 的 wire handler；`rg` 只在 transcript 注释等非状态机位置命中。因此源结论“缺协议级 dual confirm”成立，但应窄化为“不是无用户交互 MITM”，因为 QR pin + SAS 仍存在。

### M-02：macOS provisional fingerprint 与连接身份绑定不足

- TLS verify block 与 ready connection callback 是分离阶段：verify block 只调用 verifier，connection 之后在 `.ready` 才交给 `onConnection`：`apps/macos/Sources/HyphenTransport/TLSEndpoint.swift:33`、`:100`、`:104`。
- `ProvisionalPairingState.claimFingerprint` 在没有 attached connection 时允许覆盖 pending fingerprint；`attachConnection` 随后取当前 pending fingerprint：`apps/macos/Sources/HyphenCore/ProvisionalPairingState.swift:29`、`:34`、`:43`。
- 测试 `testSecondClaimBeforeAttachReclaimsTheSlot` 明确断言第二次 claim 覆盖第一次：`apps/macos/Tests/HyphenCoreTests/ProvisionalPairingStateTests.swift:19`、`:23`、`:25`。
- `PairingController` 用 lock 串行化访问，但没有把 fingerprint 与特定 `NWConnection` 原子绑定：`apps/macos/Sources/HyphenApp/PairingController.swift:343`、`:349`、`:352`。

### M-03：resume token 清理与 trust reset

- 双端 `ResumeTokenStore` 都是内存 map；redeem 会删除被提交 token 并拒绝过期/错 peer，`invalidatePeer` 只按 peer 删除：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/ResumeTokenStore.kt:6`、`:31`、`:47`、`:49`、`:56`；`apps/macos/Sources/HyphenTransport/ResumeTokenStore.swift:4`、`:25`、`:58`、`:62`、`:68`。
- responder handshake 在成功/新 session 时 issue 新 token，presented token 仅在 handshake redeem：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionHandshake.kt:181`、`:193`、`:196`；`apps/macos/Sources/HyphenTransport/SessionHandshake.swift:203`、`:227`、`:232`。
- Android trust forget/reset 会 stop 当前 session，并清空 `resumeToken` 和 `lastSessionId`：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:348`、`:372`、`:384`、`:387`。
- macOS `PairingController` 持有私有 `tokenStore`：`apps/macos/Sources/HyphenApp/PairingController.swift:32`、`:36`。macOS forget/reset 路径只 remove trust 并调用 `pairingController?.endPairing()`：`apps/macos/Sources/HyphenApp/main.swift:642`、`:643`、`:665`、`:666`；`endPairing()` 停 listener/active session，但未 invalidate token store：`apps/macos/Sources/HyphenApp/PairingController.swift:173`、`:176`。
- Troubleshooting 文档声明 trust reset 后旧 resume tokens/sessions 应丢弃：`docs/troubleshooting_en.md:104`、`:110`；`docs/troubleshooting_zh.md:90`、`:95`。这是正确目标，但当前 macOS responder-side token invalidation 证据不足。

### M-07：Android trust store 跨实例事务性

- `EncryptedFilePeerTrustStore` 的 `lock` 是实例字段；`add/remove` 在该锁内执行 load-mutate-persist：`apps/android/app/src/main/kotlin/dev/hyphen/android/trust/EncryptedFilePeerTrustStore.kt:30`、`:40`、`:42`、`:58`。
- persist 使用临时文件与 `ATOMIC_MOVE`，保证单次写入原子性，但不是跨实例 compare-and-swap：`apps/android/app/src/main/kotlin/dev/hyphen/android/trust/EncryptedFilePeerTrustStore.kt:118`、`:129`、`:131`。
- `AndroidTrustStores.openDefault()` 每次返回新的 `EncryptedFilePeerTrustStore`：`apps/android/app/src/main/kotlin/dev/hyphen/android/trust/AndroidTrustStores.kt:32`。
- 现有测试覆盖跨实例持久化读取，但没有两个实例并发 barrier/lost-update 测试：`apps/android/app/src/test/kotlin/dev/hyphen/android/trust/EncryptedFilePeerTrustStoreTest.kt:95`、`:97`。

### Tracker/文档漂移

- HYP-M2-011 目前为 `[x]`，验收文字是“User confirms matching code before trust is stored”，并保留 live two-device SAS drill 手工残留：`docs/project_hyphen_roadmap_tracker_v0_3.md:158`。这覆盖本地 UI gate，不覆盖协议文档要求的 wire-level remote confirm。
- HYP-M2-015 为 `[x]`，但协议配对序列仍写 `pair.*` 状态机，而产品代码未实现该状态机：`docs/project_hyphen_roadmap_tracker_v0_3.md:162`；`docs/protocol/hyphen-protocol-v0.md:96`。
- 2026-06-17 进展日志说明修过 pairing retry wedge、provisional release、隐私策略等，但仍把 real paired-device pairing-retry drill 列为未执行设备证据：`docs/project_hyphen_roadmap_tracker_v0_3.md:461`。

## 修复计划

### P0：使配对提交状态与身份绑定可证明

1. 实现协议级双端配对提交，或用 ADR 明确修改 v0 协议。
   - 可能触及：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt`、`apps/android/app/src/main/kotlin/dev/hyphen/android/pairing/`、`apps/macos/Sources/HyphenApp/PairingController.swift`、`apps/macos/Sources/HyphenCore/SasConfirmationGate.swift`、`docs/protocol/hyphen-protocol-v0.md`、`protocol/test-vectors/pairing/`。
   - 接受标准：任一端 reject、断线或未收到 remote accepted confirm 时，两端 trust store 都不新增 peer；只有本地确认和远端 accepted confirm 都完成后才落盘并启动 steady session。
   - 验证：新增 Android/macOS pairing state-machine unit tests；新增 loopback/protocol test 覆盖 accept/reject/disconnect；运行 `./scripts/test-protocol.sh`、`cd apps/android && ./gradlew testDebugUnitTest --tests 'dev.hyphen.android.pairing.*'`、`cd apps/macos && swift test --filter SasConfirmationGateTests`、`./scripts/check.sh --strict`。
   - 手工证据：真 Android + Mac 扫 QR，分别执行双方确认、一端拒绝、确认前断网、确认后重连，记录 trust store/UI/session 状态。

2. 修复 macOS provisional fingerprint 绑定。
   - 可能触及：`apps/macos/Sources/HyphenCore/ProvisionalPairingState.swift`、`apps/macos/Sources/HyphenApp/PairingController.swift`、必要时 `apps/macos/Sources/HyphenTransport/TLSEndpoint.swift`。
   - 推荐方向：不要让第二个 pre-attach claim 覆盖第一个；引入一次性 attempt/claim id、pending 超时或 listener 串行化策略，保证 attach 只能消费同一个 claim。若 Network.framework verify callback 无法拿到 connection identity，则采用“一个 pending claim + 超时释放”而不是覆盖。
   - 接受标准：`testSecondClaimBeforeAttachReclaimsTheSlot` 改为拒绝/排队第二 claim；新增测试证明 A verify 后 B verify 不能让 A attach B fingerprint；dropped pre-SAS attempt 仍能在超时或明确失败后重试。
   - 验证：`cd apps/macos && swift test --filter ProvisionalPairingStateTests`，并补一个 `PairingController` 层测试或注入式模拟覆盖 verify/attach 乱序。

### P1：补齐 token/session cleanup 与 Android trust store 事务性

1. 给 resume token store 增加 purge 与 trust lifecycle API。
   - 可能触及：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/ResumeTokenStore.kt`、`apps/macos/Sources/HyphenTransport/ResumeTokenStore.swift`、两端对应 tests。
   - 接受标准：未兑换过期 token 可由 `purgeExpired()` 或 `issue/redeem/liveCount` 前置清理移除；测试覆盖 expired-unredeemed 不再长期计入 live set。
   - 验证：`cd apps/android && ./gradlew testDebugUnitTest --tests 'dev.hyphen.android.transport.ResumeTokenStoreTest'`；`cd apps/macos && swift test --filter SessionReconnectTests`。

2. 把 trust forget/reset 与 responder-side token invalidation 串起来。
   - 可能触及：`apps/macos/Sources/HyphenApp/PairingController.swift`、`apps/macos/Sources/HyphenApp/main.swift`，以及未来 Android server/supervisor 接入点。
   - 接受标准：forget 单 peer 调用 `invalidatePeer(peerFingerprint)` 并 stop active session；reset all 调用新增 `invalidateAll()` 或逐 peer invalidation；Android 当前 client-side token 清理保持，若引入 responder token store 必须同一 trust lifecycle owner 管理。
   - 验证：新增 macOS test 覆盖 remove trust 后旧 token redeem 失败；真机手工执行 trust reset 后旧 session 无法 resume，必须重新配对。

3. 修复 Android encrypted trust store 跨实例事务性。
   - 可能触及：`apps/android/app/src/main/kotlin/dev/hyphen/android/trust/AndroidTrustStores.kt`、`EncryptedFilePeerTrustStore.kt`、`EncryptedFilePeerTrustStoreTest.kt`。
   - 推荐方向：进程内按 canonical file path 共享单例/共享锁；或者增加文件锁 + version/CAS 事务，确保跨实例 load-mutate-persist 不丢更新。保持 zero-dependency 和 tamper-detect 行为。
   - 接受标准：两个 store 实例并发 add/remove 不丢 peer；forget/revoke 与 add 竞态下，已撤销 fingerprint 不会被旧快照写回；tamper/wrong-key 仍抛 `CorruptStore`。
   - 验证：新增 deterministic barrier test；运行 `cd apps/android && ./gradlew testDebugUnitTest --tests 'dev.hyphen.android.trust.EncryptedFilePeerTrustStoreTest'` 和 `./scripts/check.sh --strict`。

### P2：纠正文档/tracker 与设备证据

1. 在行为落地后拆分 tracker 状态。
   - 可能触及：`docs/project_hyphen_roadmap_tracker_v0_3.md`、`docs/protocol/hyphen-protocol-v0.md`、`docs/troubleshooting_en.md`、`docs/troubleshooting_zh.md`。
   - 接受标准：HYP-M2-011 不再把“本地 SAS UI gate”与“协议级双端 accepted confirm”混成同一完成状态；troubleshooting 的 trust reset/resume token 语句与代码 owner/API 一致。
   - 验证：文档 diff 逐条引用已落地代码/tests；`./scripts/check.sh --strict`。

2. 补真实配对与并发证据。
   - 接受标准：至少一组真 Android + Mac 记录 pairing accept/reject/reset/resume 行为；Android trust store 并发风险用自动测试覆盖，生产并发 reachability 用调用图或 runtime proof 说明。
   - 手工 proof：保存命令、设备、OS、构建版本、操作步骤、pass/fail，不把本地绿色测试等同于真机 release evidence。

## Open Questions / Environment-Only Gates

- v0 是否必须实现文档中的 `pair.request/challenge/response/confirm`，还是接受当前“QR pin + SAS + 本地确认”的简化协议？若选择后者，需要 ADR 和协议文档更新，不能只改 tracker。
- Network.framework 的 TLS verify callback 不能直接暴露 `NWConnection` identity；macOS provisional 修复需要在“拒绝第二 pending claim + timeout”与更深 transport 改造之间做取舍。
- Android 当前没有生产级 app-scoped responder/server owner；M-03 Android responder invalidation 与 M-07 多 writer 场景在引入 FGS、server 或独立设备管理入口前仍是条件风险。
- 真机配对、trust reset 后 reconnect、旧 resume token 失效、以及 Android trust store 多入口并发，都需要设备/运行时证据；本次审计只做静态仓库核验，没有运行测试或真机操作。
