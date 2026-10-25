package dev.hyphen.android.pairing

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract for the dependency-free QR handoff (HYP-M2-010).
 *
 * On Android 11+ package visibility filtering makes `resolveActivity()` return
 * null for the still-image camera action unless the manifest declares a
 * `<queries>` intent for it. Observed on the Android 17 emulator: the system
 * camera existed and resolved from the shell, but the app reported
 * "System camera unavailable". The declaration grants no permission.
 */
class CameraHandoffVisibilityContractTest {
    @Test
    fun `manifest declares still-image camera visibility`() {
        val manifest = androidRoot().resolve("app/src/main/AndroidManifest.xml").readText()
        val queries = Regex("""<queries>(.*?)</queries>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest)
            .map { it.groupValues[1] }
            .joinToString("\n")
        assertTrue(
            "manifest must declare <queries> visibility for android.media.action.STILL_IMAGE_CAMERA",
            queries.contains("android.media.action.STILL_IMAGE_CAMERA"),
        )
    }

    @Test
    fun `camera handoff never requests camera permission`() {
        val manifest = androidRoot().resolve("app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "the QR handoff must stay an external system-camera entry (ADR-0003)",
            !manifest.contains("android.permission.CAMERA"),
        )
    }

    private fun androidRoot(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        return generateSequence(start) { current -> current.parentFile }
            .flatMap { current -> sequenceOf(current, current.resolve("apps/android")) }
            .firstOrNull { it.resolve("app/src/main/AndroidManifest.xml").isFile }
            ?: error("could not locate apps/android from $start")
    }
}
