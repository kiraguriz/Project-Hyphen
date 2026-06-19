# 02. 文件传输生命周期、正确性、性能与资源边界

## 范围

本分类只覆盖 `docs/Project-Hyphen-security-architecture-review.md` 中与文件传输生命周期、传输正确性、性能、资源限制和相关 tracker/doc 漂移有关的声明。源报告只作为未采信输入；下面结论均按当前仓库文件复核。

不覆盖配对确认、通知隐私、打包、TLS 版本策略等其他分类，除非它们直接影响传输状态。

## 覆盖的源报告发现

- C. Efficiency 中关于流式发送、manifest 上限、接收端线性扫描、状态表无回收、发送前整文件 SHA-256 的传输声明。
- D. Performance 中关于 queued progress、内存 checkpoint、缺少 1 GiB/真机/Doze/sleep-wake 性能证据的传输声明。
- H-02：成功接收的文件立即被删除。
- H-03：取消按钮不停止原始发送器。
- H-04：百万分块 O(n^2) 与无聚合配额。
- M-03：resume/checkpoint 仅内存；token 清理只在影响传输状态的范围内记录。
- M-06：progress 是 queued/sent 而不是 ACK/verified；registered/completed 表不回收。
- L-01：Android 每帧 flush 对传输吞吐的影响。
- HYP-M3-011/012/014/015 与 HYP-M6-004 的传输相关 tracker/doc 漂移。

## 审计结论表

