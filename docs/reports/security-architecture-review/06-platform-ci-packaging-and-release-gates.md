# 06 平台、CI、打包与发布门禁审计

## 范围

本分报告只覆盖平台支持、CI 门禁、macOS/Android 打包、许可证/发布包完整性、兼容性矩阵和外部发布门槛。源输入 `docs/Project-Hyphen-security-architecture-review.md` 只作为未采信线索；结论以当前仓库文件为准。

不覆盖传输协议正确性、配对状态机、通知隐私、文件传输复杂度或 UI 交互缺陷，除非它们直接影响本分类的发布门禁。

## 覆盖的源评审声明

- J 发布就绪度与文档：源文档指出 README/安装文档仍把项目定义为 pre-alpha，本地包未签名/未 notarize，外部门槛未关闭；并指出 macOS package script 未复制 SwiftPM resource bundle，许可证发布包不完整。源位置：`docs/Project-Hyphen-security-architecture-review.md:397-426`。
- H-06：Android `minSdk=26` 与当前 TLS 1.3/API 29+ floor 冲突。源位置：`docs/Project-Hyphen-security-architecture-review.md:532-545`。
- H-08：macOS `package-local.sh` 遗漏 SwiftPM resource bundle。源位置：`docs/Project-Hyphen-security-architecture-review.md:560-571`。
- M-09：CI 在平台检查被 skip 时仍可能成功。源位置：`docs/Project-Hyphen-security-architecture-review.md:691-702`。
- M-10：许可证决策已有，但仓库不是完整许可分发包。源位置：`docs/Project-Hyphen-security-architecture-review.md:704-710`。
- 风险登记与修复路线中有关全仓测试、API 26-28、macOS bundle 启动、设备矩阵、Developer ID/notarization/Play/F-Droid 的条目。源位置：`docs/Project-Hyphen-security-architecture-review.md:743-753`、`docs/Project-Hyphen-security-architecture-review.md:797-828`。
- tracker/doc drift 中有关 HYP-M5-003、`minSdk=26`、矩阵 `not-run/blocked` 的条目。源位置：`docs/Project-Hyphen-security-architecture-review.md:842-846`。

## 审计结论表

| ID | 源声明 / 可执行问题 | 判定 | 当前结论 |
|---|---|---|---|
| 06-01 | README/安装文档诚实标注 pre-alpha、本地构建、非签名/非商店发布 | confirmed | 当前文档确实把公开发布与本地 dry run 分开，结论成立。 |
| 06-02 | Android `minSdk=26` 与 TLS 1.3/API 29+ floor 冲突 | confirmed | 构建配置允许 API 26 安装，传输层和协议文档要求 TLS 1.3 且说明 API 26-28 缺平台支持。实际设备失败形态仍是 environment-only。 |
| 06-03 | H-08 macOS 本地包遗漏 SwiftPM resource bundle | confirmed | `Package.swift` 声明资源，`Localization.swift` 依赖 `Bundle.module`，`package-local.sh` 只创建 `Contents/Resources` 目录但没有复制 SwiftPM 生成的 bundle。包启动后的具体表现未在本审计执行，属 environment-only。 |
| 06-04 | M-09 CI 可在平台检查 skip 时成功 | confirmed | GitHub Actions 只在 Ubuntu 跑非 strict `./scripts/check.sh`；脚本的 `skip_check` 只在 `--strict` 下失败。因此缺 macOS toolchain 时可绿色通过。需窄化：Android unit test 在当前 repo 中不会被 skip，因为 `apps/android/gradlew` 存在。 |
| 06-05 | M-10 许可证发布包不完整 | confirmed | ADR/README/CONTRIBUTING/审计报告均承认缺 root license files、license map、SPDX/NOTICE/DCO-or-CLA 决策；仓库根目录也没有 `LICENSE*` / `NOTICE*` 文件。 |
| 06-06 | 兼容性矩阵不是已完成证明 | confirmed | 矩阵明确是覆盖目标和日志模板，主要行仍为 `not-run` 或 `blocked`。不能把它当作 Alpha/RC 设备证明。 |
| 06-07 | Developer ID、notarization、Android release key、Play/F-Droid、设备矩阵是外部门槛 | environment-only | 仓库文档已正确列出 blocker，但这些需要账号、证书、密钥、商店/渠道访问和真实设备，不能从源码静态证明关闭。 |
| 06-08 | HYP-M5-003 `[x]` 的 “Install path tested” 与当前打包资源缺口冲突 | confirmed | Tracker 记录了 package-local/DMG/codesign 通过，但没有资源 bundle/本地化 smoke 证据；在 H-08 修复并补证明前，`[x]` 语义过强。 |
| 06-09 | HYP-M6-008/009/010 存在同类“完成态”漂移 | disputed | 当前 tracker 已将 HYP-M6-008/009/010 标为 `[?]` 并列出许可证、RC、tag、外部发布门槛 blocker；不需要降级，只需要保持 blocked 并补齐证据后再关闭。 |
| 06-10 | ADR-0002 可作为 TLS floor 决策依据 | unsupported | 当前 `docs/adr/` 没有 ADR-0002。代码注释提到“ADR-0002 decision”是陈旧/悬空引用；当前协议文档只说变更 TLS floor、提高 `minSdk` 或新增 TLS 依赖需要“new ADR”。 |

