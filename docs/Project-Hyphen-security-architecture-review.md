# Project Hyphen 跨平台安全、架构与发布就绪度评审

- **评审对象**：Project Hyphen
- **评审快照**：`main@859314870c64752d166d1bc164911b2fb825c6ce`
- **项目阶段**：Pre-alpha
- **评审范围**：Android、macOS、LAN TLS 配对与传输协议、协议 schema/test vectors、测试体系、隐私模型、文档及本地打包路径
- **证据说明**：本文基于指定提交中的源码、schema、测试、脚本与文档开展静态评审。未在本次环境中独立执行 Gradle、SwiftPM、真机、签名、公证或商店审核流程；相关结论均按“本地代码证据”和“外部门槛”分开陈述。

---

## 1. 执行摘要（Executive Summary）

Hyphen 当前仍是“协议组件可演示、产品闭环未完成”的 pre-alpha，不是可持续常驻的 Alpha 陪伴层。TLS pin/SAS、帧限额和脱敏日志基础可信；但自动重连未接入、接收文件即删、取消无效、百万分块 O(n²)、通知隐私先发后设，以及 API 26–28/TLS 1.3 冲突，均足以阻断 Alpha。Critical 0，High 8；加权 **4.5/10**。

---

## 2. 评分卡（Scorecard）

| 维度 | 分数 | 权重 | 一句话理由 |
|---|---:|---:|---|
| A. 功能性 | 3.5 | 10% | QR 单次会话及若干插件路径存在，但常驻、重连和文件接收交付未闭环。 |
| B. 完整性 | 3.0 | 8% | 组件和测试很多，但多个 v1 P0 仍是组件级、内存级或 UI 演示级实现。 |
| C. 效率 | 3.5 | 6% | 有流式 IO 和 8 块窗口，但接收进度退化为 O(n²)，状态表缺少回收。 |
| D. 性能 | 3.0 | 8% | 有分块、帧上限和窗口，但无持久恢复、后台执行和真实大文件/功耗证据。 |
| E. 安全性 | 5.0 | 20% | pin、mTLS、SAS 和严格 envelope 较扎实；仍有配对状态、隐私竞态及资源耗尽问题。 |
| F. 架构与代码质量 | 4.5 | 10% | 模块划分可辨，但双端手写协议漂移，主控制器职责过重，异常隔离不一致。 |
| G. 协议与互操作性 | 5.5 | 15% | schema、向量和次版本策略较系统；能力方向、严格字段和配对序列未完全落地。 |
| H. 测试 | 5.5 | 9% | 单元/环回测试面较广，但关键生命周期、竞态、复杂度、真机和产物测试缺门禁。 |
| I. 隐私与威胁模型 | 5.5 | 9% | 无云、无遥测、诊断脱敏基本兑现；通知源端策略存在短暂明文外发窗口。 |
| J. 发布就绪度与文档 | 3.0 | 5% | 文档大体诚实，但 macOS 包资源缺失、正式许可证未落地，外部门槛均未关闭。 |

**加权总评：4.455，四舍五入为 4.5/10。**

安全性、协议和隐私合计权重 **44%**，符合 local-first LAN 安全项目的风险分布。

### 审计边界

项目自己的兼容性矩阵明确说明各行是覆盖目标而非已完成结果：

- `docs/compatibility-matrix.md:5-18`
- `docs/compatibility-matrix.md:20-40`
- `docs/compatibility-matrix.md:54-76`

因此，“测试文件存在”不等于本次审计已本地跑绿。【仅环境相关】

---

## 3. 逐维度深评

## A. 功能性（Functionality）— 3.5/10

### 现状证据

Android QR 路径会按 QR 中的 SPKI 指纹建立 TLS、计算 SAS、用户确认后启动 hello/稳态会话；macOS 端也有对应 SAS UI 和会话接管。

- `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:435-579`
  - `startPairing`
  - `presentSasDialog`
  - `startSteadySession`
- `apps/macos/Sources/HyphenApp/PairingController.swift:240-299`
  - `presentSasConfirmation`

### 问题

1. 手工 endpoint 输入只执行 TCP probe，不进入 TLS、SAS、信任写入或 session。
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:399-423`
   - `probeManualEndpoint`

2. Android 连接由 `MainActivity` 持有，`onDestroy()` 主动清空通知 outbox 并停止 session；Manifest 没有承载传输的前台服务。
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:1167-1178`
   - `apps/android/app/src/main/AndroidManifest.xml:20-58`

3. macOS 的唤醒 `startConnect` 仍只写日志，连接关闭回调只清状态。
   - `apps/macos/Sources/HyphenApp/main.swift:166-177`
   - `apps/macos/Sources/HyphenApp/PairingController.swift:438-450`

4. 双端收到完整文件并校验后立即删除临时文件。
   - Android：`apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:661-679`
   - macOS：`apps/macos/Sources/HyphenApp/PairingController.swift:727-747`

### 影响

Happy path 可展示“配对后交换消息”，但屏幕旋转、Activity 回收、睡眠、网络切换或一次断线即可结束产品级连续性；成功接收的文件也无法被用户使用。

该状态不满足 README 所称的 persistent paired companion，也不满足 ADR-0001 的重连与双向文件传输要求：

- `README.md:5-25`
- `docs/adr/0001-product-scope.md:18-33`

