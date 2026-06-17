package dev.hyphen.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hyphen.android.R
import dev.hyphen.android.ui.theme.HyphenPalette
import dev.hyphen.android.ui.theme.HyphenTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

// Frontend UX plan Track B (B2/B3): the Android home screen, rebuilt in Compose
// and bound to live state from `HyphenUiModel`. No hardcoded timeline, no fake
// "已连接 · 延迟 18ms" summary — the connection header and timeline render the
// real `HyphenUiState`. Send/transfer/diagnostics controls call back into the
// controller. Visual language is the ported dark design (HyphenTheme), unchanged.

@Composable
fun HyphenHome(model: HyphenUiModel, actions: HyphenActions) {
    val p = HyphenTheme.palette
    val state = model.state
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.canvas)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 24.dp),
    ) {
        BrandHeader()
        Spacer(Modifier.height(18.dp))
        ConnectionHeader(state.connection)
        Spacer(Modifier.height(12.dp))
        TimelineCard(state = state, actions = actions)
        Spacer(Modifier.height(12.dp))
        PermissionsCard(actions = actions)
        Spacer(Modifier.height(12.dp))
        NotificationCard(
            accessEnabled = model.notificationAccessEnabled,
            privacyLabel = model.notificationPrivacyLabel,
            actions = actions,
        )
        Spacer(Modifier.height(12.dp))
        DiagnosticsCard(betaOn = model.betaDiagnosticsOn, actions = actions)
        Spacer(Modifier.height(12.dp))
        EventLogCard(lines = state.logLines)
    }
}

// MARK: - Header

@Composable
private fun BrandHeader() {
    val p = HyphenTheme.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)).background(p.surface3)
                .border(1.dp, p.hair2, RoundedCornerShape(5.dp)))
            Box(Modifier.padding(horizontal = 7.dp).width(20.dp).height(4.dp)
                .clip(RoundedCornerShape(3.dp)).background(p.accent))
            Box(Modifier.size(width = 11.dp, height = 18.dp).clip(RoundedCornerShape(4.dp))
                .background(p.surface3).border(1.dp, p.hair2, RoundedCornerShape(4.dp)))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Hyphen—", color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.brand_tagline), color = p.dim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ConnectionHeader(connection: ConnectionSnapshot) {
    val p = HyphenTheme.palette
    val title = if (connection.isPaired) {
        connection.peerName ?: stringResource(R.string.conn_paired_fallback)
    } else {
        stringResource(R.string.conn_unpaired_title)
    }
    val subtitle = connectionSubtitle(connection)
    // One merged announcement for TalkBack; the status dot and version badge are
    // decorative and cleared so the row is not read piecemeal.
    val description = "${stringResource(R.string.cd_connection_status)}: $title, $subtitle"
    HyphenCardContainer {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        ) {
            StatusDot(connection.state)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = p.dim, fontSize = 12.sp)
            }
            if (connection.isPaired && connection.state == ConnectionState.CONNECTED) {
                Text("hyphen/0.3", color = p.faint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun connectionSubtitle(c: ConnectionSnapshot): String {
    if (!c.isPaired) return stringResource(R.string.conn_unpaired_subtitle)
    val base = stringResource(c.state.titleRes())
    return c.peerName?.let { stringResource(R.string.conn_subtitle_with_peer, base, it) } ?: base
}

private fun ConnectionSnapshot.stateColor(p: HyphenPalette): Color = when (state) {
    ConnectionState.CONNECTED -> p.accent
    ConnectionState.DEGRADED, ConnectionState.RECONNECTING -> p.amber
    ConnectionState.DISCOVERING -> p.blue
    ConnectionState.SLEEPING, ConnectionState.SUSPENDED -> p.faint
}

@Composable
private fun StatusDot(state: ConnectionState) {
    val p = HyphenTheme.palette
    val color = ConnectionSnapshot(state = state).stateColor(p)
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        if (state == ConnectionState.CONNECTED || state == ConnectionState.DEGRADED) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)))
        }
        if (state == ConnectionState.SUSPENDED) {
            Box(Modifier.size(12.dp).clip(CircleShape).border(2.dp, color, CircleShape))
        } else {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        }
    }
}

// MARK: - Timeline

@Composable
private fun dayLabelText(label: DayLabel): String = when (label) {
    DayLabel.Today -> stringResource(R.string.feed_today)
    DayLabel.Yesterday -> stringResource(R.string.feed_yesterday)
    is DayLabel.OnDate -> stringResource(R.string.feed_date_month_day, label.month, label.day)
}

