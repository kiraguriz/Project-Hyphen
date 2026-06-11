package dev.hyphen.android.pairing

import dev.hyphen.android.trust.hyphenHexToBytesOrNull
import dev.hyphen.android.trust.toHyphenHex
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reproduces every case in the normative vector file
 * `protocol/test-vectors/pairing/sas-vectors.json` (HYP-M2-004) — the
 * Kotlin implementation is conformant only if all of them match.
 */
class PairingTranscriptTest {

    /** Walks up from the test working directory to the repo root. */
    private fun vectorFile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "protocol/test-vectors/pairing/sas-vectors.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("sas-vectors.json not found above ${System.getProperty("user.dir")}")
    }

    /**
     * Field extraction for the known-flat vector objects — org.json is a
     * stub in JVM unit tests and a JSON library would be a new dependency.
     */
    private fun cases(section: String): List<Map<String, String>> {
        val text = vectorFile().readText()
        val sectionStart = text.indexOf("\"$section\"")
        check(sectionStart >= 0) { "section $section missing" }
        val arrayStart = text.indexOf('[', sectionStart)
        val arrayEnd = text.indexOf(']', arrayStart)
        return Regex("\\{[^{}]*\\}")
            .findAll(text.substring(arrayStart + 1, arrayEnd))
            .map { obj ->
                Regex("\"([A-Za-z]+)\"\\s*:\\s*\"([^\"]*)\"")
                    .findAll(obj.value)
                    .associate { it.groupValues[1] to it.groupValues[2] }
            }
            .toList()
    }

    private fun transcriptOf(vector: Map<String, String>): PairingTranscript {
        val transcript = PairingTranscript.create(
            nonce = vector.getValue("nonceHex").hyphenHexToBytesOrNull()!!,
            macSpkiFingerprint = vector.getValue("macSpkiFpHex").hyphenHexToBytesOrNull()!!,
            androidSpkiFingerprint = vector.getValue("androidSpkiFpHex").hyphenHexToBytesOrNull()!!,
            protocolVersion = vector.getValue("protocolVersion"),
        )
        assertNotNull("vector ${vector["name"]} did not build", transcript)
        return transcript!!
    }

    @Test
    fun `reproduces every normative vector`() {
        val vectors = cases("cases")
        assertEquals("vector file shrank — investigate", 5, vectors.size)
        for (vector in vectors) {
            val result = transcriptOf(vector)
            assertEquals(
                "transcript hash mismatch in ${vector["name"]}",
                vector.getValue("expectedTranscriptHashHex"),
                result.hash.toHyphenHex(),
            )
            assertEquals("SAS mismatch in ${vector["name"]}", vector.getValue("expectedSas"), result.sas)
            assertEquals("SAS must render 6 digits in ${vector["name"]}", 6, result.sas.length)
        }
    }

    @Test
    fun `tamper cases are detected as mismatch`() {
        val tamperCases = cases("tamperCases")
        check(tamperCases.isNotEmpty())
        for (vector in tamperCases) {
            assertNotEquals(
                "verifier self-test ${vector["name"]}: wrong expectation must mismatch",
                vector.getValue("expectedSas"),
                transcriptOf(vector).sas,
            )
        }
    }

    @Test
    fun `transcript layout is label nonce fps version`() {
        val nonce = ByteArray(16) { 0x01 }
        val mac = ByteArray(32) { 0x02 }
        val android = ByteArray(32) { 0x03 }
        val transcript = PairingTranscript.create(nonce, mac, android, "hyphen/0.3")!!
        assertArrayEquals(
            PairingTranscript.LABEL.toByteArray(Charsets.US_ASCII) +
                nonce + mac + android + "hyphen/0.3".toByteArray(Charsets.UTF_8),
            transcript.transcriptData,
        )
    }

    @Test
    fun `invalid field lengths are rejected`() {
        val nonce = ByteArray(16)
        val fp = ByteArray(32)
        assertNull(PairingTranscript.create(ByteArray(15), fp, fp, "hyphen/0.3"))
        assertNull(PairingTranscript.create(nonce, ByteArray(31), fp, "hyphen/0.3"))
        assertNull(PairingTranscript.create(nonce, fp, ByteArray(33), "hyphen/0.3"))
        assertNull(PairingTranscript.create(nonce, fp, fp, ""))
    }
}
