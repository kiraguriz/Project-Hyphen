# Project Hyphen 排障指南（预 Alpha）

本文面向预 Alpha 测试者。目标是把已知故障面转成可执行检查，同时不假装兼容性矩阵已经完整。

先记住当前证据边界：

- Android 和 macOS 设备矩阵仍有 blocked / not-run 行。
- 部分检查需要真实 Android 设备、额外 Mac 硬件，或明确安排的 sleep/wake 测试窗口。
- discovery 不等于信任。只有 pinned fingerprint 加 SAS 确认才能建立可信 peer。
- 不要把通知正文、文件内容、URL、IP 地址、密码、token 或私人联系方式贴进 bug report。

## 先收集什么

改设置之前，先记录：

- Hyphen commit 或 package checksum。
- Android 设备型号、Android 版本、OEM skin、安装 track。
- Mac 型号和 macOS 版本。
- 网络场景：home Wi-Fi、mesh、hotspot、AP/client isolation、VPN、restricted LAN mode。
- 测试场景：pairing、discovery、notification mirror、dismiss、quick reply、text/link、transfer、wake reconnect、diagnostics export。
- 结果：`pass`、`fail`、`blocked` 或 `not-run`。

使用 [Compatibility Matrix](compatibility-matrix.md) 里的模板。有 redacted diagnostics export 时可以附上。

## Local Network 权限

### Android

症状：

- "Find my Mac" 找不到设备。
- manual endpoint connect 很快失败。
- Android 16 restricted-LAN 测试阻断 NSD 或 socket。
- Android 17 local-network permission 被拒绝或尚未授予。

检查：

1. 先使用 QR/manual pairing。LAN denial 是受支持的 degraded mode。
2. 确认手机和 Mac 在同一个网络。
3. 本次测试先关闭会隔离本地流量的 VPN 或 private DNS profile。
4. Android 16 restricted-LAN 测试按
   [Android 16 Restricted Local Network Mode](test-plans/android-16-restricted-lan.md)
   执行，并记录该设备上实际可用的 compat-toggle 命令。
5. Android 17 或 SDK 37 测试构建中，只在用户主动触发 discovery/pairing 流程时授予 Local Network 权限。拒绝后使用 QR/manual fallback。

不要为了绕过 LAN 失败而新增 location、SMS、Call Log 或 Accessibility 权限。ADR-0003 已明确拒绝这条路线。

### macOS

症状：

- Local Network prompt 从未出现。
- 用户拒绝 Local Network 后 advertising/browsing 失败。
- System Settings 里看不到 Hyphen。

检查：

1. Hyphen 不应在启动时触发 OS prompt。先点击会使用 local network 的动作，例如 advertising 或 discovery。
2. 如果权限被拒绝，使用 QR/manual pairing，或在 System Settings -> Privacy & Security -> Local Network 中启用 Hyphen。
3. 如果列表里没有 Hyphen，退出并重新启动 Hyphen，再次点击 local-network 动作，然后重新检查 System Settings。
4. 在 compatibility matrix 中记录 macOS 版本和 prompt 行为。

## mDNS / Bonjour Discovery

症状：

- Android 找不到 Mac service。
- 某个 Wi-Fi 能发现，另一个 Wi-Fi 不能。
- discovery 结果像是 stale 或 duplicate peer。

检查：

1. 把 mDNS 当作加速路径。失败时继续使用 QR/manual。
2. 确认 Mac 已开始 advertising `_hyphen._tcp`。
3. baseline run 中让两台设备处于同一个 SSID/VLAN。
4. 调 mesh 或企业 Wi-Fi 之前，先在普通 home Wi-Fi 上重试。
5. 如果网络启用了 AP/client isolation，预期 discovery 会失败；此时记录 QR/manual fallback 行为。
6. SAS 确认成功前，不信任任何 discovery 出来的 service。

网络场景请记录到 matrix：home Wi-Fi、mesh、hotspot、AP/client isolation、Android restricted LAN、permission denied 或 Wi-Fi switch。

## Pairing 与 Trust

症状：

- SAS code 不一致。
- 之前配对过的设备无法再连接。
- discovery 里出现不该配对的设备。

检查：

1. SAS code 不一致时，两端都拒绝 pairing，不要继续。
2. 使用 "Manage paired devices" 忘记 peer，然后重新配对。
3. 确认显示的 endpoint 或 QR payload 来自你要配对的那台 Mac。
4. 如果发生 trust reset，旧的 resume token 和 session 应该被丢弃。

## Wake、Sleep 与网络切换

症状：

- Mac 唤醒后 Android session 没回来。
- Wi-Fi 切换后 UI 卡在看似 connected 的状态。
- reconnect 一直重试，却没有清晰的 degraded state。

检查：

1. wake 或网络切换后等待最多 30 秒。
2. 确认 Hyphen 显示为已重连，或显示清晰的 degraded/error state。
3. 如果状态 stale，重启本地 app surface 并导出 diagnostics。
4. 在 roadmap tracker 的 wake/reconnect 模板中记录 sleep duration、wake reconnect time、fallback path 和 pass/fail。

最终的 20-cycle sleep/wake 验收测试尚未完成。如果缺少的是人工安排或设备覆盖，请标记 `blocked`；不要把“没有观察过”写成通过。

## Android OEM 后台限制

症状：

- 手机息屏后连接断开。
- battery saver 开启后通知停止。
- Pixel 正常，但 Samsung、Xiaomi、OnePlus/Oppo 或其他 OEM skin 异常。

检查：

1. 当 Hyphen connection/foreground notification surface 存在时，保持它可见；它是隐私和可靠性模型的一部分。
2. 对测试设备，在 OEM battery settings 里允许 Hyphen background activity。
3. 先在 battery saver 关闭时测试，再单独记录 battery-saver run。
4. 在 [Compatibility Matrix](compatibility-matrix.md) 中记录准确的 OEM skin、settings path 和行为。
5. 如果某个 OEM 仍然杀掉连接，把它作为 OEM-specific matrix finding，不要因此扩大权限面。

## Notifications

症状：

- 通知没有镜像。
- update 造成重复通知。
- dismiss 或 quick reply 不工作。

检查：

1. 确认 Android Settings 中已授予 Notification Listener access。
2. 确认源 app 确实在 Android 上发出了通知。
3. 排查重复时，记录 Android notification key 行为。Hyphen 使用 `StatusBarNotification.getKey()` 作为稳定身份。
4. Quick Reply 只属于 beta：只有源通知提供受支持的 `RemoteInput` action 时才应显示。
5. bug report 中不要包含通知内容；使用 redacted diagnostics。

## File 与 Text/Link Transfer

症状：

- text/link send 没出现在另一端。
- file transfer 被中断后停止。
- 大文件 transfer 无法 resume。

检查：

1. 确认两端已配对，且 session 不处于 degraded 状态。
2. 确认接收端用户确认弹窗已接受。
3. 文件传输问题要记录方向、大小、网络、中断点、resume 行为和 hash verification result。
4. 1GB 多设备验收尚未完成；请记录真实设备覆盖，不要推断它已经通过。

## 什么时候标记 Blocked

当缺失项在当前 repo 之外时，用 `blocked`：

- 没有连接 Android 设备或模拟器。
- 缺少目标 OS 版本或 OEM 设备。
- 缺少 Apple Developer ID 或 notary 凭据。
- 缺少 Android release/upload keystore。
- 缺少 Play/F-Droid 账号或 review access。
- sleep/wake 测试尚未和人类用户安排。

如果本地测试失败并给出具体错误，请先记录完整错误输出，再修复这个错误。
