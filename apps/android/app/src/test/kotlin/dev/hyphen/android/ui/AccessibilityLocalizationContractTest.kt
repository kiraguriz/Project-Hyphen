package dev.hyphen.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-side guards for the Android half of the a11y/localization gate. */
class AccessibilityLocalizationContractTest {
    private val androidRoot by lazy(::locateAndroidRoot)

    @Test
    fun `default and English string tables expose the same keys`() {
        val chinese = stringKeys("values/strings.xml")
        val english = stringKeys("values-en/strings.xml")

        assertTrue("the localized resource contract must not be empty", chinese.isNotEmpty())
        assertEquals("values and values-en must stay in exact key parity", chinese, english)
    }

    @Test
    fun `every manifest label resolves through localized resources`() {
        val manifest = androidRoot.resolve("app/src/main/AndroidManifest.xml").readText()
        val literalLabels = Regex("""android:label=(?:\"|')(?!@string/)([^\"']+)(?:\"|')""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toList()

        assertTrue("manifest labels must not embed one locale", literalLabels.isEmpty())
        assertTrue(
            manifest.contains("android:label=\"@string/notification_listener_service_label\""),
        )
        assertTrue(stringKeys("values/strings.xml").contains("notification_listener_service_label"))
    }

    @Test
    fun `editable fields keep a visible label and an accessibility name`() {
        val source = androidRoot
            .resolve("app/src/main/kotlin/dev/hyphen/android/ui/HyphenHome.kt")
            .readText()

        assertTrue(source.contains("label: String"))
        assertTrue(source.contains("OutlinedTextField("))
        assertTrue(source.contains("label = { Text(label"))
        assertTrue(source.contains("placeholder = { Text(hint"))
        assertTrue(
            "do not split the accessible name from editable semantics with a wrapper description",
            !source.contains("contentDescription = label"),
        )
        for (key in listOf(
            "first_use_pairing_payload_label",
            "field_send_label",
            "perm_manual_label",
        )) {
            assertTrue("missing persistent label wiring for $key", source.contains("label = stringResource(R.string.$key)"))
        }
    }

    private fun stringKeys(relativeFile: String): Set<String> {
        val source = androidRoot.resolve("app/src/main/res/$relativeFile").readText()
        return Regex("""<string\s+name=(?:\"|')([^\"']+)(?:\"|')""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSortedSet()
    }

    private fun locateAndroidRoot(): File {
        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        return generateSequence(start) { current -> current.parentFile }
            .flatMap { current -> sequenceOf(current, current.resolve("apps/android")) }
            .firstOrNull { it.resolve("app/src/main/AndroidManifest.xml").isFile }
            ?: error("could not locate apps/android from $start")
    }
}
