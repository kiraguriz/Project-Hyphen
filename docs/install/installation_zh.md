# Project Hyphen 安装指南（预 Alpha）

Project Hyphen 仍处于预 Alpha 阶段。本文面向从本仓库本地构建的维护者和技术测试者，不代表已经可公开信任发布。

请配合当前进度表使用：
[roadmap tracker](../project_hyphen_roadmap_tracker_v0_3.md)。
排查失败请参考 [Troubleshooting](../troubleshooting_zh.md)。

## 当前安装路径

| 平台 | 当前路径 | 公开发布状态 |
|---|---|---|
| macOS | 通过 `packaging/macos/package-local.sh` 生成本地 ZIP/DMG | 默认 ad-hoc 签名，尚未 notarize |
| Android debug | 通过 Gradle 生成本地 debug APK | 仅供开发/测试安装 |
| Android release dry run | 通过 `packaging/android-play/build-release.sh` 生成 APK/AAB | 未配置外部签名变量时不是 Play-ready |
| F-Droid | 仅有 metadata 草稿 | 处于 disabled 状态，尚不能提交 |

## 前置条件

- macOS 14 或更高版本，用于菜单栏 App。
- Xcode Command Line Tools，包括 `swift`、`codesign`、`hdiutil`、`ditto`。
- Android SDK 和 JDK 17，用于 Android 构建。
- ADB 以及一台 Android 设备或模拟器，用于 Android 安装测试。
- 配对和传输测试需要 Mac 与 Android 在同一局域网内。

不要把签名 key、keystore、密码、生成的发布产物或复制出来的签名日志提交到仓库。

## macOS 本地包

在仓库根目录运行：

```bash
./packaging/macos/package-local.sh
```

预期产物：

- `packaging/macos/build/Hyphen-macOS-0.0.1.zip`
- `packaging/macos/build/Hyphen-macOS-0.0.1.dmg`
- `packaging/macos/build/SHA256SUMS`

校验 checksum：

```bash
cd packaging/macos/build
shasum -a 256 -c SHA256SUMS
```

本地 smoke test 安装步骤：

1. 打开 `Hyphen-macOS-0.0.1.dmg`，或解压
   `Hyphen-macOS-0.0.1.zip`。
2. 将 `Hyphen.app` 拷贝到 `/Applications`，也可以直接从挂载目录/临时目录运行做短测试。
3. 启动 `Hyphen.app`。
4. 确认菜单栏出现 Hyphen 图标/菜单项。

默认包使用 ad-hoc 签名（`SIGN_IDENTITY=-`），只适合验证本地打包路径。它尚未 notarize，在其他机器上可能被 macOS Gatekeeper 警告或拦截。

如需 Developer ID 签名，先在本机安装有效证书，然后运行：

```bash
SIGN_IDENTITY="Developer ID Application: Example Team (TEAMID1234)" \
./packaging/macos/package-local.sh
```

notarization 仍是单独步骤：

```bash
SIGN_IDENTITY="Developer ID Application: Example Team (TEAMID1234)" \
NOTARY_PROFILE="hyphen-notary" \
./packaging/macos/notarize-dry-run.sh
```

如果缺少 Developer ID 或 notary 凭据，notarization 脚本会以 `BLOCKED` 信息退出。凭据只应保存在本机 keychain 或一次性环境变量里。

## Android Debug 安装

本地设备测试优先使用 debug APK：

```bash
cd apps/android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

这个路径需要已连接的 Android 设备或模拟器，并开启 USB debugging。当前 app id 是 `dev.hyphen.android`。

卸载：

```bash
adb uninstall dev.hyphen.android
```

## Android Release Dry Run

在仓库根目录运行：

```bash
./packaging/android-play/build-release.sh
```

预期输出目录：

- `packaging/android-play/build/`

脚本会把 release APK/AAB 复制到该目录，并写入 `SHA256SUMS`。如果没有配置签名环境变量，这些产物只是 dry-run 输出，不是 Play-ready 发布包。

校验 checksum：

```bash
cd packaging/android-play/build
shasum -a 256 -c SHA256SUMS
```

如需签名 release build，运行脚本前必须同时设置四个外部签名变量：

```bash
HYPHEN_ANDROID_KEYSTORE="/secure/path/hyphen-upload.jks" \
HYPHEN_ANDROID_KEY_ALIAS="hyphen-upload" \
HYPHEN_ANDROID_KEYSTORE_PASSWORD="..." \
HYPHEN_ANDROID_KEY_PASSWORD="..." \
./packaging/android-play/build-release.sh
```

只要设置了任意一个签名变量，就必须四个全部设置；部分配置会 fail closed。不要把这些值写入仓库。

## F-Droid 状态

`packaging/android-fdroid/metadata/dev.hyphen.android.yml` 目前是 disabled 草稿，还不是安装来源。提交前还需要公开 repo URL、issue tracker、release tag、最终 app 名称、根目录 license 文件、依赖审计以及签名/更新策略。

## 首次运行注意事项

- 配对坚持 local-first：QR/manual endpoint fallback 加 SAS 确认。
- mDNS/Bonjour discovery 只是便利发现路径，不等于信任。
- 只有 pinned fingerprint 加 SAS 确认才能建立信任。
- Android 通知镜像需要用户主动授予 Notification Listener 权限。
- macOS Local Network 权限应只在可见用户操作之后触发。
- Hyphen 默认不使用账号、云 relay、SMS、Call Log、Accessibility 或 telemetry。

## 已知预 Alpha 阻塞项

- macOS 公开分发仍需要 Developer ID 签名、notarization、stapling 证据。
- Android 公开分发仍需要外部签名 key 以及 Play/F-Droid track review。
- 设备和 OS 兼容性矩阵尚未完成。
- 部分验收仍需要真实 Android 设备、额外 Mac 硬件、beta 用户，或明确安排的 sleep/wake 测试窗口。