| 源声明 | 判定 | 复核结论 |
|---|---|---|
| 发送端使用流式 source、最多 8 个未 ACK chunk，manifest 限制 1 GiB、1 KiB..2 MiB、最多 1,048,576 chunk | confirmed | 当前 Android/macOS 代码确实流式读取并用默认 `outstandingWindow = 8`；manifest 初始化校验了大小、chunk size、chunk count 和交叉关系。 |
| 发送前会完整读取一次 source 计算 SHA-256，导致大文件首包延迟接近一次完整磁盘读取 | confirmed | 两端 `TransferManifest` 都通过 `source.sha256Hex()` 生成整文件 hash；这是完整性设计代价，不等同于一次性把文件载入内存。 |
| 接收端每块后 `progress()` 线性扫描，完成检查也全量扫描，极端 1,048,576 chunk 可退化为 O(n^2) | confirmed | Android 与 macOS 的 `progress/checkpoint/fileIfComplete` 都从数组头扫描；每个 chunk 后调用 progress 和完成检查。 |
| `registered`、`completed`、多 `fileId` receiver state 没有容量上限或生命周期回收 | confirmed | 发送端 `registered` 和接收端 `completed/states` 都是内存 map，完成后仍保留 outbound registry 与 receiver completed checkpoint；没有总量、TTL 或 LRU。 |
| 成功接收后 App 删除临时文件，用户没有可打开/保存/定位的接收文件 | confirmed | Android/macOS App 层在 Completed 后删除 temp 文件，只留下状态/活动记录。 |
| “取消”会新建一个 sender 发送 `transfer.cancel`，不停止原始 `activeTransferSender` | confirmed | Android/macOS UI 取消路径都构造新 `TransferSender(...).sendCancel(...)`；原 sender 的 stream、outstanding、ACK pump 没有被 cancel API 终止。 |
| 发送进度显示的是 queued/sent chunk，而不是 ACK 或 receiver verified 进度 | confirmed | 发送端在 `outbox.send(...)` 后立即 `onProgress(index + 1)`；ACK 只释放窗口继续 pump。协议文档也说 progress 是本地 UI state，不是 wire message。 |
| receiver progress/checkpoint 是 highest-contiguous，而非任意已收到 chunk 数 | confirmed | 当前实现只从 0 连续扫描；乱序收到后不会按总 received count 增长。这一点对 resume 是保守正确的，但对“总接收进度”会低估。 |
| resume checkpoint 只在内存中，进程死亡后失效 | confirmed | transfer receiver state 与 sender registry 都是对象内存 map；协议文档明确 persistent checkpoint 是 post-v0 hardening。 |
| 完成后删除接收文件但 `completed` map 仍可报告完成 checkpoint | confirmed | receiver 在完成后保存 `completed[fileId]`，App 层随后删除 temp 文件；后续 resume request 仍可得到 `nextChunkIndex == chunkCount`。 |
| resume token 无全局过期清理 | confirmed | Android/macOS token store 只在 issue/redeem/invalidatePeer 时改变 map；没有周期 purge。对本分类的影响是：传输 resume 依赖 session 恢复时，过期未兑换 token 会暂留内存。 |
| token 清理问题本身属于传输状态表问题 | disputed | token store 是 transport/session 层，不是 transfer checkpoint；应作为跨模块清理/撤销约束处理，不能把 token TTL purge 与传输 completed/registered 回收混为一个实现点。 |
| 每帧强制 flush 会影响传输 throughput | confirmed | Android `FrameIO.write` 每个 frame 后 `flush()`；transfer chunk 经 `ProtocolSessionTransferOutbox -> ProtocolSession.send -> FrameIO.write` 发送。macOS 使用 `NWConnection.send`，源报告没有证明同样的 flush 问题存在于 macOS。 |
| 1 GiB、进程死亡、Doze/OEM battery、20 次 sleep/wake 等真实性能/恢复数据不存在 | environment-only | 当前仓库有手工测试计划和 compatibility matrix 模板；矩阵多为 `not-run/blocked`，不能从代码静态复核推断真实性能。 |
| HYP-M3-011 `[x]` 已证明产品级文件接收可交付 | disputed | HYP-M3-011 当前行和进度日志只证明 streaming sender/receiver core、小/空文件重建、坏 chunk/文件 hash 拒绝；App 层删除完成文件，所以不能扩展为“用户可交付文件接收”。 |
| HYP-M3-012 `[x]` 意味着 durable restart resume 已完成 | disputed | Tracker/协议均明确当前是 sender registry + receiver checkpoint 的 v0 wire path，checkpoint 仍在内存中，durable restart resume 属后续 hardening。 |
| HYP-M3-014 的 progress/cancel UI 已完全满足产品取消语义 | confirmed | Tracker 状态仍为 `[?]`，但进度日志称支持 cancel；代码显示 UI cancel 不停止原 sender，因此“cancel 产品语义”未满足。 |
| HYP-M3-015 与 HYP-M6-004 没有最终真实 1 GiB 多组合证据 | environment-only | Tracker 已标 `[?]` 并写明真实 paired-device 1 GiB interruption/resume run、三组 Android/macOS/network 组合仍 blocked；这不是代码可静态关闭的项。 |

## 证据记录

### 传输数据模型与边界

- Android `TransferProtocol` 定义 `MIN_CHUNK_SIZE_BYTES = 1024`、`MAX_CHUNK_SIZE_BYTES = 2 * 1024 * 1024`、`MAX_V0_TRANSFER_SIZE_BYTES = 1_073_741_824L`、`MAX_V0_TRANSFER_CHUNK_COUNT = 1_048_576`：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:15`。
- Android manifest init 校验文件名、大小、hash、chunk size、chunk count 和 `chunkCount == ceil(size/chunkSize)`：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:39`。
- Android `TransferManifest.fromSource(...)` 调用 `source.sha256Hex()` 后才构造 manifest：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:79`。
- Android `TransferByteSource.sha256Hex()` 通过 `openStream().use { sha256Hex(it) }` 流式计算 hash：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:117`。
- macOS 同样定义 1 KiB、2 MiB、1 GiB、1,048,576 chunk 上限：`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:5`。
- macOS manifest 初始化调用 `source.sha256Hex()`：`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:81`。

### sender lifecycle、progress 与 cancel

