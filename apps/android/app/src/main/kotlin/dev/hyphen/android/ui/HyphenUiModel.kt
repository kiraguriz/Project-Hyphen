package dev.hyphen.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Compose-facing observable holder for [HyphenUiState] (frontend UX plan B1),
 * the Android analogue of the macOS `HyphenAppModel`. The backend controller
 * (`MainActivity`) feeds it [ActivityEvent]s; Compose reads [state] and
 * recomposes.
 *
 * All mutation must happen on the main thread (Compose snapshot requirement).
 * The controller marshals through `postToUi` before calling [apply]/[setLog].
 */
class HyphenUiModel {
    var state by mutableStateOf(HyphenUiState())
        private set

    // Reactive UI-settings the controls display. These live in SharedPreferences /
    // the notification-listener runtime, not in the pure feed reducer, so they
    // are held as plain observable fields the controller pushes into.
    var notificationPrivacyLabel by mutableStateOf("完整")
        private set
    var betaDiagnosticsOn by mutableStateOf(false)
        private set
    var notificationAccessEnabled by mutableStateOf(false)
        private set

    private var idCounter = 0L

    fun apply(event: ActivityEvent) {
        state = HyphenReducer.reduce(state, event) { idCounter++ }
    }

    /** Replace the auditable event-log lines (the "本机事件流" card). */
    fun setLog(lines: List<String>) {
        state = state.copy(logLines = lines)
    }

    fun updateNotificationPrivacyLabel(label: String) { notificationPrivacyLabel = label }

    fun setBetaDiagnostics(enabled: Boolean) { betaDiagnosticsOn = enabled }

    fun setNotificationAccess(enabled: Boolean) { notificationAccessEnabled = enabled }
}