## 证据记录

- 发布状态：README 明确 `Status: pre-alpha`，并说明没有 signed、notarized 或 store-ready public release，只能按 install docs 做 local maintainer/tester builds：`README.md:3-5`。安装文档同样列出 macOS local ZIP/DMG 默认 ad-hoc、Android release dry run 未配置外部签名、F-Droid metadata draft only：`docs/install/installation_en.md:11-18`。
- Android floor：Gradle 当前 `minSdk = 26`、`targetSdk = 36`：`apps/android/app/build.gradle.kts:29-35`。TLS endpoint 固定 `TLSv1.3`，注释说明 Android API 26-28 会在 `SSLContext.getInstance` 处失败且不静默降级：`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/TlsEndpoint.kt:11-31`。协议文档也写明 API 26-28 缺平台 TLS 1.3 支持，变更 floor/提高 minSdk/引入 TLS 依赖需要新 ADR：`docs/protocol/hyphen-protocol-v0.md:418-422`。当前 ADR 列表没有 `docs/adr/0002-*.md`，只有 0001、0003、0004、0005、0006、0007。
- macOS resource bundle：SwiftPM `HyphenApp` target 声明 `defaultLocalization: "zh-Hans"` 和 `resources: [.process("Resources")]`：`apps/macos/Package.swift:7-31`。本地化 helper 明确通过 `NSLocalizedString(..., bundle: .module)` 读取 `HyphenApp` resource bundle：`apps/macos/Sources/HyphenApp/Localization.swift:3-18`。`package-local.sh` 运行 `sign-local.sh` 和 `swift build --show-bin-path`，随后只复制 `$SOURCE` 到 `Contents/MacOS/$APP_NAME`，虽然创建了 `Contents/Resources`，但没有复制 SwiftPM resource bundle，再执行 codesign：`packaging/macos/package-local.sh:27-48`、`packaging/macos/package-local.sh:89-96`。
- CI 门禁：GitHub Actions 只有一个 `ubuntu-latest` job，运行非 strict `./scripts/check.sh`：`.github/workflows/checks.yml:11-18`。`scripts/check.sh` 的 `skip_check` 仅在 `--strict` 时置失败：`scripts/check.sh:21-26`。macOS 分支在无 Xcode/Swift 时调用 `skip_check "Xcode toolchain not available on this machine"`：`scripts/check.sh:72-83`，而 summary 在非 strict 下仍说 available checks passed 且可能有 SKIP：`scripts/check.sh:96-102`。
- 许可证发布包：README 说明 license decision 已记录，但 formal root license files、SPDX sweeps、contribution terms 仍需落地：`README.md:49-51`。ADR-0005 明确“without adding root license files yet”，并列出 root license files、license map、SPDX、NOTICE、DCO/CLA 等发布前步骤：`docs/adr/0005-license-and-clean-room-policy.md:15-18`、`docs/adr/0005-license-and-clean-room-policy.md:32-42`。HYP-M6-008 审计报告也把这些列为 public-release blocker：`docs/reports/hyp-m6-008-dependency-license-audit.md:100-107`。
- 兼容性矩阵：矩阵声明自身是 coverage targets and log templates，不是完成结果：`docs/compatibility-matrix.md:1-5`。Android、macOS、network、scenario 主要行仍为 `not-run`，sleep/wake 行为 `blocked`：`docs/compatibility-matrix.md:20-29`、`docs/compatibility-matrix.md:31-38`、`docs/compatibility-matrix.md:40-50`、`docs/compatibility-matrix.md:52-67`。Evidence log 记录了缺 Android 设备/额外 Mac 组合和 paired Android session 的 blocker：`docs/compatibility-matrix.md:69-75`。
- 外部发布门槛：Public Beta checklist 明确 real public beta blocked，stop conditions 包括 macOS 未 Developer ID signed/notarized、Android 未 release sign、F-Droid metadata placeholder 等：`docs/release/public-beta-checklist.md:1-11`、`docs/release/public-beta-checklist.md:13-25`。Required External Gates 表列出 Apple Developer Program、Developer ID certificate、notary credentials、Android release/upload keystore、Play account/review、F-Droid path、physical device coverage、sleep/wake session：`docs/release/public-beta-checklist.md:27-38`。当前状态仍 blocked 到 Developer ID/notary、Android release signing、beta channel access 可用：`docs/release/public-beta-checklist.md:172-177`。
- Android Play/F-Droid：Android release dry run 在无 signing env 时输出 unsigned dry run，最后报告 release signing key not configured / artifacts are not Play-ready：`packaging/android-play/build-release.sh:31-35`、`packaging/android-play/build-release.sh:56-59`。Play README 也要求外部 keystore，缺签名不是 Play-ready：`packaging/android-play/README.md:7-17`、`packaging/android-play/README.md:19-37`。F-Droid README 说明 metadata draft disabled，直到 public URL、issue tracker、release tag、formal root license files、dependency audit、signing/update strategy 完成才可提交：`packaging/android-fdroid/README.md:7-10`、`packaging/android-fdroid/README.md:18-25`。
- Tracker 状态：`[x]` 的定义包含 acceptance criteria met、tests run、docs/ADR updated：`docs/project_hyphen_roadmap_tracker_v0_3.md:13-20`，DoD 还要求相关 unit/integration/manual tests 已运行、failure modes handled、tracker row updated：`docs/project_hyphen_roadmap_tracker_v0_3.md:30-39`。HYP-M5-003 当前为 `[x]` 且验收是 Install path tested：`docs/project_hyphen_roadmap_tracker_v0_3.md:207-220`。进度日志记录 package-local、ZIP/DMG、checksums、hdiutil、codesign 通过，但只说 mounted DMG contained executable `Hyphen.app`，未记录 resource bundle/本地化 smoke：`docs/project_hyphen_roadmap_tracker_v0_3.md:441-441`。HYP-M6-008/009/010 当前均为 `[?]` 且 blocker 明确：`docs/project_hyphen_roadmap_tracker_v0_3.md:224-237`、`docs/project_hyphen_roadmap_tracker_v0_3.md:451-457`。

