package dev.hyphen.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstUseOnboardingTest {
    @Test
    fun `unfinished first use is visible and dismiss is session only`() {
        val initial = FirstUseOnboardingState(completedVersion = 0, hasTrustedPeer = false)

        assertTrue(initial.isVisible)
        val dismissed = initial.dismissForSession()
        assertFalse(dismissed.isVisible)

        val nextLaunch = FirstUseOnboardingState(completedVersion = 0, hasTrustedPeer = false)
        assertTrue(nextLaunch.isVisible)
    }

    @Test
    fun `trusted peer or current completion version suppresses first use`() {
        assertFalse(
            FirstUseOnboardingState(
                completedVersion = 0,
                hasTrustedPeer = true,
            ).isVisible,
        )
        assertFalse(
            FirstUseOnboardingState(
                completedVersion = FirstUseOnboardingContract.CURRENT_VERSION,
                hasTrustedPeer = false,
            ).isVisible,
        )
    }

    @Test
    fun `reopen is available after a session dismissal`() {
        val reopened = FirstUseOnboardingState(
            completedVersion = 0,
            hasTrustedPeer = false,
            dismissedForSession = true,
        ).reopen()

        assertTrue(reopened.isVisible)
        assertFalse(reopened.dismissedForSession)
    }

    @Test
    fun `session presentation flags can be restored across activity recreation`() {
        val model = HyphenUiModel()

        model.initializeFirstUseOnboarding(
            completedVersion = 0,
            hasTrustedPeer = false,
            dismissedForSession = true,
        )

        assertFalse(model.firstUseOnboarding.isVisible)
        assertTrue(model.firstUseOnboarding.dismissedForSession)
    }

    @Test
    fun `explicit reopen can show the guide after pairing or completion`() {
        val reopened = FirstUseOnboardingState(
            completedVersion = FirstUseOnboardingContract.CURRENT_VERSION,
            hasTrustedPeer = true,
        ).reopen()

        assertTrue(reopened.isVisible)
    }
}

class ConnectionRecoveryPromptTest {
    @Test
    fun `only actionable paired states produce recovery prompts`() {
        assertNull(connectionRecoveryPrompt(ConnectionSnapshot()))
        assertNull(
            connectionRecoveryPrompt(
                ConnectionSnapshot(state = ConnectionState.CONNECTED, isPaired = true),
            ),
        )
        assertNull(
            connectionRecoveryPrompt(
                ConnectionSnapshot(state = ConnectionState.DISCOVERING, isPaired = true),
            ),
        )

        assertEquals(
            ConnectionRecoveryKind.DEGRADED,
            connectionRecoveryPrompt(
                ConnectionSnapshot(state = ConnectionState.DEGRADED, isPaired = true),
            )?.kind,
        )
        assertEquals(
            ConnectionRecoveryKind.RECONNECTING,
            connectionRecoveryPrompt(
                ConnectionSnapshot(state = ConnectionState.RECONNECTING, isPaired = true),
            )?.kind,
        )
        assertEquals(
            ConnectionRecoveryKind.SUSPENDED,
            connectionRecoveryPrompt(
                ConnectionSnapshot(state = ConnectionState.SUSPENDED, isPaired = true),
            )?.kind,
        )
    }
}