### 证据等级

【已确认】

---

## B. 完整性（Completeness）— 3.0/10

### 现状证据

ADR-0001 冻结了 QR+SAS、pinned TLS、manual fallback、通知、双向传输、跨前后台重连和脱敏诊断等 v1 能力：

- `docs/adr/0001-product-scope.md:18-33`

Tracker 规定 `[x]` 表示验收标准满足、相关测试已运行、失败路径和文档均处理：

- `docs/project_hyphen_roadmap_tracker_v0_3.md:13-21`
- `docs/project_hyphen_roadmap_tracker_v0_3.md:32-41`

### 问题

Tracker 把 SAS UI、重连、协议文档同步标成 `[x]`，也把传输 sender/receiver、resume 和完整性标成 `[x]`；但产品路径没有自动重连，配对没有文档所述的双端 confirm 交换，resume 只在进程内存中成立，接收文件不会交付用户。

相关条目：

- `docs/project_hyphen_roadmap_tracker_v0_3.md:158-164`
- `docs/project_hyphen_roadmap_tracker_v0_3.md:178-191`

### 影响

当前 tracker 更像“协议组件/单元验收状态”，却使用了“产品 DoD 完成”的状态语义，会误导后续 Alpha gate、Issue 优先级和发布说明。

### 证据等级

【已确认】

---

## C. 效率（Efficiency）— 3.5/10

### 现状证据

发送端使用流式输入，最多保留 8 个未 ACK 分块，未一次性加载整文件；manifest 约束文件最大 1 GiB、分块 1 KiB–2 MiB，并验证 `chunkCount`。

- `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:15-28`
- `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:315-439`
- `apps/macos/Sources/HyphenTransfer/TransferMessages.swift:7-18`
- `apps/macos/Sources/HyphenTransfer/TransferMessages.swift:363-568`

### 问题

1. 每收到一块，`TransferState.progress()` 从索引 0 线性扫描；`fileIfComplete()` 又全量扫描。允许的最大分块数是 1,048,576，因此顺序传输总体可退化为 O(n²)。

   Android：
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:509-514`
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:574-590`

   macOS：
   - `apps/macos/Sources/HyphenTransfer/TransferMessages.swift:628-636`
   - `apps/macos/Sources/HyphenTransfer/TransferMessages.swift:722-742`

2. `registered`、`completed` 和多 fileId 状态没有容量或生命周期回收。
   - Android：`TransferMessages.kt:348-349,483-484`
   - macOS：`TransferMessages.swift:392-393,587-588`

3. 源文件先完整读取计算 SHA-256，再重新打开传输；这是完整性设计的合理代价，但大文件首包延迟会接近一次完整磁盘读取。
   - Android：`TransferMessages.kt:81-124`
   - macOS：`TransferMessages.swift:83-140`

### 影响

已配对恶意端可利用大量小分块、重复分块和并发 fileId 放大 CPU、随机写和内存状态；正常 1 GiB/1 KiB 极端配置也不可接受。

### 证据等级

【已确认】

---

## D. 性能（Performance）— 3.0/10

### 现状证据

4 MiB frame cap、流式文件源、分块窗口和逐块 hash 提供了基本内存上界。

- `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/FrameIO.kt:10-46`
- `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:119-144`
- `apps/macos/Sources/HyphenTransfer/TransferMessages.swift:133-160`

### 问题

1. 发送进度在分块写入 outbox 后立即推进，而非在 ACK 到达后推进。
   - Android：`TransferMessages.kt:411-437`
   - macOS：`TransferMessages.swift:542-567`

2. resume checkpoint、发送 source 注册表及接收 bitmap 均为内存对象；Android Activity 销毁即失去连接和 sender。
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:483-490`
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:1167-1178`

3. 没有真机 1 GiB、进程死亡、Doze/OEM 电池限制、20 次 sleep/wake 的结果；矩阵全部为 `not-run` 或 `blocked`。
   - `docs/compatibility-matrix.md:20-40`
   - `docs/compatibility-matrix.md:54-69`

### 影响

UI 会高估真实交付进度；“断线续传”只能覆盖同进程、状态尚存的窄场景；吞吐、耗电和恢复时间均无可发布证据。

### 证据等级

- 代码结论：【已确认】
- 真机性能：【未证实 / 仅环境相关】

---

## E. 安全性（Security）— 5.0/10

### 现状证据

1. 双端身份基于 SPKI pin，而非 LAN discovery 或 CA/hostname；客户端和服务端证书都走 pin 检查。
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/PinnedTrustManager.kt:9-39`

2. Android 信任数据使用 AES-256-GCM，密钥由 Android Keystore 路径提供；Manifest 禁用备份。
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/trust/TrustCipher.kt:18-49`
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/trust/AndroidTrustStores.kt:13-54`
   - `apps/android/app/src/main/AndroidManifest.xml:20-25`

3. SAS transcript 绑定 nonce、双方指纹、角色顺序和协议版本；向量校验包含篡改不匹配与前导零。
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/pairing/PairingTranscript.kt:7-64`
   - `scripts/verify_pairing_vectors.py:20-67`