## 修复计划

### P0：修复本地包资源契约并纠正 HYP-M5-003 证据

- 可能触达文件/模块：`packaging/macos/package-local.sh`、`packaging/macos/README.md`、`docs/install/installation_en.md`、`docs/install/installation_zh.md`、`docs/project_hyphen_roadmap_tracker_v0_3.md`；如需自动 smoke，可新增或扩展 macOS packaging verification 脚本。
- 工作内容：在 `swift build` 后定位 SwiftPM 生成的 `Hyphen_HyphenApp.bundle` 或实际 resource bundle 名称，复制到 `Hyphen.app/Contents/Resources/`，再 codesign bundle。补充 clean-user launch/localization smoke；若短期不修，应把 HYP-M5-003 从 `[x]` 降为 `[~]` 或拆分为“ZIP/DMG 生成完成”和“安装路径/资源 smoke 未完成”。
- 验收标准：`.app/Contents/Resources/` 包含 SwiftPM resource bundle；`Bundle.module` 能解析 zh-Hans/en 本地化 key；ad-hoc package 能启动并显示 menu-bar item；HYP-M5-003 的 tracker 状态只在资源/启动证明齐全后为 `[x]`。
- 验证命令/人工证明：`./packaging/macos/package-local.sh`；`find packaging/macos/build/staging -maxdepth 5 -name '*HyphenApp*.bundle' -o -name 'Localizable.strings'`；`codesign --verify --strict --verbose=2 packaging/macos/build/staging/Hyphen-macOS/Hyphen.app`；`shasum -a 256 -c packaging/macos/build/SHA256SUMS`；`hdiutil verify packaging/macos/build/Hyphen-macOS-0.0.1.dmg`；在干净用户或临时 HOME 下 launch，截图或 UI tree 证明菜单栏项和本地化字符串正常。

