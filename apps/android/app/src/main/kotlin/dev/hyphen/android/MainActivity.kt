package dev.hyphen.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.hyphen.android.companion.AssociationController
import dev.hyphen.android.companion.AssociationEvent
import dev.hyphen.android.companion.CdmAssociationBackend
import dev.hyphen.android.diagnostics.DiagnosticProtocolSessionListener
import dev.hyphen.android.diagnostics.LocalStructuredLogStore
import dev.hyphen.android.diagnostics.RedactedDiagnosticsExporter
import dev.hyphen.android.discovery.AndroidMulticastLockHandle
import dev.hyphen.android.discovery.AndroidNsdBackend
import dev.hyphen.android.discovery.DiscoveryEvent
import dev.hyphen.android.discovery.DiscoveryManager
import dev.hyphen.android.discovery.HandlerScheduler
import dev.hyphen.android.discovery.ScopedMulticastLock
import dev.hyphen.android.notifications.HyphenNotificationListenerRuntime
import dev.hyphen.android.notifications.NotificationCapabilityGate
import dev.hyphen.android.notifications.NotificationAccessController
import dev.hyphen.android.notifications.NotificationDismissRequestHandler
import dev.hyphen.android.notifications.NotificationPrivacyMode
import dev.hyphen.android.notifications.NotificationPrivacyPolicyHandler
import dev.hyphen.android.notifications.NotificationProtocol
import dev.hyphen.android.notifications.NotificationReplyRequestHandler
import dev.hyphen.android.notifications.ProtocolSessionNotificationOutbox
import dev.hyphen.android.pairing.EndpointConnectProbe
import dev.hyphen.android.pairing.EndpointParser
import dev.hyphen.android.pairing.PairingTranscript
import dev.hyphen.android.pairing.ParseResult
import dev.hyphen.android.pairing.ParsedEndpoint
import dev.hyphen.android.pairing.SasConfirmationGate
import dev.hyphen.android.text.ProtocolSessionTextLinkOutbox
import dev.hyphen.android.text.TextLinkConfirmationRequest
import dev.hyphen.android.text.TextLinkKind
import dev.hyphen.android.text.TextLinkMessage
import dev.hyphen.android.text.TextLinkReceiver
import dev.hyphen.android.text.TextLinkSender
import dev.hyphen.android.transfer.ProtocolSessionTransferOutbox
import dev.hyphen.android.transfer.TransferCancel
import dev.hyphen.android.transfer.TransferCompleted
import dev.hyphen.android.transfer.TransferEvent
import dev.hyphen.android.transfer.FileTransferStorage
import dev.hyphen.android.transfer.StreamTransferByteSource
import dev.hyphen.android.transfer.TransferProgress
import dev.hyphen.android.transfer.TransferProtocol
import dev.hyphen.android.transfer.TransferReceiver
import dev.hyphen.android.transfer.TransferResumeInfo
import dev.hyphen.android.transfer.TransferSender
import dev.hyphen.android.transport.AndroidKeystoreTlsIdentity
import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.HeartbeatMonitor
import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.ProtocolSession
import dev.hyphen.android.transport.SessionHandshake
import dev.hyphen.android.transport.TlsClient
import dev.hyphen.android.trust.AndroidTrustStores
import dev.hyphen.android.trust.TrustedPeer
import java.io.File
import javax.net.ssl.SSLSocket

// Native-view pre-alpha surface. Keep this dependency-free until the first
// formal Android UI framework task decides whether Compose enters the app.
class MainActivity : Activity() {

    private var manager: DiscoveryManager? = null
    private lateinit var log: TextView
    private lateinit var button: Button
    private lateinit var betaDiagnosticsButton: Button
    private val activeStateLock = Any()
    private var activeSession: ProtocolSession? = null
    private var activeCapabilities: SessionHandshake.NegotiatedCapabilities? = null
    private var activeTransferSender: TransferSender? = null
    private var resumeToken: String? = null
    private var lastSessionId: String? = null
    private val textReceiver = TextLinkReceiver()
    private val diagnosticLogs = LocalStructuredLogStore()
    private var lastTransferProgress: TransferProgress? = null
    private val logBuffer = BoundedLineBuffer(MAX_LOG_LINES, "Hyphen 事件流")
    private val workerLock = Any()
    private val activeWorkers = mutableSetOf<Thread>()
    @Volatile
    private var activityDestroyed = false
    private val transferReceiver by lazy {
        TransferReceiver(FileTransferStorage(File(cacheDir, "transfers"))) { progress ->
            updateLastTransferProgress(progress)
            append(transferProgressLine(progress))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        log = bodyText(logBuffer.render()).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(HYPHEN_CARD_2, 14, HYPHEN_HAIR)
        }
        button = hyphenButton("查找 Mac（20 秒）", primary = true) { startWindow() }

        // Manual-endpoint fallback path (HYP-M1-006): works with mDNS
        // disabled or the LAN permission denied. A pasted hyphen://pair
        // payload takes the QR parse path (HYP-M2-010) — same code a
        // camera scan will feed once the scanner-library decision lands.
        val endpointInput = hyphenInput("host:port 或 hyphen://pair")
        val connectButton = hyphenButton("手动连接 / 解析二维码") {
            probeManualEndpoint(endpointInput.text.toString())
        }

        // CDM self-managed association PoC (HYP-M1-007).
        val cdm = AssociationController(CdmAssociationBackend(this), ::renderCdm)
        val associateButton = hyphenButton("创建系统关联") {
            cdm.associate("Hyphen Mac")
        }
        val listButton = hyphenButton("列出并移除系统关联") {
                val ids = cdm.associations()
                append("associations: $ids")
                ids.forEach(cdm::disassociate)
        }
        val managePeersButton = hyphenButton("管理已配对设备") {
            showPeerManagement()
        }

        // Notification listener onboarding (HYP-M3-001): explicit user
        // action, rationale before system settings, no payload forwarding yet.
        val notificationStatusButton = hyphenButton("检查通知使用权") {
            append(notificationAccessLine())
        }
        val notificationSettingsButton = hyphenButton("开启通知镜像", primary = true) {
            showNotificationAccessOnboarding()
        }
        lateinit var notificationPrivacyButton: Button
        notificationPrivacyButton = hyphenButton(
            notificationPrivacyButtonText(HyphenNotificationListenerRuntime.notificationPrivacyMode()),
        ) {
            val next = when (HyphenNotificationListenerRuntime.notificationPrivacyMode()) {
                NotificationPrivacyMode.SHOW_FULL -> NotificationPrivacyMode.HIDE_BODY
                NotificationPrivacyMode.HIDE_BODY -> NotificationPrivacyMode.EXISTS_ONLY
                NotificationPrivacyMode.EXISTS_ONLY -> NotificationPrivacyMode.SHOW_FULL
            }
            HyphenNotificationListenerRuntime.setNotificationPrivacyMode(next)
            notificationPrivacyButton.text = notificationPrivacyButtonText(next)
            append("notification privacy: ${notificationPrivacyStatus(next)}")
        }

        val textInput = hyphenInput("发送文本或链接到 Mac…")
        val sendTextButton = hyphenButton("发送文本 / 链接", primary = true) {
            sendTextLink(textInput.text.toString())
        }
        val sendFileButton = hyphenButton("发送文件") {
            pickFileToSend()
        }
        val cancelTransferButton = hyphenButton("取消当前传输") {
            cancelActiveTransfer()
        }
        val previewDiagnosticsButton = hyphenButton("预览诊断") {
            previewDiagnostics()
        }
        val exportDiagnosticsButton = hyphenButton("导出诊断包", primary = true) {
            exportDiagnostics()
        }
        val deleteDiagnosticsButton = hyphenButton("删除日志") {
            deleteDiagnostics()
        }
        betaDiagnosticsButton = hyphenButton(betaDiagnosticsButtonText()) {
            toggleBetaDiagnostics()
        }

        setContentView(
            buildHyphenHome(
                endpointInput = endpointInput,
                connectButton = connectButton,
                associateButton = associateButton,
                listButton = listButton,
                managePeersButton = managePeersButton,
                notificationStatusButton = notificationStatusButton,
                notificationSettingsButton = notificationSettingsButton,
                notificationPrivacyButton = notificationPrivacyButton,
                textInput = textInput,
                sendTextButton = sendTextButton,
                sendFileButton = sendFileButton,
                cancelTransferButton = cancelTransferButton,
                betaDiagnosticsButton = betaDiagnosticsButton,
                previewDiagnosticsButton = previewDiagnosticsButton,
                exportDiagnosticsButton = exportDiagnosticsButton,
                deleteDiagnosticsButton = deleteDiagnosticsButton,
            ),
        )
    }