- Android `TransferSender` 默认 `outstandingWindow = 8`，`registered` 与 `activeSends` 是内存 map：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:320`、`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:348`。
- Android `sendSource(...)` 把 source 注册到 `registered`，发送 manifest，随后 `sendRemaining(...)`：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:351`。
- Android `handleAck(...)` 只移除 outstanding message 并继续 `pumpChunks(...)`：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:402`。
- Android `pumpChunks(...)` 每次 `outbox.send(...)` 后立即 `onProgress(... index + 1)`，所以发送进度表示 queued/sent，不是 ACK：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:411`。
- Android sender 完成后可能移除 `activeSends`，但没有移除 `registered`：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:431`。
- Android `sendCancel(...)` 只是发消息，没有停止本实例内的 active send：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:455`。
- Android Activity 持有 `activeTransferSender`：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:96`；安装 session 时写入：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:192`；ACK 回调转发给该 sender：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:524`。
- Android `cancelActiveTransfer()` 却新建 `TransferSender(ProtocolSessionTransferOutbox(session)).sendCancel(...)`，没有调用原 `activeTransferSender`：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:878`。
- macOS sender 同样有 `registered`/`activeSends`、ACK pump 和发送后立即 progress：`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:389`、`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:499`、`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:539`。
- macOS `cancelActiveTransfer()` 同样新建 sender 发 cancel：`apps/macos/Sources/HyphenApp/PairingController.swift:880`。

### receiver lifecycle、completion 与 resume

- Android `TransferReceiver` 的 `states` 与 `completed` 都是内存 map；`checkpoint(...)` 从 active state 或 completed state 返回：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:477`。
- Android 收到 manifest 时 `storage.prepare(manifest)` 后写入 `states`，无全局并发数、总字节或总 fileId 限制：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:489`。
- Android 每个 chunk 后 `state.accept(chunk)`、`onProgress(state.progress())`、`completeIfReady(state)`：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:509`。
- Android 完成后从 `states` 移除但写入 `completed[fileId]`：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:536`。
- Android `TransferState.fileIfComplete()` 全量扫描 `received`；`checkpoint()` 与 `progress()` 从 index 0 线性扫描：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:574`、`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:581`、`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:587`。
- Android App 在 Completed 后删除 `event.completed.file`，只记录活动事件：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:661`。
- macOS receiver 也用内存 `states`/`completed`：`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:584`。
- macOS 完成后保存 `completed[fileId]`，`completedCheckpoint(...)` 可继续返回完成 checkpoint：`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:656`、`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:673`。
- macOS `fileIfComplete()`、`checkpoint()`、`progress()` 同样线性扫描：`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:722`、`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:729`、`apps/macos/Sources/HyphenTransfer/TransferMessages.swift:737`。
- macOS App 在 Completed 后 `removeItem(at: completed.fileURL)`：`apps/macos/Sources/HyphenApp/PairingController.swift:727`。

### token、flush 与文档/tracker 状态