### P0：解决 Android API floor 与 TLS 1.3 冲突

- 可能触达文件/模块：`apps/android/app/build.gradle.kts`、`apps/android/app/src/main/kotlin/dev/hyphen/android/transport/TlsEndpoint.kt`、`docs/protocol/hyphen-protocol-v0.md`、`docs/compatibility-matrix.md`、`docs/project_hyphen_roadmap_tracker_v0_3.md`，以及一份新的 ADR。
- 工作内容：二选一并记录决策。路线 A：将 `minSdk` 提高到 29，更新安装/兼容性文档，避免 API 26-28 设备安装后核心配对失败。路线 B：新增正式 ADR，定义 TLS 1.2 fallback 或 provider 方案、cipher/profile、降级防护、依赖许可和 API 26-28 设备矩阵；实现前不得把 API 26-28 宣称为可用。
- 验收标准：支持矩阵、Gradle minSdk、协议 TLS floor、安装文档和 tracker 一致；API 26-28 要么无法安装/明确 unsupported，要么有真实设备或 emulator 证据证明配对/传输按 ADR 安全运行；陈旧的 `ADR-0002` 注释被删除或替换为实际 ADR 编号。
- 验证命令/人工证明：`cd apps/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug`；`./scripts/test-protocol.sh`；API 26/27/28 emulator 或真机安装/配对日志；API 29+ happy path 回归；更新 `docs/compatibility-matrix.md` 的具体设备、版本、build、结果和证据。

### P0：把 CI 从“available checks”提升为平台门禁

- 可能触达文件/模块：`.github/workflows/checks.yml`、`scripts/check.sh`、`packaging/macos/package-local.sh`、`packaging/android-play/build-release.sh`，必要时新增 release/packaging smoke 脚本。
- 工作内容：拆分 Linux Android/protocol job 与 macOS Swift/packaging job；CI 使用 `./scripts/check.sh --strict` 或等价 strict mode；macOS job 必须运行 `swift test` 和 package/localization smoke；缺平台 toolchain 时 CI fail，而不是 SKIP 绿灯。Android release dry run 可保留 unsigned blocker 文案，但 packaging check 要验证产物和 checksum。
- 验收标准：PR/merge gate 中 macOS toolchain 缺失、Swift test 失败、resource bundle 缺失、package smoke 失败都会 red；Ubuntu job 继续覆盖 Android unit/protocol/markdown/secrets；CI run summary 区分 local dry run、platform proof、external release gates。
- 验证命令/人工证明：本地 `./scripts/check.sh --strict`；GitHub Actions run ID，含 Ubuntu job 和 macOS job；macOS job artifact listing；故意隐藏 Swift 或 bundle copy 的负例能使 job 失败。

### P1：补齐许可证与 release package 法务元数据

- 可能触达文件/模块：根 `LICENSE*` / `NOTICE*` 或 `LICENSES/`，top-level license map，`README.md`、`CONTRIBUTING.md`、`docs/adr/0005-license-and-clean-room-policy.md`、`docs/reports/hyp-m6-008-dependency-license-audit.md`、`docs/project_hyphen_roadmap_tracker_v0_3.md`。
- 工作内容：落地 MPL-2.0、Apache-2.0、CC-BY-4.0 正式文本；定义树级 license map；决定 NOTICE 策略和 SPDX/header policy；在接受外部贡献前决定 DCO vs CLA。
- 验收标准：HYP-M6-008 不再被 root license files/map/notice/SPDX/DCO-or-CLA 阻塞；F-Droid metadata 的 license 字段和仓库实际文件一致；第三方/vendored 文件 notice 可追踪。
- 验证命令/人工证明：`rg --files | rg '(^|/)(LICENSE|LICENSES|NOTICE|COPYING|THIRD_PARTY)'`；依赖/license audit 重跑；F-Droid metadata lint 或等价 YAML/license 检查；人工 legal/release review sign-off。