4. Envelope 拒绝未知顶层字段，frame 有 4 MiB 上限，trace 强制 `localOnly=true`。
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/Envelope.kt:49-67`
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/Envelope.kt:121-136`
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/FrameIO.kt:10-46`

### 问题

- 配对落盘不等待对端确认。
- 通知隐私策略存在先发后设窗口。
- 已配对恶意端可触发 O(n²) 传输和通知队列增长。
- macOS provisional fingerprint 槽可被并发握手覆盖。
- resume token 无全局过期清理。

### 具体攻击场景

1. 已配对或失窃设备发起百万分块和重复块，造成 CPU/磁盘可用性攻击。
2. 本地恶意 Android App 高频发布/撤销通知，在 socket 阻塞时推高 critical removal 队列。
3. 同网段攻击者在 macOS 首次配对 listener 上并发握手，覆盖待附着指纹，引发 SAS 错配或配对 DoS。
4. 已存在通知可能在用户的 `hideBody/existsOnly` 策略到达 Android 前以完整内容跨 LAN 发往已配对 Mac。

### 影响

目前未确认未认证 RCE、pin 绕过或无交互持久信任接管；主要风险是已配对端/本地 App 可用性攻击、隐私承诺违背和配对状态不一致。

### 证据等级

【已确认】

---

## F. 架构与代码质量（Architecture & Maintainability）— 4.5/10

### 现状证据

macOS 分为 Core、Transport、Notifications、Text、Transfer、Diagnostics 等 SwiftPM target；Android 也有相应 package，双端类型命名和协议结构基本镜像。

- `apps/macos/Package.swift:19-59`

### 问题

1. `MainActivity` 同时负责 UI、worker 生命周期、配对、信任、TLS、session、通知、文本、传输和诊断；`PairingController` 也承担类似跨层职责。

2. Kotlin 与 Swift 分别手写 schema 约束和能力交集，已经产生相同方向协商缺陷和字段严格性漂移。

3. Android `ProtocolSession.readLoop()` 未隔离 listener 异常；App 插件层又只捕获 `IllegalArgumentException`。
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/ProtocolSession.kt:128-157`
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:703-714`

### 影响

生命周期、线程和协议状态相互耦合；未来增加 FGS、重连和持久 transfer supervisor 时容易产生多 owner、旧 callback 和撤销竞态。

### 证据等级

【已确认】

---

## G. 协议与互操作性（Protocol & Interop）— 5.5/10

### 现状证据

Envelope、capability、transfer manifest 等 schema 有必填字段、模式、上限和 `additionalProperties:false`；协议规定同 major 次版本兼容、能力交集和版本绑定 SAS。

- `protocol/schema/envelope.schema.json:3-84`
- `protocol/schema/capability.schema.json:3-92`
- `protocol/schema/transfer-manifest.schema.json:3-51`
- `docs/protocol/hyphen-protocol-v0.md:138-143`

### 问题

1. Schema 允许 `text.v1.direction` 为 `send-only` 或 `receive-only`，但两端只要都声明 `text.v1`，就固定协商为 `bidirectional`。
   - `protocol/schema/capability.schema.json:73-79`
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/SessionHandshake.kt:49-51`
   - `apps/macos/Sources/HyphenTransport/SessionHandshake.swift:112-114`