- Android `ResumeTokenStore` 注释声明 token in-memory only；`issue/redeem/invalidatePeer` 之外没有 purge API 或 timer：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/ResumeTokenStore.kt:6`、`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/ResumeTokenStore.kt:35`、`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/ResumeTokenStore.kt:47`、`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/ResumeTokenStore.kt:55`。
- macOS `ResumeTokenStore` 同样 in-memory only，只有 issue/redeem/invalidatePeer/liveCount：`apps/macos/Sources/HyphenTransport/ResumeTokenStore.swift:4`、`apps/macos/Sources/HyphenTransport/ResumeTokenStore.swift:38`、`apps/macos/Sources/HyphenTransport/ResumeTokenStore.swift:58`、`apps/macos/Sources/HyphenTransport/ResumeTokenStore.swift:68`。
- Android `FrameIO.write(...)` 每个 frame 后 `out.flush()`；transfer outbox 直接调用 session send：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/FrameIO.kt:21`、`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:302`、`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/ProtocolSession.kt:94`。
- 协议文档明确 v0 checkpoint in-memory，persistent partial checkpoints 属后续 hardening：`docs/protocol/hyphen-protocol-v0.md:360`、`docs/protocol/hyphen-protocol-v0.md:435`。
- 协议文档明确 transfer progress 是 local UI state，不是 wire message：`docs/protocol/hyphen-protocol-v0.md:380`、`docs/protocol/hyphen-protocol-v0.md:436`。
- Tracker `[x]` 定义为 acceptance met、tests run、docs/ADR updated；DoD 要求相关测试和失败路径：`docs/project_hyphen_roadmap_tracker_v0_3.md:13`、`docs/project_hyphen_roadmap_tracker_v0_3.md:32`。
- HYP-M3-011/012 为 `[x]`，但 row 文本限定在 core sender/receiver 与 resume wire path：`docs/project_hyphen_roadmap_tracker_v0_3.md:180`、`docs/project_hyphen_roadmap_tracker_v0_3.md:181`。
- HYP-M3-011 进度日志说明 receiver 返回 completed file path/URL，并由测试覆盖小/空文件、坏 chunk、整文件 hash：`docs/project_hyphen_roadmap_tracker_v0_3.md:408`。
- HYP-M3-012 进度日志明确 checkpoint remains v0 in-memory，durable restart resume 是 post-v0 hardening：`docs/project_hyphen_roadmap_tracker_v0_3.md:409`。
- HYP-M3-014、HYP-M3-015、HYP-M6-004 当前均为 `[?]`，且真实手工/多组合验证 blocked：`docs/project_hyphen_roadmap_tracker_v0_3.md:183`、`docs/project_hyphen_roadmap_tracker_v0_3.md:184`、`docs/project_hyphen_roadmap_tracker_v0_3.md:231`。
- 1 GiB 测试计划明确“不要 kill 任一 app”，因为 checkpoint 仍是内存状态：`docs/test-plans/hyp-m3-015-1gb-transfer-test.md:31`。
- compatibility matrix 是模板，记录规则禁止推断，当前 transfer resume 等行是 `not-run`：`docs/compatibility-matrix.md:3`、`docs/compatibility-matrix.md:20`、`docs/compatibility-matrix.md:52`。

## 修复计划

### P0：重写传输生命周期与用户可交付路径

目标：先关闭“完成即删除”“取消不停止原 sender”“状态无限增长”这三类产品语义错误。

可能触达文件/模块：

