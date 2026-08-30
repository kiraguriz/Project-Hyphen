package dev.hyphen.android.diagnostics

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsDisclosureTest {
    @Test
    fun `trace id disclosure covers previews and exports in both locales`() {
        val english = stringResource("values-en/strings.xml", "diag_trace_detail")
        val chinese = stringResource("values/strings.xml", "diag_trace_detail")

        assertTrue("English copy must disclose previews", english.contains("preview", ignoreCase = true))
        assertTrue("English copy must disclose exports", english.contains("export", ignoreCase = true))
        assertTrue("Chinese copy must disclose previews", chinese.contains("预览"))
        assertTrue("Chinese copy must disclose exports", chinese.contains("导出"))
    }

    private fun stringResource(relativeFile: String, key: String): String {
        val source = locateAndroidRoot().resolve("app/src/main/res/$relativeFile").readText()
        val pattern = Regex("""<string\s+name=[\"']$key[\"']>([^<]*)</string>""")
        return requireNotNull(pattern.find(source)?.groupValues?.get(1)) {
            "missing string resource $key in $relativeFile"
        }
    }

    private fun locateAndroidRoot(): File {
        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        return generateSequence(start) { current -> current.parentFile }
            .flatMap { current ->
                sequenceOf(current, current.resolve("apps/android"))
            }
            .firstOrNull { it.resolve("app/src/main/res/values/strings.xml").isFile }
            ?: error("could not locate apps/android from $start")
    }
}