2. Transfer schema 禁止额外字段，但 `TransferManifest.fromJson`、`TransferChunk.fromJson` 等只读取已知字段，不检查剩余字段。
   - `protocol/schema/transfer-manifest.schema.json:8-10`
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:106-115`
   - `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:184-197`

3. 协议文档定义 `pair.request/challenge/response/confirm` 和“双端确认后落盘”，生产代码没有该 wire 状态机。
   - `docs/protocol/hyphen-protocol-v0.md:91-118`

4. Python 校验器是项目使用的 JSON Schema 子集实现，而非完整标准实现或运行时 decoder differential test。
   - `scripts/validate_protocol_fixtures.py:3-15`
   - `scripts/validate_protocol_fixtures.py:42-99`

### 影响

当前同版本双端因都宣告双向而暂时可工作，但第三方实现、未来单向能力和严格 decoder 会出现不对称协商或一端接受、一端拒绝。

### 证据等级

【已确认】

---

## H. 可测试性与测试覆盖（Testability & Tests）— 5.5/10

### 现状证据

仓库存在 TLS pin、SAS、严格 JSON、handshake、重连、通知、传输、诊断等 JVM/Swift 单元或环回测试；协议脚本会运行 schema fixture、SAS 和其他向量验证。

验证器要求每个 schema 至少有一组 valid 和 invalid fixture：

- `scripts/validate_protocol_fixtures.py:102-145`

### 问题

1. `check.sh` 非 strict 模式允许平台检查 `SKIP` 后整体成功。
   - `scripts/check.sh:23-28`
   - `scripts/check.sh:74-104`

2. 唯一 workflow 使用 Ubuntu 且仅执行非 strict `./scripts/check.sh`，没有 macOS runner、打包 smoke test 或设备测试。
   - `.github/workflows/checks.yml:13-20`

3. 缺少以下回归：
   - 通知 policy ACK 前禁止 snapshot
   - 取消后不得再产生 chunk
   - 百万分块复杂度
   - 全 critical 队列硬上限
   - Activity/FGS 生命周期
   - 真实跨平台 sleep/wake
   - 打包后启动

4. 兼容性矩阵明确没有真机通过记录。
   - `docs/compatibility-matrix.md:54-76`

### 影响

测试对纯组件逻辑有价值，但不能证明 App 主路径、后台常驻、跨平台互操作和分发产物。

### 证据等级

- 测试及 CI 配置：【已确认】
- 测试当前是否全绿：【未证实 / 仅环境相关】

---

## I. 隐私与威胁模型（Privacy & Threat Model）— 5.5/10

### 现状证据

ADR 明确排除账号、云中继、SMS/Call Log、屏幕控制、后台剪贴板和私有 API；Manifest 未申请这些权限并禁用备份。

- `docs/adr/0001-product-scope.md:35-46`
- `apps/android/app/src/main/AndroidManifest.xml:6-25`

诊断日志只接受受控错误码和 token，容量 500；导出默认不含 trace ID，也不包含通知正文、文本、URL 或原始 exception。

- `apps/android/app/src/main/kotlin/dev/hyphen/android/diagnostics/StructuredLog.kt:26-93`
- `apps/android/app/src/main/kotlin/dev/hyphen/android/diagnostics/RedactedDiagnosticsExporter.kt:22-46`

### 问题

Android 默认通知策略为 `SHOW_FULL`；绑定 outbox 会立即重放 active snapshot；macOS 启动 session 后才发送策略。

- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationPrivacy.kt:19-36`
- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycle.kt:65-79`
- `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycle.kt:188-196`
- `apps/macos/Sources/HyphenApp/PairingController.swift:496-499`

测试直接证明默认 snapshot 含正文：

- `apps/android/app/src/test/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycleTest.kt:202-216`

### 影响

数据仍在 pinned TLS 内发送给已配对 Mac，不是旁路窃听；但若用户配置 `hideBody/existsOnly`，正文仍可能短暂离开手机，违背“源端过滤、隐藏内容从不离开手机”的承诺。

### 证据等级

【已确认】

---

## J. 发布就绪度与文档（Release Readiness & Docs）— 3.0/10

### 现状证据

README 明确称 pre-alpha、无签名/公证/商店发布，仅供本地构建；安装文档也把 Developer ID、公证、Android 密钥和设备矩阵列为外部门槛。

- `README.md:3-7`
- `docs/install/installation_en.md:162-170`

### 本地代码/脚本缺口

1. SwiftPM 声明 `HyphenApp` 有资源，所有 UI 字符串通过 `Bundle.module` 读取，但 `package-local.sh` 仅复制 executable，未复制 SwiftPM resource bundle。
   - `apps/macos/Package.swift:19-33`
   - `apps/macos/Sources/HyphenApp/Localization.swift:5-19`
   - `packaging/macos/package-local.sh:34-48`

2. 正式 MPL/Apache/CC-BY 文本、license map、SPDX、NOTICE 规则和 DCO/CLA 尚未落地；ADR 明确禁止把当前仓库描述为 release-packaged licensed distribution。
   - `docs/adr/0005-license-and-clean-room-policy.md:17-20`
   - `docs/adr/0005-license-and-clean-room-policy.md:34-44`

### 外部门槛

- Developer ID
- Notarization / stapling
- Android release/upload key
- Play/F-Droid 账号与审核
- 真机矩阵
- Sleep/wake 安排

这些不能用本地单测替代，也不应被归类为源码失败。

### 影响

当前可做开发者 dry-run，但尚不具备可信安装包、公开接收贡献或 Alpha 分发门禁。

### 证据等级

- 本地缺口：【已确认】
- 签名/公证/商店结果：【仅环境相关】

---

## 4. 重点问题清单（Findings）

## Critical

**无已确认的 Critical。**

在本快照审阅范围内，未发现可直接证明的未认证 RCE、TLS pin 绕过、无交互信任持久化或 LAN 明文传输；这不构成对不存在未知漏洞的证明。

---

## High

### H-01 — “常驻连接/自动重连”未接入产品路径

- **位置**
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:530-536`
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:1167-1178`
  - `apps/android/app/src/main/AndroidManifest.xml:6-58`
  - `apps/macos/Sources/HyphenApp/main.swift:166-172`
  - `apps/macos/Sources/HyphenApp/PairingController.swift:438-450`
- **攻击或失效场景**：Activity 销毁、Mac 睡眠、Wi‑Fi 切换或 socket 断开后，只清空状态，没有持久 transport owner、listener 重开或重新拨号。
- **影响**：核心“persistent companion”主张不成立；通知、传输和 resume 随 UI 生命周期终止。
- **修复建议**：
  - Android 建立 app-scoped connection supervisor。
  - 使用合规 `connectedDevice/dataSync` FGS 承载持续连接和大文件传输。
  - macOS 建立持久 listener/dialer。
  - 把 `SessionReconnector`、endpoint、resume token、trust revoke 统一接入。
- **证据等级**：【已确认】

### H-02 — 成功接收的文件立即被删除

- **位置**
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:661-679`
  - `apps/macos/Sources/HyphenApp/PairingController.swift:727-747`
- **攻击或失效场景**：文件全部分块和整文件 SHA-256 均通过后，App 删除 `.part`，只留下活动记录。
- **影响**：用户无法打开、保存或定位接收文件；“文件互传”只有协议演示效果。
- **修复建议**：
  - 完成后原子移动到用户批准的目标位置。
  - macOS 使用保存面板/security-scoped URL。
  - Android 使用 SAF。
  - 移动成功后再删除临时状态。