    private fun buildHyphenHome(
        endpointInput: EditText,
        connectButton: Button,
        associateButton: Button,
        listButton: Button,
        managePeersButton: Button,
        notificationStatusButton: Button,
        notificationSettingsButton: Button,
        notificationPrivacyButton: Button,
        textInput: EditText,
        sendTextButton: Button,
        sendFileButton: Button,
        cancelTransferButton: Button,
        betaDiagnosticsButton: Button,
        previewDiagnosticsButton: Button,
        exportDiagnosticsButton: Button,
        deleteDiagnosticsButton: Button,
    ): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }
        return ScrollView(this).apply {
            isFillViewport = false
            setBackgroundColor(HYPHEN_CANVAS)
            clipToPadding = true
            setOnApplyWindowInsetsListener { view, insets ->
                val (top, bottom) = systemBarInsets(insets)
                view.setPadding(0, top, 0, bottom)
                insets
            }
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            content.addView(brandHeader())
            content.addView(connectionSummary())
            content.addView(
                card("时间线", "推荐信息流方向：通知、传输、文本与链接在同一上下文中出现。") {
                    addView(timelineRow("微", "#1f9d57", "微信 · 张伟", "晚上一起吃饭吗？", "9:41"))
                    addView(timelineRow("↓", null, "来自 Mac · 设计稿.pdf", "8.2 MB · 已保存到下载", "打开"))
                    addView(timelineRow("↑", null, "链接已发送到 Mac", "github.com/hyphen/spec", "9:21"))
                    addView(textInput, fullWidthParams(top = dp(10)))
                    addView(buttonRow(sendTextButton, sendFileButton), fullWidthParams(top = dp(10)))
                    addView(cancelTransferButton, fullWidthParams(top = dp(10)))
                },
            )
            content.addView(
                card("权限与配对", "逐项说明用途；本地网络发现失败时仍保留二维码或手动地址路径。") {
                    addView(statusRow("本地网络", "在 Wi-Fi 上查找并连接你的 Mac，不扫描互联网。", "本地", button))
                    addView(statusRow("手动配对", "粘贴 host:port 或 hyphen://pair 载荷，走同一条校验链路。", null, null))
                    addView(endpointInput, fullWidthParams(top = dp(10)))
                    addView(connectButton, fullWidthParams(top = dp(10)))
                    addView(statusRow("系统关联", "系统伴侣设备管理器自管理关联 PoC。", "CDM", associateButton))
                    addView(listButton, fullWidthParams(top = dp(10)))
                    addView(managePeersButton, fullWidthParams(top = dp(10)))
                },
            )
            content.addView(
                card("通知镜像", "不保存通知历史；可在完整与隐藏内容之间切换。") {
                    addView(statusRow("通知使用权", "显式跳转系统设置，开启后才读取通知。", "权限", notificationSettingsButton))
                    addView(notificationStatusButton, fullWidthParams(top = dp(10)))
                    addView(notificationPrivacyButton, fullWidthParams(top = dp(10)))
                },
            )
            content.addView(
                card("本地脱敏诊断", "默认本地保存并脱敏。无遥测、不自动上传。") {
                    addView(statusRow("跟踪 ID", "选择加入；仅写入用户主动导出的诊断包。", "可审计", betaDiagnosticsButton))
                    addView(buttonRow(previewDiagnosticsButton, exportDiagnosticsButton), fullWidthParams(top = dp(10)))
                    addView(deleteDiagnosticsButton, fullWidthParams(top = dp(10)))
                },
            )
            content.addView(
                card("本机事件流", "协议、配对、传输与诊断操作会追加到这里。") {
                    addView(log, fullWidthParams())
                },
            )
        }
    }

    private fun brandHeader(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(18))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(markBlock(dp(16), dp(16), dp(5)))
                    addView(
                        View(this@MainActivity).apply { background = rounded(HYPHEN_ACCENT, 3) },
                        LinearLayout.LayoutParams(dp(20), dp(4)).apply { leftMargin = dp(7); rightMargin = dp(7) },
                    )
                    addView(markBlock(dp(11), dp(18), dp(4)))
                },
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "Hyphen—"
                            setTextColor(HYPHEN_TEXT)
                            textSize = 24f
                            typeface = Typeface.DEFAULT_BOLD
                        },
                    )
                    addView(captionText("本地优先 · 可审计 · Mac × Android 持续连接层"))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(14)
                },
            )
        }

    private fun connectionSummary(): View =
        card("MacBook Pro", "同一 Wi-Fi · 延迟 18ms · 通知镜像开启", showTitle = false) {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                View(this@MainActivity).apply { background = rounded(HYPHEN_ACCENT, 99) },
                LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(13) },
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(titleText("已连接"))
                    addView(captionText("MacBook Pro · hyphen/0.3"))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(signalBars())
        }

    private fun card(
        title: String,
        subtitle: String? = null,
        showTitle: Boolean = true,
        body: LinearLayout.() -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(HYPHEN_CARD, 20, HYPHEN_HAIR)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            if (showTitle) {
                addView(titleText(title))
                if (subtitle != null) addView(captionText(subtitle), fullWidthParams(top = dp(3), bottom = dp(12)))
            }
            body()
        }

    private fun timelineRow(icon: String, iconColor: String?, title: String, detail: String, meta: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(HYPHEN_CARD_2, 16, HYPHEN_HAIR)
            addView(
                TextView(this@MainActivity).apply {
                    text = icon
                    gravity = Gravity.CENTER
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    background = rounded(iconColor?.let(Color::parseColor) ?: HYPHEN_SURFACE_3, 10)
                },
                LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(11) },
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(bodyText(title).apply { typeface = Typeface.DEFAULT_BOLD })
                    addView(captionText(detail))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(captionText(meta).apply {
                typeface = Typeface.MONOSPACE
                setTextColor(if (meta == "打开") HYPHEN_ACCENT else HYPHEN_FAINT)
            })
        }.withBottomMargin(dp(8))

    private fun statusRow(title: String, detail: String, badge: String?, control: View?): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(HYPHEN_CARD_2, 16, HYPHEN_HAIR)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            addView(bodyText(title).apply { typeface = Typeface.DEFAULT_BOLD })
                            if (badge != null) addView(pill(badge), LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { leftMargin = dp(8) })
                        },
                    )
                    addView(captionText(detail), fullWidthParams(top = dp(3)))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (control != null) {
                addView(
                    control,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(42),
                    ).apply { leftMargin = dp(12) },
                )
            }
        }.withBottomMargin(dp(8))

    private fun buttonRow(vararg buttons: Button): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEachIndexed { index, view ->
                addView(
                    view,
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        if (index > 0) leftMargin = dp(9)
                    },
                )
            }
        }

    private fun hyphenButton(text: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(42)
            minWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(if (primary) HYPHEN_ACCENT_INK else HYPHEN_TEXT)
            background = rounded(if (primary) HYPHEN_ACCENT else HYPHEN_CARD_2, 12, if (primary) null else HYPHEN_HAIR_2)
            setOnClickListener { onClick() }
        }

    private fun hyphenInput(hintText: String): EditText =
        EditText(this).apply {
            hint = hintText
            setSingleLine(true)
            textSize = 14f
            setTextColor(HYPHEN_TEXT)
            setHintTextColor(HYPHEN_DIM)
            setPadding(dp(13), 0, dp(13), 0)
            minHeight = dp(48)
            background = rounded(HYPHEN_CARD_2, 14, HYPHEN_HAIR)
        }

    private fun titleText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(HYPHEN_TEXT)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = true
        }

    private fun bodyText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(HYPHEN_TEXT)
            textSize = 13f
            includeFontPadding = true
        }

    private fun captionText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(HYPHEN_DIM)
            textSize = 12f
            includeFontPadding = true
        }

    private fun pill(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(HYPHEN_ACCENT_INK)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = rounded(HYPHEN_ACCENT, 6)
        }

    private fun markBlock(width: Int, height: Int, radius: Int): View =
        View(this).apply {
            background = rounded(HYPHEN_SURFACE_3, radius, HYPHEN_HAIR_2)
            layoutParams = LinearLayout.LayoutParams(width, height)
        }

    private fun signalBars(): View =
        LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            val heights = listOf(7, 12, 18)
            heights.forEach { h ->
                addView(
                    View(this@MainActivity).apply { background = rounded(HYPHEN_ACCENT, 1) },
                    LinearLayout.LayoutParams(dp(3), dp(h)).apply { leftMargin = dp(2) },
                )
            }
        }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun fullWidthParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = top
            bottomMargin = bottom
        }

    private fun View.withBottomMargin(bottom: Int): View =
        apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = bottom }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun systemBarInsets(insets: WindowInsets): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            bars.top to bars.bottom
        } else {
            legacySystemBarInsets(insets)
        }

    @Suppress("DEPRECATION")
    private fun legacySystemBarInsets(insets: WindowInsets): Pair<Int, Int> =
        insets.systemWindowInsetTop to insets.systemWindowInsetBottom

    private data class ActiveStateSnapshot(
        val session: ProtocolSession?,
        val capabilities: SessionHandshake.NegotiatedCapabilities?,
        val transferSender: TransferSender?,
        val lastTransferProgress: TransferProgress?,
    )

    private data class ActiveSessionInstall(
        val previousSession: ProtocolSession?,
    )

    private fun activeStateSnapshot(): ActiveStateSnapshot =
        synchronized(activeStateLock) {
            ActiveStateSnapshot(
                session = activeSession,
                capabilities = activeCapabilities,
                transferSender = activeTransferSender,
                lastTransferProgress = lastTransferProgress,
            )
        }

    private fun installActiveState(
        session: ProtocolSession,
        capabilities: SessionHandshake.NegotiatedCapabilities,
        transferSender: TransferSender,
    ): ProtocolSession? =
        synchronized(activeStateLock) {
            val previous = activeSession
            activeSession = session
            activeCapabilities = capabilities
            activeTransferSender = transferSender
            lastTransferProgress = null
            previous
        }

    private fun installActiveStateAndBindOutboxIfAlive(
        session: ProtocolSession,
        capabilities: SessionHandshake.NegotiatedCapabilities,
        transferSender: TransferSender,
    ): ActiveSessionInstall? =
        synchronized(workerLock) {
            if (activityDestroyed) return@synchronized null
            val previous = installActiveState(session, capabilities, transferSender)
            if (NotificationCapabilityGate.shouldBindOutbox(capabilities)) {
                HyphenNotificationListenerRuntime.bindNotificationOutbox(
                    outbox = ProtocolSessionNotificationOutbox(session),
                    allowReplyActions = NotificationCapabilityGate.allowReplyActions(capabilities),
                )
            } else {
                HyphenNotificationListenerRuntime.clearNotificationOutbox()
            }
            ActiveSessionInstall(previous)
        }

    private fun startSessionIfAlive(session: ProtocolSession): Boolean =
        synchronized(workerLock) {
            if (activityDestroyed) {
                false
            } else {
                session.start()
                true
            }
        }

    private fun clearActiveStateIf(session: ProtocolSession? = null): ProtocolSession? =
        synchronized(activeStateLock) {
            if (session != null && activeSession !== session) return@synchronized null
            val current = activeSession
            activeSession = null
            activeCapabilities = null
            activeTransferSender = null
            lastTransferProgress = null
            current
        }

    private fun updateLastTransferProgress(progress: TransferProgress?) {
        synchronized(activeStateLock) {
            lastTransferProgress = progress
        }
    }

    private fun launchWorker(name: String, work: () -> Unit): Boolean {
        val worker = Thread({
            try {
                work()
            } finally {
                synchronized(workerLock) {
                    activeWorkers.remove(Thread.currentThread())
                }
            }
        }, name)
        val shouldStart = synchronized(workerLock) {
            if (activityDestroyed) {
                false
            } else {
                activeWorkers.add(worker)
                true
            }
        }
        if (!shouldStart) return false
        worker.start()
        return true
    }

    private fun postToUi(onDropped: (() -> Unit)? = null, action: () -> Unit): Boolean {
        fun drop(): Boolean {
            onDropped?.invoke()
            return false
        }
        if (activityDestroyed) return drop()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (activityDestroyed) return drop()
            action()
            return true
        }
        runOnUiThread {
            if (activityDestroyed) {
                onDropped?.invoke()
            } else {
                action()
            }
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (::log.isInitialized) append(notificationAccessLine())
    }

    private fun renderCdm(event: AssociationEvent) {
        when (event) {
            is AssociationEvent.PendingUserApproval -> {
                append("cdm: launching system approval dialog")
                event.launch()
            }
            is AssociationEvent.Associated ->
                append("cdm associated: id=${event.associationId} name=${event.displayName}")
            is AssociationEvent.Removed -> append("cdm removed: id=${event.associationId}")
            is AssociationEvent.Failed -> append("cdm failed: ${event.message}")
            AssociationEvent.UnsupportedOnThisSdk ->
                append("cdm: self-managed association needs API 33+ (QR-only on this device)")
        }
    }

    private fun showPeerManagement() {
        try {
            val store = AndroidTrustStores.openDefault(applicationContext)
            val peers = store.allPeers()
            if (peers.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Paired devices")
                    .setMessage("No trusted peers are stored on this phone.")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
            AlertDialog.Builder(this)
                .setTitle("Paired devices")
                .setItems(peers.map(::peerLabel).toTypedArray()) { _, which ->
                    confirmForgetPeer(peers[which])
                }
                .setMessage("Tap a device to forget it, or reset all local trust.")
                .setPositiveButton("Reset all") { _, _ -> confirmResetPeers(peers.size) }
                .setNegativeButton("Close", null)
                .show()
        } catch (e: Exception) {
            append("peer management failed: ${e.message}")
        }
    }

    private fun confirmForgetPeer(peer: TrustedPeer) {
        AlertDialog.Builder(this)
            .setTitle("Forget ${peer.displayName.ifBlank { "peer" }}?")
            .setMessage(
                "This removes the pinned fingerprint ${fingerprintPrefix(peer.spkiFingerprint)}. " +
                    "Pair again before this device can reconnect.",
            )
            .setPositiveButton("Forget") { _, _ ->
                try {
                    val removed = AndroidTrustStores.openDefault(applicationContext).remove(peer.spkiFingerprint)
                    stopCurrentSessionAfterTrustChange()
                    append("peer forgotten: ${peer.displayName.ifBlank { "unnamed" }} removed=$removed")
                } catch (e: Exception) {
                    append("peer forget failed: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmResetPeers(count: Int) {
        AlertDialog.Builder(this)
            .setTitle("Reset paired devices?")
            .setMessage("This removes $count trusted peer(s). Pair again before any device can reconnect.")
            .setPositiveButton("Reset") { _, _ ->
                try {
                    AndroidTrustStores.openDefault(applicationContext).removeAll()
                    stopCurrentSessionAfterTrustChange()
                    append("paired devices reset: $count removed")
                } catch (e: Exception) {
                    append("peer reset failed: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun stopCurrentSessionAfterTrustChange() {
        val session = clearActiveStateIf()
        session?.stop()
        resumeToken = null
        lastSessionId = null
        HyphenNotificationListenerRuntime.clearNotificationOutbox()
    }

    private fun peerLabel(peer: TrustedPeer): String =
        "${peer.displayName.ifBlank { "Unnamed peer" }} (${fingerprintPrefix(peer.spkiFingerprint)})"

    private fun fingerprintPrefix(fingerprint: ByteArray): String =
        fingerprint.take(6).joinToString("") { "%02x".format(it) }

    private fun probeManualEndpoint(raw: String) {
        val isQr = raw.trim().startsWith("hyphen://")
        val result = if (isQr) EndpointParser.parseQr(raw) else EndpointParser.parseManual(raw)
        if (isQr && result is ParseResult.Ok) {
            val qr = result.endpoint as ParsedEndpoint.QrPayload
            val fpHead = qr.decodedFingerprint().take(4).joinToString("") { "%02x".format(it) }
            append("qr parsed: v=${qr.version} dn=${qr.deviceName ?: "—"} fp=$fpHead… nonce ok")
            startPairing(qr)
            return
        }
        when (val parsed = result) {
            is ParseResult.Rejected -> append("endpoint rejected: ${parsed.reason}")
            is ParseResult.Ok -> {
                append("probing ${parsed.endpoint.host}:${parsed.endpoint.port} …")
                launchWorker("hyphen-endpoint-probe") {
                    val result = EndpointConnectProbe().probe(parsed.endpoint)
                    postToUi {
                        when (result) {
                            is EndpointConnectProbe.Result.Connected ->
                                append("connected: ${result.host}:${result.port}")
                            is EndpointConnectProbe.Result.Failed ->
                                append("connect failed: ${result.reason}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Android side of the SAS pairing flow (HYP-M2-011, protocol v0 §5):
     * provisional TLS connect pinning the QR's fingerprint, compute the
     * SAS, and write trust only through the gate when the user confirms.
     * Once trusted, the same socket becomes the first steady-state
     * ProtocolSession so M3 debug features can send over it.
     */
    private fun startPairing(qr: ParsedEndpoint.QrPayload) {
        append("pairing: provisional TLS to ${qr.host}:${qr.port} …")
        launchWorker("hyphen-pairing") {
            var socket: SSLSocket? = null
            try {
                val identity = AndroidKeystoreTlsIdentity.getOrCreate()
                val macFp = qr.decodedFingerprint()
                socket = TlsClient.connect(
                    host = qr.host,
                    port = qr.port,
                    identity = identity,
                    isTrusted = { it.contentEquals(macFp) },
                )
                val transcript = PairingTranscript.create(
                    nonce = qr.decodedNonce(),
                    macSpkiFingerprint = macFp,
                    androidSpkiFingerprint = identity.spkiFingerprint,
                    protocolVersion = PairingTranscript.PROTOCOL_VERSION,
                )!!
                val gate = SasConfirmationGate(
                    transcript = transcript,
                    peerFingerprint = macFp,
                    peerDisplayName = qr.deviceName ?: "Mac",
                    trustStore = AndroidTrustStores.openDefault(applicationContext),
                )
                val handoffSocket = socket
                socket = null
                postToUi(onDropped = { runCatching { handoffSocket.close() } }) {
                    presentSasDialog(qr, gate, handoffSocket)
                }
            } catch (e: Exception) {
                runCatching { socket?.close() }
                append("pairing failed: ${e.message}")
            }
        }
    }

    private fun presentSasDialog(qr: ParsedEndpoint.QrPayload, gate: SasConfirmationGate, socket: SSLSocket) {
        fun closeSocket() {
            launchWorker("hyphen-close-pairing-socket") { runCatching { socket.close() } }
        }
        AlertDialog.Builder(this)
            .setTitle("Confirm pairing code")
            .setMessage("Code: ${gate.sas}\n\nTrust this Mac only if it shows the same code.")
            .setPositiveButton("Codes match — trust") { _, _ ->
                gate.confirm()
                append("paired — fingerprint pinned (${gate.sas})")
                startSteadySession(qr, socket)
            }
            .setNegativeButton("Reject") { _, _ ->
                gate.reject()
                append("pairing rejected — nothing stored")
                closeSocket()
            }
            .setCancelable(false)
            .show()
    }

    private fun startSteadySession(qr: ParsedEndpoint.QrPayload, socket: SSLSocket) {
        launchWorker("hyphen-steady-session") {
            try {
                val device = SessionHandshake.DeviceInfo(
                    kind = "android",
                    appVersion = "0.0.1",
                    deviceName = Build.MODEL,
                )
                val handshake = SessionHandshake.initiate(
                    socket = socket,
                    device = device,
                    resumeToken = resumeToken,
                    previousSessionId = lastSessionId,
                )
                resumeToken = handshake.resumeToken
                lastSessionId = handshake.sessionId
                lateinit var session: ProtocolSession
                val listener = object : ProtocolSession.Listener {
                    override fun onLiveness(state: HeartbeatMonitor.State) {
                        append("session liveness: $state")
                    }

                    override fun onProtocolError(code: String, detail: String) {
                        append("session protocol error: $code $detail")
                    }

                    override fun onEnvelope(envelope: Envelope) {
                        handleSessionEnvelope(envelope)
                    }

                    override fun onAck(messageId: String) {
                        // Advances the outbound transfer window (chunk acks).
                        val sender = activeStateSnapshot().transferSender
                        runCatching { sender?.handleAck(messageId) }
                    }

                    override fun onClosed() {
                        if (clearActiveStateIf(session) != null) {
                            HyphenNotificationListenerRuntime.clearNotificationOutbox()
                        }
                        append("Mac session closed")
                    }
                }
                session = ProtocolSession(
                    socket = socket,
                    sessionId = handshake.sessionId,
                    config = ProtocolSession.Config(startingSeq = 1),
                    listener = DiagnosticProtocolSessionListener(diagnosticLogs, listener),
                )
                val sender = TransferSender(
                    ProtocolSessionTransferOutbox(session),
                    handshake.negotiatedCapabilities,
                    onProgress = { progress ->
                        updateLastTransferProgress(progress)
                        append(transferProgressLine(progress))
                    },
                )
                val install = installActiveStateAndBindOutboxIfAlive(
                    session,
                    handshake.negotiatedCapabilities,
                    sender,
                )
                if (install == null) {
                    clearActiveStateIf(session)
                    runCatching { session.stop() }
                    return@launchWorker
                }
                install.previousSession?.stop()
                if (!startSessionIfAlive(session)) {
                    clearActiveStateIf(session)
                    runCatching { session.stop() }
                    return@launchWorker
                }
                append(
                    "session connected to ${handshake.peerDevice?.deviceName ?: qr.deviceName ?: "Mac"}",
                )
            } catch (e: Exception) {
                runCatching { socket.close() }
                append("session failed: ${e.message}")
            }
        }
    }

    private fun handleSessionEnvelope(envelope: Envelope) {
        val state = activeStateSnapshot()
        val session = state.session
        try {
            if (envelope.type == Envelope.TYPE_ERROR) {
                append(peerErrorLine(envelope))
                return
            }
            val expectedCapability = expectedCapability(envelope.type)
            if (session != null && expectedCapability != null && envelope.capability != expectedCapability) {
                val id = sendProtocolError(
                    session = session,
                    regarding = envelope,
                    code = "plugin/unsupported-capability",
                    message = "Message type was sent under the wrong capability.",
                )
                append("unsupported capability reported: $id (${envelope.capability})")
                return
            }
            if (session != null && !isNegotiatedCapability(state, envelope.capability)) {
                val id = sendProtocolError(
                    session = session,
                    regarding = envelope,
                    code = "plugin/unsupported-capability",
                    message = "Capability is not negotiated for this session.",
                )
                append("unsupported capability reported: $id (${envelope.capability})")
                return
            }
            if (session != null && !isNegotiatedNotificationOption(state, envelope.type)) {
                val id = sendProtocolError(
                    session = session,
                    regarding = envelope,
                    code = "plugin/unsupported-capability",
                    message = "Notification action is not negotiated for this session.",
                )
                append("unsupported notification option reported: $id (${envelope.type})")
                return
            }
            if (session != null) {
                val dismissResultId = NotificationDismissRequestHandler(
                    canceller = HyphenNotificationListenerRuntime.notificationCanceller(),
                    isActiveNotification = HyphenNotificationListenerRuntime::isNotificationActive,
                    outbox = ProtocolSessionNotificationOutbox(session),
                ).handle(envelope)
                if (dismissResultId != null) {
                    append("notification dismiss result sent: $dismissResultId")
                    return
                }
                val replyResultId = NotificationReplyRequestHandler(
                    replier = HyphenNotificationListenerRuntime.notificationReplier(),
                    outbox = ProtocolSessionNotificationOutbox(session),
                ).handle(envelope)
                if (replyResultId != null) {
                    append("notification reply result sent: $replyResultId")
                    return
                }
                if (envelope.type == NotificationProtocol.TYPE_PRIVACY_POLICY) {
                    val policy = NotificationPrivacyPolicyHandler.parse(envelope.payload)
                    HyphenNotificationListenerRuntime.setNotificationPrivacyPolicy(policy)
                    append(
                        "notification privacy policy applied: default=${policy.defaultMode.wire}, " +
                            "${policy.perPackageModes.size} app overrides",
                    )
                    return
                }
            }
            if (envelope.capability == TransferProtocol.CAPABILITY) {
                if (envelope.type == TransferProtocol.TYPE_RESUME_INFO) {
                    val info = TransferResumeInfo.fromJson(envelope.payload)
                    try {
                        state.transferSender?.handleResumeInfo(info)
                        append("transfer resume continued: ${info.fileId}")
                    } catch (e: Exception) {
                        append("transfer resume info ignored: ${e.message}")
                    }
                    return
                }
                when (val event = transferReceiver.handle(envelope)) {
                    is TransferEvent.Completed -> {
                        updateLastTransferProgress(null)
                        // Debug surface keeps no copy; drop the temp file so completed
                        // transfers do not accumulate in the storage directory.
                        val line = transferCompletedLine(event.completed)
                        event.completed.file.delete()
                        append(line)
                    }
                    is TransferEvent.ResumeRequested -> {
                        if (session != null) {
                            val id = TransferSender(ProtocolSessionTransferOutbox(session)).sendResumeInfo(event.info)
                            append("transfer resume info sent: $id (${event.info.fileId})")
                        } else {
                            append("transfer resume requested without active session")
                        }
                    }
                    is TransferEvent.Cancelled -> {
                        updateLastTransferProgress(null)
                        append("transfer cancelled: ${event.cancel.fileId}")
                    }
                    TransferEvent.Ignored -> Unit
                }
                return
            }
            val request = textReceiver.handle(envelope)
            if (request != null) {
                postToUi { presentTextLinkConfirmation(request) }
                return
            }
        } catch (e: IllegalArgumentException) {
            session?.let {
                sendProtocolError(
                    session = it,
                    regarding = envelope,
                    code = "protocol/invalid-envelope",
                    message = "Envelope payload is invalid for its type.",
                )
            }
            append("session envelope rejected: ${e.message}")
            return
        }
        if (isPluginEnvelope(envelope)) {
            session?.let {
                val id = sendProtocolError(
                    session = it,
                    regarding = envelope,
                    code = "protocol/unknown-type",
                    message = "No handler is registered for this message type.",
                )
                append("unknown type reported: $id (${envelope.type})")
            }
        }
    }

    private fun isNegotiatedCapability(state: ActiveStateSnapshot, capability: String?): Boolean =
        capability == null || state.capabilities?.contains(capability) != false

    private fun isNegotiatedNotificationOption(state: ActiveStateSnapshot, type: String): Boolean =
        NotificationCapabilityGate.allowsInboundRequest(type, state.capabilities)

    private fun isPluginEnvelope(envelope: Envelope): Boolean =
        envelope.type !in setOf(Envelope.TYPE_ACK, Envelope.TYPE_HEARTBEAT, Envelope.TYPE_HELLO, Envelope.TYPE_ERROR)

    private fun expectedCapability(type: String): String? =
        when (type) {
            NotificationProtocol.TYPE_DISMISS_REQUEST,
            NotificationProtocol.TYPE_REPLY_REQUEST,
            NotificationProtocol.TYPE_PRIVACY_POLICY -> NotificationProtocol.CAPABILITY
            TransferProtocol.TYPE_MANIFEST,
            TransferProtocol.TYPE_CHUNK,
            TransferProtocol.TYPE_RESUME_REQUEST,
            TransferProtocol.TYPE_RESUME_INFO,
            TransferProtocol.TYPE_CANCEL -> TransferProtocol.CAPABILITY
            TextLinkMessage.TYPE_SEND -> TextLinkMessage.CAPABILITY
            else -> null
        }

    private fun sendProtocolError(
        session: ProtocolSession,
        regarding: Envelope,
        code: String,
        message: String,
        retryable: Boolean = false,
    ): String =
        session.send(
            type = Envelope.TYPE_ERROR,
            payload = Json.obj(
                "code" to Json.Str(code),
                "message" to Json.Str(message.take(256)),
                "regarding" to Json.Str(regarding.messageId),
                "retryable" to Json.Bool(retryable),
            ),
            requiresAck = false,
        )

    private fun peerErrorLine(envelope: Envelope): String {
        val code = (envelope.payload["code"] as? Json.Str)?.value ?: "unknown"
        val regarding = (envelope.payload["regarding"] as? Json.Str)?.value ?: "none"
        return "peer error: $code regarding $regarding"
    }

    private fun pickFileToSend() {
        if (activeStateSnapshot().transferSender == null) {
            append("transfer/send: no active Mac session")
            return
        }
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Send file to Mac"), REQUEST_PICK_FILE)
        } catch (e: ActivityNotFoundException) {
            append("transfer/send: no file picker available")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_FILE) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            append("transfer/send cancelled")
            return
        }
        sendFile(uri)
    }

    /**
     * Outbound file send entry: feeds a content:// selection into the persistent
     * [activeTransferSender], populating its outbound registry so a later
     * resume.info/ack from the peer routes to a real transfer.
     */
    private fun sendFile(uri: Uri) {
        val state = activeStateSnapshot()
        val sender = state.transferSender
        if (sender == null) {
            append("transfer/send: no active Mac session")
            return
        }
        if (state.capabilities?.contains(TransferProtocol.CAPABILITY) != true) {
            append("transfer/send: peer did not negotiate transfer.v1")
            return
        }
        val meta = queryContentMetadata(uri)
        if (meta == null) {
            append("transfer/send: could not read selected file size")
            return
        }
        val source = StreamTransferByteSource(meta.sizeBytes) {
            contentResolver.openInputStream(uri) ?: throw java.io.IOException("could not open $uri")
        }
        launchWorker("hyphen-send-file") {
            try {
                val manifest = sender.sendSource(
                    filename = meta.filename,
                    mimeType = meta.mimeType,
                    source = source,
                    chunkSizeBytes = DEFAULT_TRANSFER_CHUNK_BYTES,
                )
                append("transfer/send started: ${manifest.filename} (${manifest.sizeBytes} bytes)")
            } catch (e: Exception) {
                append("transfer/send failed: ${e.message}")
            }
        }
    }

    private data class ContentMetadata(
        val filename: String,
        val mimeType: String,
        val sizeBytes: Long,
    )

    private fun queryContentMetadata(uri: Uri): ContentMetadata? {
        var name = uri.lastPathSegment ?: "file.bin"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        if (size < 0) return null
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        return ContentMetadata(name, mime, size)
    }

    private fun cancelActiveTransfer() {
        val state = activeStateSnapshot()
        val progress = state.lastTransferProgress
        if (progress == null || progress.isComplete) {
            append("transfer cancel: no active transfer")
            return
        }
        val session = state.session
        if (session == null) {
            append("transfer cancel: no active session")
            return
        }
        val id = TransferSender(ProtocolSessionTransferOutbox(session)).sendCancel(
            TransferCancel(progress.fileId, discard = true),
        )
        updateLastTransferProgress(null)
        append("transfer cancel sent: $id (${progress.filename})")
    }

    private fun transferProgressLine(progress: TransferProgress): String =
        "transfer ${progress.filename}: ${progress.completedBytes}/${progress.totalBytes} bytes " +
            "(${progress.completedChunks}/${progress.totalChunks})"

    private fun transferCompletedLine(completed: TransferCompleted): String =
        "transfer received: ${completed.manifest.filename} (${completed.file.length()} bytes)"

    private fun presentTextLinkConfirmation(request: TextLinkConfirmationRequest) {
        val isUrl = request.message.kind == TextLinkKind.URL
        AlertDialog.Builder(this)
            .setTitle(if (isUrl) "Open link from Mac?" else "Copy text from Mac?")
            .setMessage(request.message.value)
            .setPositiveButton(if (isUrl) "Open" else "Copy") { _, _ ->
                textReceiver.resolve(request.messageId)
                if (isUrl) {
                    openConfirmedLink(request.message.value)
                } else {
                    copyConfirmedText(request.message.value)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                textReceiver.resolve(request.messageId)
                append("text/link declined")
            }
            .show()
    }

    private fun copyConfirmedText(value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Hyphen text", value))
        append("text copied from Mac")
    }

    private fun openConfirmedLink(value: String) {
        val uri = Uri.parse(value)
        if (!TextLinkMessage.isAllowedOpenUrl(value, uri.scheme)) {
            append("link open rejected: url must use http or https")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            append("link opened from Mac")
        } catch (e: ActivityNotFoundException) {
            append("link open failed: ${e.message}")
        }
    }

    private fun sendTextLink(raw: String) {
        val state = activeStateSnapshot()
        val session = state.session
        if (session == null) {
            append("text/link: no active Mac session")
            return
        }
        if (state.capabilities?.contains(SessionHandshake.CAPABILITY_TEXT) != true) {
            append("text/link: peer did not negotiate text.v1")
            return
        }
        val message = try {
            TextLinkMessage.fromUserInput(raw)
        } catch (e: IllegalArgumentException) {
            append("text/link rejected: ${e.message}")
            return
        }
        launchWorker("hyphen-send-text-link") {
            try {
                val id = TextLinkSender(ProtocolSessionTextLinkOutbox(session)).send(message)
                append("text/link sent: $id")
            } catch (e: Exception) {
                append("text/link send failed: ${e.message}")
            }
        }
    }

    private fun showNotificationAccessOnboarding() {
        val status = NotificationAccessController.forContext(this).status()
        if (status.enabled) {
            append("notification access: already enabled (${status.componentName})")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Enable notification mirror")
            .setMessage(
                "Hyphen can read Android notification titles, actions, and content so " +
                    "it can mirror them to your paired Mac over local TLS.\n\n" +
                    "Notifications are not sent to a cloud relay, and Hyphen does not " +
                    "store notification history. You can switch to hidden-body mode " +
                    "after enabling if you want less detail on the Mac.\n\n" +
                    "Hyphen never asks for SMS or Call Log access. You can turn " +
                    "notification access off later in Android Settings.",
            )
            .setPositiveButton("Open settings") { _, _ -> openNotificationSettings() }
            .setNegativeButton("Not now") { _, _ ->
                append("notification access: settings not opened")
            }
            .show()
    }

    private fun openNotificationSettings() {
        try {
            startActivity(NotificationAccessController.settingsIntent())
            append("notification access: opened system settings")
        } catch (e: ActivityNotFoundException) {
            append("notification access: settings unavailable (${e.message})")
        }
    }

    private fun notificationAccessLine(): String {
        val status = NotificationAccessController.forContext(this).status()
        return "notification access: ${if (status.enabled) "enabled" else "disabled"}; " +
            "listener=${HyphenNotificationListenerRuntime.state()}; " +
            "component=${status.componentName}"
    }

    private fun notificationPrivacyButtonText(mode: NotificationPrivacyMode): String =
        "通知隐私：${notificationPrivacyStatus(mode)}"

    private fun notificationPrivacyStatus(mode: NotificationPrivacyMode): String =
        when (mode) {
            NotificationPrivacyMode.SHOW_FULL -> "完整"
            NotificationPrivacyMode.HIDE_BODY -> "隐藏内容"
            NotificationPrivacyMode.EXISTS_ONLY -> "仅提示"
        }

    private fun toggleBetaDiagnostics() {
        if (betaDiagnosticsEnabled()) {
            setBetaDiagnosticsEnabled(false)
            append("beta diagnostics: disabled")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("开启 Beta 诊断？")
            .setMessage(
                "Beta 诊断默认关闭。\n\n" +
                    "仅在排查 Beta 问题时开启。本地预览和导出可能包含用于关联失败的跟踪 ID；" +
                    "通知正文、文件名、URL 和 IP 后缀仍会脱敏。\n\n" +
                    "Hyphen 不会自动上传诊断。导出始终由用户主动触发，也可以随时关闭此项。",
            )
            .setPositiveButton("开启") { _, _ ->
                setBetaDiagnosticsEnabled(true)
                append("beta diagnostics: enabled")
            }
            .setNegativeButton("取消") { _, _ ->
                append("beta diagnostics: unchanged (off)")
            }
            .show()
    }

    private fun setBetaDiagnosticsEnabled(enabled: Boolean) {
        getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_BETA_DIAGNOSTICS_ENABLED, enabled)
            .apply()
        renderBetaDiagnosticsButton()
    }

    private fun betaDiagnosticsEnabled(): Boolean =
        getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_BETA_DIAGNOSTICS_ENABLED, false)

    private fun renderBetaDiagnosticsButton() {
        betaDiagnosticsButton.text = betaDiagnosticsButtonText()
    }

    private fun betaDiagnosticsButtonText(): String =
        "Beta 诊断：${betaDiagnosticsStatus()}"

    private fun betaDiagnosticsStatus(): String =
        if (betaDiagnosticsEnabled()) "开启" else "关闭"

    private fun previewDiagnostics() {
        val json = diagnosticsExporter().previewJson()
        AlertDialog.Builder(this)
            .setTitle("诊断预览")
            .setMessage(json)
            .setPositiveButton("OK", null)
            .show()
        append("diagnostics preview: ${diagnosticLogs.snapshot().size} event(s), beta ${betaDiagnosticsStatus()}")
    }

    private fun exportDiagnostics() {
        val json = diagnosticsExporter().exportText()
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_SUBJECT, "Hyphen diagnostics")
            .putExtra(Intent.EXTRA_TEXT, json)
        try {
            startActivity(Intent.createChooser(intent, "Export Hyphen diagnostics"))
            append("diagnostics export: chooser opened")
        } catch (e: ActivityNotFoundException) {
            append("diagnostics export failed: ${e.message}")
        }
    }

    private fun deleteDiagnostics() {
        val count = diagnosticLogs.snapshot().size
        diagnosticsExporter().deleteLocalDiagnostics()
        append("diagnostics deleted: $count event(s)")
    }

    private fun diagnosticsExporter(): RedactedDiagnosticsExporter =
        RedactedDiagnosticsExporter(
            logs = diagnosticLogs,
            appVersion = appVersionName(),
            sdkInt = Build.VERSION.SDK_INT,
            includeTraceIds = betaDiagnosticsEnabled(),
        )

    @Suppress("DEPRECATION")
    private fun appVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"

    private fun startWindow() {
        val m = DiscoveryManager(
            backend = AndroidNsdBackend(applicationContext),
            scheduler = HandlerScheduler(),
            lock = ScopedMulticastLock(AndroidMulticastLockHandle(applicationContext)) {
                append("multicast lock: $it")
            },
            onEvent = ::render,
        )
        manager = m
        if (m.start()) {
            button.isEnabled = false
            append("discovery started (${DiscoveryManager.SERVICE_TYPE})")
        }
    }

    private fun render(event: DiscoveryEvent) {
        when (event) {
            is DiscoveryEvent.ServiceResolved ->
                append("resolved: ${event.service.name} @ ${event.service.host}:${event.service.port}")
            is DiscoveryEvent.ServiceLost -> append("lost: ${event.name}")
            is DiscoveryEvent.Failed -> append("failure: ${event.reason}")
            is DiscoveryEvent.WindowEnded -> {
                append("window ended, resolved=${event.resolvedCount}")
                postToUi { button.isEnabled = true }
            }
        }
    }

    private fun append(line: String) {
        postToUi {
            logBuffer.append(line)
            if (::log.isInitialized) log.text = logBuffer.render()
        }
    }

    override fun onDestroy() {
        val workers = synchronized(workerLock) {
            activityDestroyed = true
            activeWorkers.toList().also { activeWorkers.clear() }
        }
        workers.forEach { it.interrupt() }
        val session = clearActiveStateIf()
        super.onDestroy()
        HyphenNotificationListenerRuntime.clearNotificationOutbox()
        session?.stop()
        manager?.stop()
    }

    private companion object {
        const val HYPHEN_CANVAS = 0xFF101318.toInt()
        const val HYPHEN_CARD = 0xFF1A1D23.toInt()
        const val HYPHEN_CARD_2 = 0xFF23262E.toInt()
        const val HYPHEN_SURFACE_3 = 0xFF2B3038.toInt()
        const val HYPHEN_HAIR = 0x24FFFFFF
        const val HYPHEN_HAIR_2 = 0x33FFFFFF
        const val HYPHEN_TEXT = 0xFFE8EAEF.toInt()
        const val HYPHEN_DIM = 0xFF9298A3.toInt()
        const val HYPHEN_FAINT = 0xFF646A75.toInt()
        const val HYPHEN_ACCENT = 0xFF2BC48F.toInt()
        const val HYPHEN_ACCENT_INK = 0xFF04140D.toInt()
        const val DIAGNOSTICS_PREFS = "dev.hyphen.android.diagnostics"
        const val PREF_BETA_DIAGNOSTICS_ENABLED = "beta_diagnostics_enabled"
        const val REQUEST_PICK_FILE = 4201
        const val DEFAULT_TRANSFER_CHUNK_BYTES = 256 * 1024
        const val MAX_LOG_LINES = 500
    }
}