@Composable
private fun TimelineCard(state: HyphenUiState, actions: HyphenActions) {
    val p = HyphenTheme.palette
    // Re-evaluate "now" at each midnight so today/yesterday day headers don't go
    // stale while the screen stays composed across a date boundary. Grouping only
    // depends on the feed contents and the current day, not on the connection.
    val nowMillis by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val nextMidnight = Instant.ofEpochMilli(value).atZone(zone)
                .toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
            delay((nextMidnight.toEpochMilli() - value).coerceAtLeast(1_000L))
        }
    }
    val days = remember(state.feed, nowMillis) {
        state.feed.grouped(nowMillis)
    }
    var draft by remember { mutableStateOf("") }
    val connected = state.connection.state == ConnectionState.CONNECTED

    HyphenCard(title = stringResource(R.string.timeline_title), subtitle = stringResource(R.string.timeline_subtitle)) {
        if (days.isEmpty()) {
            EmptyTimeline(state.connection)
        } else {
            days.forEachIndexed { index, day ->
                Text(
                    dayLabelText(day.label), color = p.faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 8.dp, bottom = 4.dp),
                )
                day.items.forEach { item ->
                    TimelineRow(item)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HyphenTextField(
            value = draft, onValueChange = { draft = it },
            hint = stringResource(R.string.field_send_hint), enabled = connected,
            onSend = { if (draft.isNotBlank()) { actions.onSendText(draft); draft = "" } },
        )
        Spacer(Modifier.height(10.dp))
        Row {
            HyphenButton(
                stringResource(R.string.btn_send_text), primary = true, enabled = connected, modifier = Modifier.weight(1f),
            ) { if (draft.isNotBlank()) { actions.onSendText(draft); draft = "" } }
            Spacer(Modifier.width(9.dp))
            HyphenButton(stringResource(R.string.btn_send_file), enabled = connected, modifier = Modifier.weight(1f), onClick = actions.onSendFile)
        }
        Spacer(Modifier.height(10.dp))
        HyphenButton(stringResource(R.string.btn_cancel_transfer), enabled = connected, modifier = Modifier.fillMaxWidth(), onClick = actions.onCancelTransfer)
    }
}

@Composable
private fun EmptyTimeline(connection: ConnectionSnapshot) {
    val p = HyphenTheme.palette
    val (titleRes, subtitleRes) = emptyCopy(connection)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(titleRes), color = p.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(subtitleRes), color = p.faint, fontSize = 11.sp)
    }
}

private fun emptyCopy(c: ConnectionSnapshot): Pair<Int, Int> {
    if (!c.isPaired) return R.string.empty_unpaired_title to R.string.empty_unpaired_sub
    return when (c.state) {
        ConnectionState.CONNECTED, ConnectionState.DEGRADED -> R.string.empty_idle_title to R.string.empty_idle_sub
        ConnectionState.RECONNECTING -> R.string.empty_reconnecting_title to R.string.empty_reconnecting_sub
        ConnectionState.DISCOVERING -> R.string.empty_discovering_title to R.string.empty_discovering_sub
        ConnectionState.SLEEPING -> R.string.empty_sleeping_title to R.string.empty_sleeping_sub
        ConnectionState.SUSPENDED -> R.string.empty_suspended_title to R.string.empty_suspended_sub
    }
}

@Composable
private fun TimelineRow(item: ActivityItem) {
    when (val kind = item.kind) {
        is ActivityKind.Transfer -> TransferRow(kind.item, item.timestampMillis)
        is ActivityKind.Text -> TextRow(kind.item, item.timestampMillis)
        is ActivityKind.NotificationAction -> NotificationActionRow(kind.item, item.timestampMillis)
        is ActivityKind.Pairing -> PairingRow(kind.item, item.timestampMillis)
    }
}

@Composable
private fun RowTile(glyph: String, fg: Color, content: @Composable RowScope.() -> Unit) {
    val p = HyphenTheme.palette
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface2)
            .border(1.dp, p.hair, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(p.surface3)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) { Text(glyph, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(11.dp))
        content()
    }
}