- Android transfer core：`apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt`
- Android App/controller：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt`
- macOS transfer core：`apps/macos/Sources/HyphenTransfer/TransferMessages.swift`
- macOS App/controller：`apps/macos/Sources/HyphenApp/PairingController.swift`
- 相关 UI/model 层用于保存接收目标、显示交付结果、发起取消：Android `ui/` 与 macOS `HyphenApp` model/UI。
- 对应单元/集成测试：`apps/android/app/src/test/kotlin/dev/hyphen/android/transfer/TransferMessagesTest.kt`、`apps/macos/Tests/HyphenTransferTests/TransferMessagesTests.swift`，以及 app/controller 层测试如可用。

修复项：

- 为 receiver completion 引入明确的 delivery contract：完成 hash 验证后，文件必须原子交付到用户批准的位置或保持在可打开的 app-owned location；只有交付成功并完成状态回收后才删除临时 `.part`。
- Android 使用 SAF 或 app-specific share/export flow；macOS 使用 save panel 或 security-scoped URL。debug surface 不得再无条件删除唯一副本。
- 给 `TransferSender` 增加本实例 `cancel(fileId, discard: Boolean)`：关闭 stream、清理 outstanding/active、从 registered 移除或标记 terminal、阻止后续 ACK 继续 pump，并由同一实例发送 `transfer.cancel`。
- receiver 在完成、取消、失败、超时后回收 `states/completed`；sender 在完成、取消、失败、session close 后回收 `registered/activeSends`。
- 增加 terminal-state 语义，避免 completed map 对已删除文件继续报告完成 checkpoint。若需要完成去重，保留 bounded tombstone，而不是永久 `TransferCompleted(fileURL)`。

验收标准：

- 成功接收后用户能打开/保存/定位文件；App 不删除唯一副本。
- cancel 后原 sender 不再产生任何新 `transfer.chunk`，ACK 到达也不会继续 pump。
- 完成/取消/失败/session close 后 `registered`、`activeSends`、`states`、`completed/tombstone` 均按策略释放。
- 对同一 fileId 的重复 manifest/chunk/cancel 在 terminal 状态下行为确定，并有测试覆盖。
- 错误路径不会泄漏文件内容、完整本地路径、notification 内容或私密诊断。

验证命令/手工证明：

- `cd apps/android && ./gradlew test assembleDebug`
- `cd apps/macos && swift build && swift test`
- `./scripts/check.sh`
- 手工：Android -> macOS 与 macOS -> Android 各传一个小文件，完成后打开/保存并比对 SHA-256。
- 手工：传输中点 cancel，抓取 UI 状态和本地日志，证明 cancel 后没有新增 chunk、没有残留 temp 文件、没有残留 sender registry。

### P1：资源上限、O(1) 进度与 ACK/verified 进度模型

目标：防止已配对端或异常 peer 通过小 chunk、多 fileId、重复 chunk、无限 completed/registered 表放大 CPU、磁盘、fd 和内存。

可能触达文件/模块：

- `TransferMessages.kt` / `TransferMessages.swift`
- `ProtocolSession` ack/timeout 回调接入处：Android `MainActivity.kt`，macOS `PairingController.swift`
- 协议文档若 wire 或语义改变：`docs/protocol/hyphen-protocol-v0.md`
- tracker/test plan 行在实现后同步更新。

修复项：

- `TransferState` 维护 `receivedCount`、`highestContiguousIndex`、`receivedBytes`，使 progress/checkpoint/complete 检查为 O(1) 或摊还 O(1)。
- 重复 chunk 不重复写磁盘或重复计数；乱序 chunk 的 total received 与 contiguous checkpoint 分开记录。
- 增加 per-peer 和全局配额：最大并发 transfer 数、最大暂存总字节、最大 completed/tombstone 数、最大 sender registry 数、最大 outstanding bytes/messages。
- 对超额情况返回明确错误，如 `plugin/disk-full`、`plugin/transfer-cancelled` 或新增受审 error code。
- 发送端 progress 分层：`queued/sent`、`acked`、receiver `verified`。用户主进度默认使用 ACK 或 receiver verified；debug 可单独展示 queued。
- ACK timeout、session close、trust revoke 要能终止/冻结相关 transfer，并释放资源或留下 bounded resumable state。

验收标准：

- 1,048,576 chunk 的 progress/checkpoint 路径不能按 chunk 数重复全量扫描；测试可用大 `BooleanArray`/fixture 或 micro-benchmark 证明线性扫描不在 per-chunk hot path。
- 多 fileId、重复 chunk、乱序 chunk、取消/失败/完成回收都有单元测试。
- UI 主进度不再因写入 outbox 而高估已 ACK/verified 进度。
- 资源超限时 fail closed，不写无限 temp 文件，不保留无限 map entries。

验证命令/手工证明：

- `cd apps/android && ./gradlew test assembleDebug`
- `cd apps/macos && swift build && swift test`
- `./scripts/check.sh`
- 增加一个资源边界测试/benchmark：大量 small chunk、多 fileId、重复 chunk 后确认 map/bytes/fd 均受限。
- 手工：断网停在未 ACK 窗口，UI 显示 queued 与 acked/verified 的差异，不把 queued 误报成完成。

### P2：持久 checkpoint、flush/throughput 与文档收口

目标：把 v0 内存 resume 明确升级为产品级恢复能力，并用真实数据评估 Android 每帧 flush 的吞吐影响。

可能触达文件/模块：

- 新增 transfer checkpoint repository/storage：Android app storage + macOS app support directory/keyed metadata。
- `ResumeTokenStore` 或 session supervisor 的 token purge/revoke wiring。
- Android `FrameIO` / `ProtocolSession` writer 策略，如 batching 或 writer queue。
- `docs/test-plans/hyp-m3-015-1gb-transfer-test.md`、`docs/compatibility-matrix.md`、`docs/project_hyphen_roadmap_tracker_v0_3.md`。

修复项：

- 持久化 peer-bound transfer checkpoint：manifest、temp file identity、contiguous index、received bitmap/compact ranges、hash state策略、expiry、peer/session binding。
- App/process restart 后能恢复或明确作废 partial transfer；trust revoke/reset 必须删除 peer-bound transfer checkpoints 和相关 resume tokens。
- 对 Android `FrameIO.write(... flush())` 做吞吐实验。若是瓶颈，引入受控 writer batching，确保 ACK/heartbeat 延迟不被牺牲。
- 发送前整文件 SHA-256 如首包延迟不可接受，评估预扫描 UI、缓存 hash、或协议级分阶段 manifest；任何 wire 变化需 ADR/version note。
- 更新 tracker：HYP-M3-011/012 保持“core/v0 wire path”语义；产品级交付、durable checkpoint、ACK/verified progress、资源配额应有单独行或明确补充，不再混在 `[x]` 行中。

验收标准：

- Kill/restart 场景有明确行为：可恢复、可作废并清理，或向用户展示不可恢复原因。
- trust reset/revoke 后旧 transfer checkpoint 与 resume token 不可继续使用。
- Android flush/batching 有 p50/p95 throughput、CPU、battery 或至少 wall-clock 数据；未改善则保留简单实现并记录原因。
- HYP-M3-015 与 HYP-M6-004 只有在真实 paired-device run 通过后才能关闭。

验证命令/手工证明：

- `cd apps/android && ./gradlew test assembleDebug`
- `cd apps/macos && swift build && swift test`
- `./scripts/check.sh`
- `python3 scripts/create_large_transfer_fixture.py /tmp/hyphen-transfer-1gib.bin`
- 手工：1 GiB Android -> macOS、macOS -> Android，中途断网、进程保活 resume、进程 kill/restart 行为、最终 SHA-256。
- 手工：至少三组 Android/macOS/network 组合完成 HYP-M6-004 run record。

## 开放问题与环境门槛

- **实测（2026-06-18）**：生命周期维度已实现基础用户交付（Android `received/`、macOS Downloads），但 SAF/save-panel UX、打开/分享动作与磁盘满处理仍未有真机证明。【来源：实测】
- environment-only：真实 1 GiB transfer、断网、进程死亡、Doze/OEM battery restriction、20 次 sleep/wake、三组 Android/macOS/network 组合都需要真实设备/网络与人工调度；当前仓库只能证明 core path 和测试计划存在。
- environment-only：Android SAF 与 macOS save panel/security-scoped URL 的最终 UX 需要真机/真 App 验证，单元测试不能证明用户可交付路径。
- disputed：HYP-M3-011/012 的 `[x]` 不应被解读为产品级传输生命周期完成；它们当前更准确地表示 core/v0 wire path 完成。
- confirmed：ADR-0001 把 bidirectional file transfer with resume/progress/cancellation 放在 v1 scope；如果这些 P0/P1 修复不做，transfer stability 不能作为 v1/release-ready 结论。
- open：是否继续把 1 GiB 作为 v0 单文件上限，还是在资源配额实现前临时降低 default/negotiated `maxChunkBytes` 或总量上限，需要产品与协议共同决策。