- **证据等级**：【已确认】

### H-03 — 取消按钮不停止原始发送器

- **位置**
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:878-895`
  - `apps/macos/Sources/HyphenApp/PairingController.swift:880-899`
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:402-439`
  - `apps/macos/Sources/HyphenTransfer/TransferMessages.swift:520-568`
- **攻击或失效场景**：UI 新建一个 `TransferSender` 发送 `transfer.cancel`；原 `activeTransferSender` 仍持有 stream、outstanding 和 ACK 回调，后续 ACK 会继续 pump。
- **影响**：取消后仍可能继续读取文件和发送分块，消耗带宽、电量和磁盘；UI 与真实状态分裂。
- **修复建议**：
  - 在原 sender 上实现 `cancel(fileId)`。
  - 关闭 stream。
  - 移除 outstanding/active/registered。
  - 阻止后续 ACK pump。
  - 由同一实例发送 cancel。
  - 增加“取消后零新增 chunk”测试。
- **证据等级**：【已确认】

### H-04 — 百万分块 O(n²) 与无聚合配额，可被已配对端耗尽资源

- **位置**
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:15-27`
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:483-590`
  - `apps/macos/Sources/HyphenTransfer/TransferMessages.swift:584-742`
- **攻击或失效场景**：已配对恶意端宣告 1 GiB、1 KiB 分块并顺序或重复发送；每块触发两次线性扫描，还可创建多个 fileId。
- **影响**：CPU、随机 IO、文件描述符和磁盘空间耗尽；属于威胁模型内的 paired/lost-device DoS。
- **修复建议**：
  - 维护 O(1) `receivedCount`。
  - 维护单调 `highestContiguousIndex`。
  - 重复块直接 no-op。
  - 限制并发传输数。
  - 限制单 peer 暂存总字节、状态数和空闲超时。
- **证据等级**：【已确认】

### H-05 — 通知隐私策略存在“先发完整内容、后下发策略”的竞态

- **位置**
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationPrivacy.kt:19-50`
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycle.kt:65-79`
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycle.kt:188-196`
  - `apps/macos/Sources/HyphenApp/PairingController.swift:496-499`
- **攻击或失效场景**：Android 当前有 active notification；新 session 绑定 outbox 时默认 `SHOW_FULL` 并立即 snapshot；Mac 的 `notification.privacy.policy` 尚未到达/处理。
- **影响**：标题、正文、reply label 可能先越过 LAN，违反 `hideBody/existsOnly` 的源端隐私承诺。
- **修复建议**：
  - 首次连接默认 fail-closed 为 `existsOnly`。
  - 持久化经用户确认的本地策略。
  - 在 policy ACK 前不得 bind/snapshot。
  - 建立竞态测试。
- **证据等级**：【已确认】

### H-06 — Android 声明 minSdk 26，但传输层明确要求 API 29+ TLS 1.3

- **位置**
  - `apps/android/app/build.gradle.kts:31-36`
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/TlsEndpoint.kt:13-32`
- **攻击或失效场景**：API 26–28 设备可以安装 App，却在创建 `TLSv1.3` context/配对时失败。
- **影响**：兼容性声明与核心功能直接冲突；错误发生在用户完成安装后。
- **修复建议**：Alpha 前二选一：
  1. 提高 `minSdk` 至 29；或
  2. 新增正式 ADR，定义受限 TLS 1.2 profile、cipher、降级防护和设备矩阵。
- **证据等级**：
  - 声明冲突：【已确认】
  - 具体设备表现：【仅环境相关】

### H-07 — 插件异常可杀死 Android reader thread，且不保证执行正常关闭

- **位置**
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/ProtocolSession.kt:128-157`
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:650-714`
- **攻击或失效场景**：磁盘满、文件句柄失败、非 `IllegalArgumentException` 的 IO/runtime 异常从 `transferReceiver.handle()` 逸出；`listener.onEnvelope()` 外层没有 `try/finally`。
- **影响**：reader 线程异常退出，`close()`/`onClosed()` 可能不执行，形成 UI 假连接、资源泄漏和无法重连。
- **修复建议**：
  - session 边界捕获所有插件异常。
  - 映射为受控 protocol/plugin error。
  - 使用 `finally { close() }`。
  - 插件运行与 transport reader 隔离。
- **证据等级**：【已确认】

### H-08 — macOS 打包脚本遗漏 SwiftPM resource bundle

- **位置**
  - `apps/macos/Package.swift:19-33`
  - `apps/macos/Sources/HyphenApp/Localization.swift:5-19`
  - `packaging/macos/package-local.sh:34-48`
- **攻击或失效场景**：App 的所有本地化字符串依赖 `Bundle.module`，脚本却只复制 executable，`Contents/Resources` 为空。
- **影响**：生成的 `.app` 不符合 SwiftPM 运行时资源契约，不能作为“安装路径已测试”的可信产物。
- **修复建议**：
  - 复制构建目录中的 `Hyphen_HyphenApp.bundle` 或实际生成 bundle 至 `Contents/Resources`。
  - 再执行 codesign。
  - 在干净用户环境执行 launch + localized-string smoke test。
- **证据等级**：
  - 脚本遗漏：【已确认】
  - 实际启动表现：【未证实】

---

## Medium

