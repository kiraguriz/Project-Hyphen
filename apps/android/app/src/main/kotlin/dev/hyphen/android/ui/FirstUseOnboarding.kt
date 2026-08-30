package dev.hyphen.android.ui

import android.content.Context

/** Versioned first-use state. Only the completed version is persisted. */
object FirstUseOnboardingContract {
    const val CURRENT_VERSION = 1
}

data class FirstUseOnboardingState(
    val completedVersion: Int = 0,
    val hasTrustedPeer: Boolean = false,
    val dismissedForSession: Boolean = false,
    val openedExplicitly: Boolean = false,
) {
    val isVisible: Boolean
        get() = openedExplicitly ||
            (completedVersion < FirstUseOnboardingContract.CURRENT_VERSION &&
                !hasTrustedPeer &&
                !dismissedForSession)

    fun dismissForSession(): FirstUseOnboardingState = copy(
        dismissedForSession = true,
        openedExplicitly = false,
    )

    fun reopen(): FirstUseOnboardingState = copy(
        dismissedForSession = false,
        openedExplicitly = true,
    )

    fun complete(): FirstUseOnboardingState = copy(
        completedVersion = FirstUseOnboardingContract.CURRENT_VERSION,
        dismissedForSession = false,
        openedExplicitly = false,
    )
}

/**
 * Stores one non-sensitive integer. Pairing payloads, progress, permissions,
 * and device identifiers never enter onboarding preferences.
 */
class FirstUseOnboardingPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun completedVersion(): Int = preferences.getInt(KEY_COMPLETED_VERSION, 0)

    fun markCurrentVersionCompleted(): Boolean = preferences.edit()
        .putInt(KEY_COMPLETED_VERSION, FirstUseOnboardingContract.CURRENT_VERSION)
        .commit()

    private companion object {
        const val PREFERENCES_NAME = "dev.hyphen.android.first_use"
        const val KEY_COMPLETED_VERSION = "completed_version"
    }
}

enum class ConnectionRecoveryKind {
    DEGRADED,
    RECONNECTING,
    SUSPENDED,
}

data class ConnectionRecoveryPrompt(val kind: ConnectionRecoveryKind)

/** Recovery is offered only when trust exists and a user action is meaningful. */
fun connectionRecoveryPrompt(connection: ConnectionSnapshot): ConnectionRecoveryPrompt? {
    if (!connection.isPaired) return null
    val kind = when (connection.state) {
        ConnectionState.DEGRADED -> ConnectionRecoveryKind.DEGRADED
        ConnectionState.RECONNECTING -> ConnectionRecoveryKind.RECONNECTING
        ConnectionState.SUSPENDED -> ConnectionRecoveryKind.SUSPENDED
        ConnectionState.CONNECTED,
        ConnectionState.DISCOVERING,
        ConnectionState.SLEEPING,
        -> return null
    }
    return ConnectionRecoveryPrompt(kind)
}