@Composable
private fun TransferRow(t: TransferFeedItem, at: Long) {
    val p = HyphenTheme.palette
    val incoming = t.direction == TransferDirection.INCOMING
    val meta = when (t.status) {
        TransferStatus.ACTIVE -> if (t.totalBytes > 0)
            "${HyphenFormat.bytes(t.completedBytes)} / ${HyphenFormat.bytes(t.totalBytes)}"
        else if (incoming) stringResource(R.string.transfer_receiving) else stringResource(R.string.transfer_sending)
        TransferStatus.COMPLETED -> if (incoming) {
            if (t.verified) stringResource(R.string.transfer_received_verified, HyphenFormat.bytes(t.totalBytes))
            else stringResource(R.string.transfer_received, HyphenFormat.bytes(t.totalBytes))
        } else stringResource(R.string.transfer_sent, HyphenFormat.bytes(t.totalBytes))
        TransferStatus.CANCELLED -> stringResource(R.string.transfer_cancelled)
    }
    RowTile(glyph = if (incoming) "↓" else "↑", fg = if (incoming) p.accent else p.dim) {
        Column(Modifier.weight(1f)) {
            Text(t.filename, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, color = p.dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val fraction = t.fraction
            if (t.status == TransferStatus.ACTIVE && fraction != null) {
                Spacer(Modifier.height(6.dp))
                ProgressBar(fraction, p.accent)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (t.status == TransferStatus.ACTIVE && t.fraction != null) {
            Text("${(t.fraction!! * 100).toInt()}%", color = p.accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Text(HyphenFormat.time(at), color = p.faint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TextRow(t: TextFeedItem, at: Long) {
    val p = HyphenTheme.palette
    val sent = t.direction == TextDirection.SENT
    val title = when {
        sent && t.kind == TextKind.URL -> stringResource(R.string.text_link_sent)
        sent -> stringResource(R.string.text_text_sent)
        t.kind == TextKind.URL -> stringResource(R.string.text_link_opened)
        else -> stringResource(R.string.text_text_copied)
    }
    RowTile(glyph = if (sent) "↑" else "↓", fg = if (sent) p.dim else p.accent) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(t.value, color = p.dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(HyphenFormat.time(at), color = p.faint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
private fun NotificationActionRow(item: NotificationActionFeedItem, at: Long) {
    val p = HyphenTheme.palette
    RowTile(glyph = "⌁", fg = p.faint) {
        Column(Modifier.weight(1f)) {
            Text("${item.action} · ${item.label}", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.notif_action_subtitle), color = p.dim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(HyphenFormat.time(at), color = p.faint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
private fun PairingRow(item: PairingFeedItem, at: Long) {
    val p = HyphenTheme.palette
    RowTile(glyph = "⌁", fg = p.faint) {
        Text(item.message, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(HyphenFormat.time(at), color = p.faint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
private fun ProgressBar(fraction: Float, tint: Color) {
    val p = HyphenTheme.palette
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.surface3)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(tint))
    }
}

// MARK: - Permission / pairing card

@Composable
private fun PermissionsCard(actions: HyphenActions) {
    val p = HyphenTheme.palette
    var endpoint by remember { mutableStateOf("") }
    HyphenCard(title = stringResource(R.string.perm_title), subtitle = stringResource(R.string.perm_subtitle)) {
        StatusRow(
            stringResource(R.string.perm_localnet_title),
            stringResource(R.string.perm_localnet_detail),
            stringResource(R.string.perm_localnet_badge),
        ) {
            HyphenButton(stringResource(R.string.perm_btn_find_mac), primary = true, onClick = actions.onFindMac)
        }
        Spacer(Modifier.height(8.dp))
        StatusRow(
            stringResource(R.string.perm_manual_title),
            stringResource(R.string.perm_manual_detail),
            null,
            control = null,
        )
        Spacer(Modifier.height(10.dp))
        HyphenTextField(value = endpoint, onValueChange = { endpoint = it }, hint = stringResource(R.string.perm_manual_hint), enabled = true)
        Spacer(Modifier.height(10.dp))
        HyphenButton(stringResource(R.string.perm_btn_manual_connect), modifier = Modifier.fillMaxWidth()) { actions.onManualConnect(endpoint) }
        Spacer(Modifier.height(10.dp))
        StatusRow(
            stringResource(R.string.perm_cdm_title),
            stringResource(R.string.perm_cdm_detail),
            stringResource(R.string.perm_cdm_badge),
        ) {
            HyphenButton(stringResource(R.string.perm_btn_create_assoc), onClick = actions.onCreateAssociation)
        }
        Spacer(Modifier.height(10.dp))
        Row {
            HyphenButton(stringResource(R.string.perm_btn_list_assoc), modifier = Modifier.weight(1f), onClick = actions.onListAssociations)
            Spacer(Modifier.width(9.dp))
            HyphenButton(stringResource(R.string.perm_btn_manage_peers), modifier = Modifier.weight(1f), onClick = actions.onManagePeers)
        }
    }
}

// MARK: - Notification mirror card

@Composable
private fun NotificationCard(accessEnabled: Boolean, privacyLabel: String, actions: HyphenActions) {
    val p = HyphenTheme.palette
    HyphenCard(title = stringResource(R.string.notif_card_title), subtitle = stringResource(R.string.notif_card_subtitle)) {
        StatusRow(
            stringResource(R.string.notif_access_title),
            if (accessEnabled) stringResource(R.string.notif_access_on_detail) else stringResource(R.string.notif_access_off_detail),
            if (accessEnabled) stringResource(R.string.notif_access_badge_on) else stringResource(R.string.notif_access_badge_off),
        ) {
            HyphenButton(
                if (accessEnabled) stringResource(R.string.notif_btn_on) else stringResource(R.string.notif_btn_off),
                primary = !accessEnabled,
                onClick = actions.onOpenNotificationMirror,
            )
        }
        Spacer(Modifier.height(10.dp))
        HyphenButton(stringResource(R.string.notif_btn_check), modifier = Modifier.fillMaxWidth(), onClick = actions.onCheckNotificationAccess)
        Spacer(Modifier.height(10.dp))
        HyphenButton(stringResource(R.string.notif_btn_privacy, privacyLabel), modifier = Modifier.fillMaxWidth(), onClick = actions.onCycleNotificationPrivacy)
    }
}

// MARK: - Diagnostics card

@Composable
private fun DiagnosticsCard(betaOn: Boolean, actions: HyphenActions) {
    HyphenCard(title = stringResource(R.string.diag_title), subtitle = stringResource(R.string.diag_subtitle)) {
        StatusRow(
            stringResource(R.string.diag_trace_title),
            stringResource(R.string.diag_trace_detail),
            stringResource(R.string.diag_trace_badge),
        ) {
            val betaLabel = if (betaOn) stringResource(R.string.diag_beta_on) else stringResource(R.string.diag_beta_off)
            HyphenButton(stringResource(R.string.diag_btn_beta, betaLabel), onClick = actions.onToggleBetaDiagnostics)
        }
        Spacer(Modifier.height(10.dp))
        Row {
            HyphenButton(stringResource(R.string.diag_btn_preview), modifier = Modifier.weight(1f), onClick = actions.onPreviewDiagnostics)
            Spacer(Modifier.width(9.dp))
            HyphenButton(stringResource(R.string.diag_btn_export), primary = true, modifier = Modifier.weight(1f), onClick = actions.onExportDiagnostics)
        }
        Spacer(Modifier.height(10.dp))
        HyphenButton(stringResource(R.string.diag_btn_delete), modifier = Modifier.fillMaxWidth(), onClick = actions.onDeleteDiagnostics)
    }
}

// MARK: - Event log card

@Composable
private fun EventLogCard(lines: List<String>) {
    val p = HyphenTheme.palette
    HyphenCard(title = stringResource(R.string.log_title), subtitle = stringResource(R.string.log_subtitle)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface2)
                .border(1.dp, p.hair, RoundedCornerShape(14.dp)).padding(12.dp),
        ) {
            val joined = remember(lines) { lines.joinToString("\n") }
            Text(
                if (lines.isEmpty()) stringResource(R.string.log_empty) else joined,
                color = p.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            )
        }
    }
}

// MARK: - Shared components

@Composable
private fun HyphenCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    HyphenCardContainer {
        Column {
            Text(title, color = HyphenTheme.palette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = HyphenTheme.palette.dim, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
private fun HyphenCardContainer(content: @Composable () -> Unit) {
    val p = HyphenTheme.palette
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface)
            .border(1.dp, p.hair, RoundedCornerShape(20.dp)).padding(16.dp),
    ) { content() }
}

@Composable
private fun StatusRow(title: String, detail: String, badge: String?, control: (@Composable () -> Unit)? = null) {
    val p = HyphenTheme.palette
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface2)
            .border(1.dp, p.hair, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Pill(badge)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(detail, color = p.dim, fontSize = 12.sp)
        }
        if (control != null) {
            Spacer(Modifier.width(12.dp))
            control()
        }
    }
}

@Composable
private fun Pill(text: String) {
    val p = HyphenTheme.palette
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(p.accent).padding(horizontal = 7.dp, vertical = 2.dp),
    ) { Text(text, color = p.accentInk, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun HyphenButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val p = HyphenTheme.palette
    val bg = if (primary) p.accent else p.surface2
    val fg = if (primary) p.accentInk else p.text
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .then(if (primary) Modifier else Modifier.border(1.dp, p.hair2, RoundedCornerShape(12.dp)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) fg else fg.copy(alpha = 0.5f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HyphenTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    enabled: Boolean = true,
    onSend: (() -> Unit)? = null,
) {
    val p = HyphenTheme.palette
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
            .background(p.surface2).border(1.dp, p.hair, RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp, vertical = 13.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, color = p.dim, fontSize = 14.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = p.text, fontSize = 14.sp),
            cursorBrush = SolidColor(p.accent),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSend?.invoke() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = if (onSend != null) ImeAction.Send else ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// MARK: - Previews

@Preview(name = "Home · dark")
@Composable
private fun HomePreviewDark() {
    val model = HyphenUiModel()
    HyphenTheme(dark = true) { HyphenHome(model, HyphenActions()) }
}

@Preview(name = "Home · light")
@Composable
private fun HomePreviewLight() {
    val model = HyphenUiModel()
    HyphenTheme(dark = false) { HyphenHome(model, HyphenActions()) }
}