### M-01 — 配对没有协议要求的双端 confirm 后落盘

- **位置**
  - `docs/protocol/hyphen-protocol-v0.md:91-118`
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:479-484`
  - `apps/macos/Sources/HyphenApp/PairingController.swift:273-299`
- **场景与影响**：一端点击确认即写本地 trust 并启动 session，另一端可拒绝或断线，形成半配对和状态不一致。由于 QR 已预共享 Mac pin，这不是直接无交互 MITM，但违反协议状态机。
- **修复建议**：
  - 实现 `pair.request/challenge/response/confirm`。
  - 双方收到 accepted confirm 后再提交 trust。
  - 或通过新 ADR 正式修改协议并更新向量。
- **证据等级**：【已确认】

### M-02 — macOS provisional pairing 指纹可在连接附着前被覆盖

- **位置**
  - `apps/macos/Sources/HyphenCore/ProvisionalPairingState.swift:31-48`
- **场景与影响**：连接 A 完成 verify、尚未 attach 时，连接 B 可覆盖 `pendingFingerprint`；A 随后可能绑定 B 的指纹，造成 SAS 错配/配对 DoS。
- **修复建议**：
  - 每个 verify callback 生成不可复用 attempt ID。
  - 把 `(connectionID, fingerprint)` 原子绑定。
  - 不要使用可覆盖的全局单槽。
- **证据等级**：【已确认】

### M-03 — resume 仅内存有效；token 无全局过期清理，撤销路径未统一失效

- **位置**
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/transport/ResumeTokenStore.kt:8-64`
  - `apps/macos/Sources/HyphenTransport/ResumeTokenStore.swift:6-79`
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:483-490`
- **场景与影响**：
  - 进程死亡后 checkpoint 消失。
  - 未兑换的过期 token 不会主动清理。
  - App 删除完成文件后 receiver 仍可报告“已完成” checkpoint。
- **修复建议**：
  - 持久化 peer-bound transfer checkpoint。
  - 定期 purge token。
  - 所有 revoke/reset 调用 `invalidatePeer`。
  - 完成文件移动或删除时同步清理 completed 状态。
- **证据等级**：【已确认】

### M-04 — 通知 critical 队列的 128 容量不是硬上限

- **位置**
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/notifications/NotificationListenerLifecycle.kt:268-342`
- **场景与影响**：队列全为 critical removal 时找不到 best-effort 项可丢，但代码仍 `addLast`；本地恶意 App 高频发/撤通知可造成无界增长。
- **修复建议**：
  - 所有任务统一硬上限。
  - 按 `sbnKey` 合并 removal。
  - 满载时记录最终状态而非逐事件排队。
- **证据等级**：【已确认】

### M-05 — schema 与运行时严格性、能力方向发生漂移

- **位置**
  - `protocol/schema/transfer-manifest.schema.json:8-10`
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:104-115`
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:183-195`
  - 双端 `SessionHandshake`
- **场景与影响**：
  - 严格第三方实现拒绝带额外字段的消息，而 Hyphen 接受。
  - 单向 text peer 被错误宣告为双向。
- **修复建议**：
  - 生成式或共享 conformance validator。
  - 所有 payload decoder 明确校验字段集合。
  - 增加所有合法方向组合的双端向量。
- **证据等级**：【已确认】

### M-06 — 传输进度并非 ACK 进度，状态表不回收

- **位置**
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:348-349`
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/transfer/TransferMessages.kt:411-437`
  - `apps/macos/Sources/HyphenTransfer/TransferMessages.swift:430-567`
- **场景与影响**：
  - 网络停在未 ACK 窗口时 UI 已显示更多完成分块。
  - 长期发送会累积 `registered` source。
- **修复建议**：
  - 区分 queued/sent/acked/verified。
  - 只按 ACK 或 receiver verified 状态更新用户进度。
  - 完成/取消/失败时回收所有注册项。
- **证据等级**：【已确认】

### M-07 — Android 信任文件的锁只保护单实例

