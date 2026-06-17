package dev.hyphen.android.ui

import androidx.annotation.StringRes
import dev.hyphen.android.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The callbacks the Compose UI invokes; the controller (`MainActivity`) wires
 * them to the existing backend methods. Keeping them in one immutable holder
 * keeps the UI a pure function of (state, actions).
 */
data class HyphenActions(
    val onFindMac: () -> Unit = {},
    val onManualConnect: (String) -> Unit = {},
    val onCreateAssociation: () -> Unit = {},
    val onListAssociations: () -> Unit = {},
    val onManagePeers: () -> Unit = {},
    val onCheckNotificationAccess: () -> Unit = {},
    val onOpenNotificationMirror: () -> Unit = {},
    val onCycleNotificationPrivacy: () -> Unit = {},
    val onSendText: (String) -> Unit = {},
    val onSendFile: () -> Unit = {},
    val onCancelTransfer: () -> Unit = {},
    val onToggleBetaDiagnostics: () -> Unit = {},
    val onPreviewDiagnostics: () -> Unit = {},
    val onExportDiagnostics: () -> Unit = {},
    val onDeleteDiagnostics: () -> Unit = {},
)

internal object HyphenFormat {
    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("H:mm", Locale.US)

    fun time(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        timeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = listOf("KB", "MB", "GB", "TB")
        var size = value.toDouble() / 1024
        var unit = 0
        while (size >= 1024 && unit < units.size - 1) {
            size /= 1024
            unit++
        }
        return if (size >= 100) "${size.toInt()} ${units[unit]}"
        else String.format(Locale.US, "%.1f %s", size, units[unit])
    }
}

@StringRes
internal fun ConnectionState.titleRes(): Int = when (this) {
    ConnectionState.CONNECTED -> R.string.state_connected
    ConnectionState.DEGRADED -> R.string.state_degraded
    ConnectionState.RECONNECTING -> R.string.state_reconnecting
    ConnectionState.DISCOVERING -> R.string.state_discovering
    ConnectionState.SLEEPING -> R.string.state_sleeping
    ConnectionState.SUSPENDED -> R.string.state_suspended
}