### P1：把外部发布门槛转为可审计证据包

- 可能触达文件/模块：`docs/release/public-beta-checklist.md`、`packaging/macos/README.md`、`packaging/macos/notarization-notes.md`、`packaging/android-play/README.md`、`packaging/android-fdroid/README.md`、`docs/compatibility-matrix.md`、M6 release reports。
- 工作内容：继续把 Developer ID/notary、Android release/upload key、Play/F-Droid access、真实设备矩阵作为 environment-only gate；拿到凭据/账号/设备后只记录 redacted proof，不把密钥、账号细节、notary private log 或商店敏感内容入库。
- 验收标准：macOS artifact 有 Developer ID signature、notary success、stapling policy/结果；Android release APK/AAB 用批准 key 签名；Play/F-Droid metadata 与实际包一致；public beta checklist 的 stop condition 全部关闭或明确 no-go。
- 验证命令/人工证明：`SIGN_IDENTITY="Developer ID Application: ..." NOTARY_PROFILE="..." ./packaging/macos/notarize-dry-run.sh`；`spctl --assess --type execute --verbose`；`stapler validate` 或明确记录不 stapling 的发行策略；`HYPHEN_ANDROID_* ./packaging/android-play/build-release.sh`；Play/F-Droid review/lint evidence 的 redacted 摘要；checksum 文件和 release artifact list。

### P1：填充兼容性矩阵，而不是从本地绿灯推断

- 可能触达文件/模块：`docs/compatibility-matrix.md`、`docs/test-plans/android-16-restricted-lan.md`、`docs/test-plans/hyp-m3-015-1gb-transfer-test.md`、`docs/test-plans/hyp-m6-002-crash-free-beta-sessions.md`、M6 release reports。
- 工作内容：按矩阵行逐项记录真实设备、OS、build、网络、场景、结果、redacted evidence。优先覆盖 API floor 决策、Android restricted LAN、OEM battery restriction、20 次 sleep/wake、1 GiB interruption/resume。
- 验收标准：每个发布声明都有对应矩阵行或 release checklist evidence；`not-run/blocked` 不再被 release note 或 tracker `[x]` 包装成 pass。
- 验证命令/人工证明：设备/模拟器清单、ADB 安装/配对日志、macOS `sw_vers`、sleep/wake timing log、1 GiB hash 记录、截图或 UI tree；所有证据 redacted 且不含通知正文、文件内容、私有 IP/URL 或账号资料。

### P2：发布工程硬化

- 可能触达文件/模块：CI workflow、packaging scripts、release checklist、license audit tooling、F-Droid metadata。
- 工作内容：增加 artifact manifest/SBOM 或 release bill of materials；把 resource-bundle smoke、checksum verification、metadata lint、license map check 纳入可重复脚本；为 GitHub release/F-Droid/Play 各自输出最小证据模板。
- 验收标准：维护者能从单一 release checklist 复现 artifact、checksum、license map、platform smoke 和外部 gate 状态；失败时输出可定位、可 redacted 分享的诊断。
- 验证命令/人工证明：release dry-run script 输出 manifest；CI 上传 redacted artifact list；F-Droid/Play/macOS 三条渠道各自有 pass/block/no-go 状态。

## 开放问题 / environment-only gates

- API 26/27/28 的实际失败异常、provider 行为和用户可见错误文案尚未在本审计中运行；当前只确认配置/协议冲突。
- macOS package 修复前后的实际 launch、本地化 fallback、干净用户环境行为需要运行 `package-local.sh` 并启动 `.app` 证明；本审计未生成或启动包。
- Developer ID certificate、notary credentials、Android release/upload keystore、Play/F-Droid account/review access 不在仓库中，必须由维护者在安全环境提供并只提交 redacted proof。
- 设备矩阵、OEM 后台限制、restricted LAN、20 次 sleep/wake、1 GiB resume 需要真实设备/网络/人类排程；不能由源码静态审计关闭。
- HYP-M6-008/009/010 当前 blocked 状态是正确的；后续不要为了“完成率”降噪而改成 `[x]`，除非对应 release/license/RC/tag 证据已经落地。