- **位置**
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/trust/EncryptedFilePeerTrustStore.kt:42-67`
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/trust/EncryptedFilePeerTrustStore.kt:120-138`
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/trust/AndroidTrustStores.kt:34-38`
- **场景与影响**：两个 store 实例可分别执行 load–mutate–persist；若未来 FGS、配对 UI 和设备管理并发，可能丢失更新，极端情况下把刚撤销的 pin 写回。当前生产并发可达性尚未证明。
- **修复建议**：
  - 进程级单例。
  - 跨实例锁或 `FileChannel` lock。
  - 唯一临时文件和 compare-and-swap/version。
  - 增加两实例交错 add/remove 测试。
- **证据等级**：
  - 锁域缺陷：【已确认】
  - 当前可利用并发：【未证实 / 有争议】

### M-08 — manual endpoint 不是 manual pairing

- **位置**
  - `apps/android/app/src/main/kotlin/dev/hyphen/android/MainActivity.kt:399-423`
  - `docs/protocol/hyphen-protocol-v0.md:89-114`
  - `docs/install/installation_en.md:150-153`
- **场景与影响**：用户在 mDNS/QR 不可用时输入 IP，只得到 TCP “connected”，没有 SAS 主认证路径。
- **修复建议**：
  - 实现无 QR 指纹时的 TLS provisional connection + 强制 SAS；或
  - 从当前 UI/文档中移除“manual pairing”表述。
- **证据等级**：【已确认】

### M-09 — CI 可在平台测试被跳过时成功

- **位置**
  - `scripts/check.sh:21-28`
  - `scripts/check.sh:72-104`
  - `.github/workflows/checks.yml:13-20`
- **场景与影响**：缺 Swift/macOS 工具链时显示 SKIP，workflow 仍可能绿色；无法作为双平台合并门禁。
- **修复建议**：
  - Linux job 运行 Android/protocol strict。
  - macOS job 强制 `swift test`、package/launch smoke。
  - 平台缺失应失败而非 SKIP。
- **证据等级**：【已确认】

### M-10 — 许可证决策已作出，但仓库尚不是完整许可分发包

- **位置**
  - `docs/adr/0005-license-and-clean-room-policy.md:17-44`
  - `README.md:51-53`
- **场景与影响**：公开打包或合并外部贡献时，缺根许可证文本、树级映射、SPDX 和 DCO/CLA 依据。
- **修复建议**：在接受外部代码或 Alpha 公开包前落地全部正式文本、license map、SPDX sweep 和贡献签署策略。
- **证据等级**：【已确认】

---

## Low

### L-01 — 每帧强制 `flush()`

- **位置**：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/FrameIO.kt:23-36`
- **影响**：小消息密集场景会增加系统调用和 TLS record 碎片。
- **建议**：让 session writer 批量化，但不得牺牲 ACK/heartbeat 延迟。
- **证据等级**：【已确认】

### L-02 — Android `TlsServer` 每个握手创建独立线程且无并发上限

- **位置**：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/TlsEndpoint.kt:57-75`
- **影响**：若未来启用 Android server，LAN 端可用连接洪泛消耗线程；当前产品主要走 Android client，故暂定 Low。
- **证据等级**：【已确认，当前仅组件路径】

### L-03 — 多处版本/阶段注释已经过期

- Manifest 仍称“future TLS transport”：
  - `apps/android/app/src/main/AndroidManifest.xml:6-8`
- macOS reconnect 文案仍称 transport 将在 M2 到来：
  - `apps/macos/Sources/HyphenApp/main.swift:166-172`
- **影响**：维护者误判和 tracker 漂移。
- **证据等级**：【已确认】

---

## 5. 风险登记表（Risk Register）

| 风险 | 当前判断 | 需要补充的材料才能定论 |
|---|---|---|
| 全仓测试是否在该 SHA 真正全绿 | 【未证实 / 仅环境相关】 | `./scripts/check.sh --strict`、Gradle test、`swift test` 的原始日志及 CI run ID |
| API 26–28 实际 TLS 失败形式 | 声明冲突已确认，设备表现【仅环境相关】 | API 26/27/28 emulator 或真机，记录 provider、异常和连接日志 |
| macOS 资源 bundle 缺失后的实际表现 | 脚本遗漏已确认，启动结果【未证实】 | 执行 `package-local.sh`，列出 `.app/Contents`，在干净用户环境 launch |
| Android 信任库撤销丢失更新 | 【有争议】 | 两个 store 实例的 deterministic barrier test；未来 FGS/管理 UI 调用图 |
| macOS login Keychain → data-protection Keychain 迁移 | 【未证实 / 仅环境相关】 | 签名 entitlement、访问控制、备份/iCloud 同步验证；当前代码仍用 login keychain |
| 非拉丁 locale 下 SAS 是否仍是 ASCII 0–9 | 【未证实】 | Android/macOS 阿拉伯语、波斯语等 locale 向量；Android 当前使用默认 locale 的 `String.format` |
| OEM 后台、Doze、sleep/wake 和 restricted LAN | 【仅环境相关】 | Pixel/Samsung/Xiaomi/OnePlus、不同 macOS/网络的完整矩阵及 20 次循环日志 |
| 1 GiB 断线、进程死亡及磁盘不足路径 | 【未证实】 | 三组真机/网络，包含中断、进程 kill、恢复、取消、磁盘满和最终 hash |
| Developer ID、公证、Play/F-Droid 审核 | 【仅环境相关】 | 实际凭据、notary log、stapled artifact、商店审核反馈 |
| 通知超长标题/正文和动作数组的资源边界 | 【未证实】 | 本地恶意 App 生成接近 4 MiB payload 的 instrumentation/fuzz test；目前只有 frame 总上限 |

---

## 6. 优先级建议（Prioritized Recommendations）

## 阻断 Alpha 的 P0

### P0-1：建立真正的连接所有者

- Android 把 TLS/session/notification outbox/transfer 从 Activity 移入 application-scoped supervisor。
- 使用合规 FGS 承载持续连接和大文件传输。
- macOS 保持 listener 或 remembered-endpoint dialer。
- 接入 `SessionReconnector`、sleep/wake、network change 和 trust revoke。
- 完成标准必须是 UI 重建、Activity 销毁和一次真实断网后自动恢复。

### P0-2：重写文件传输生命周期

- 增加实际接收目标与原子交付。
- 取消必须终止原 sender。
- 进度区分 sent/acked/verified。
- checkpoint 持久化。
- 接收算法改为 O(1) 计数。
- 限制并发数、暂存总量和重复块。
- HYP-M3-011/012 只应在这些产品路径完成后继续保持“整体完成”语义。

### P0-3：把通知隐私改为 fail-closed

- 在 policy ACK 前只允许 `existsOnly`。
- 不得发送 active snapshot 正文。
- 硬限制队列并按 key 合并状态。
- 新增“连接建立第一帧”“outbox rebind”“旧 active notifications”竞态测试。

### P0-4：闭合配对状态机

- 实现双方 transcript hash 交换。
- 实现双端 accepted confirm。
- trust 只在双方确认后提交。
- 修复 provisional connection/fingerprint 绑定。
- 所有 reject、revoke 和 reset 同步清理 token/session。

### P0-5：解决平台支持和异常边界

- 决定 `minSdk 29` 或正式 TLS 1.2 ADR。
- `ProtocolSession` 必须隔离插件异常并始终关闭。
- 修复 Android trust store 的跨实例事务性。

### P0-6：让本地包真正可启动

- 修复 SwiftPM resource bundle 复制与签名顺序。
- 增加 `.app` launch/localization smoke。
- 不要把 ZIP/DMG 生成成功当成安装路径成功。

### P0-7：纠正 tracker

将以下任务降为 `[~]`，或拆分成“核心组件完成 / 产品接入未完成”：

- HYP-M2-011
- HYP-M2-013
- HYP-M2-015
- HYP-M5-003
- 受上述问题影响的传输任务

恢复 `[x]` 的 DoD 含义。

---

## Beta 前的 P1

1. 以 schema/向量驱动双端 decoder，补齐 unknown-field、跨字段、全部能力方向和 malformed input differential tests。
2. CI 拆分 Linux/Android、macOS/Swift、packaging 三类 strict job；加入 sanitizer/fuzzer、性能预算和资源配额测试。
3. 填完真机矩阵、20 次 sleep/wake、OEM 后台、restricted LAN 和至少三组 1 GiB 中断恢复。
4. 完成正式许可证文本、license map、SPDX、NOTICE 判定和 DCO/CLA。
5. macOS 迁移到 data-protection Keychain；Android trust store 采用单例/文件锁和版本化事务。
6. 完成 Developer ID、公证、Android release key、Play/F-Droid policy evidence；继续把这些标为外部门槛，不与本地代码绿色混为一谈。

---

## 7. 一致性问题（Doc/Code/Tracker Drift）

| 文档/Tracker 声明 | 代码现实 | 判定 |
|---|---|---|
| 协议要求 `pair.request/challenge/response/confirm`，双方确认后才存 trust | 双端各自本地点击即写 trust 并启动 session | **协议/代码漂移**【已确认】 |
| HYP-M2-011 `[x]`：匹配码确认后存 trust，双端 UI 已完成 | UI 存在，但没有远端确认状态交换 | **Tracker 过度完成**【已确认】 |
| HYP-M2-013 `[x]`：模拟断线后重连 | 有组件环回测试，但 Android App 未实例化 reconnector；macOS `startConnect` 仍只日志 | **组件完成 ≠ 产品完成**【已确认】 |
| HYP-M2-015、HYP-M6-006：协议文档与代码匹配/冻结 | 配对序列、text direction、payload strictness 均不匹配 | **Tracker/文档漂移**【已确认】 |
| 协议与安装文档称 QR/manual endpoint 均可完成配对 | Android manual endpoint 只做 TCP probe | **文档/代码漂移**【已确认】 |
| ADR-0001 要求文件 resume、progress、cancel | resume 仅内存；progress 是 queued 而非 ACK；cancel 不停止 sender；接收文件被删 | **Scope/实现漂移**【已确认】 |
| HYP-M5-003 `[x]`：DMG/ZIP “Install path tested” | 打包脚本未复制运行时必需的 SwiftPM resource bundle | **Tracker/打包漂移**【已确认】 |
| 排错文档称 trust reset 后旧 resume token 应丢弃 | 生产 revoke 路径未统一调用 `ResumeTokenStore.invalidatePeer` | **文档/代码漂移**【已确认】 |
| 排错文档让用户等待重连并保持前台通知 | Android 没有 transport FGS；macOS connect callback 是占位 | **文档描述超前于实现**【已确认】 |
| `minSdk=26` 暗示 API 26+ 可运行 | TLS 层明确 API 26–28 无法使用当前 TLS 1.3 方案；所引用 ADR-0002 不存在 | **构建配置/架构决策漂移**【已确认】 |
| Tracker `[x]` 定义包含相关 manual/integration test 已运行 | 兼容性矩阵明确绝大多数场景 `not-run/blocked` | **状态语义漂移**【已确认】 |
| README、安装和许可 ADR 称项目仍为 pre-alpha、无可信公开发行 | 与代码及外部门槛一致 | **无漂移；表述准确**【已确认】 |

---

## 结论

Hyphen 的协议与安全基础并非空壳：SPKI pin、SAS transcript、严格 envelope、帧限额、Keystore/GCM 信任存储、脱敏诊断和一定规模的双端测试均有实际代码支撑。

但当前最高风险不在“是否有协议类”，而在“协议组件是否被正确接入长期运行的产品生命周期”。在完成常驻连接 owner、双端配对提交、fail-closed 通知隐私、可交付文件接收、有效取消、持久 resume、资源配额和真实分发产物之前，不应把该项目判断为 Alpha-ready。
