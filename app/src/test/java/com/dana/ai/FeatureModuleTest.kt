package com.dana.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.zip.*

class FeatureModuleTest {
    @Test fun wakePhraseDoesNotActivateOnUnrelatedText() {
        assertEquals("", WakePhrase.extractAfterWake("سلام دانا"))
        assertEquals("", WakePhrase.extractAfterWake("دانا"))
        assertEquals("تلگرام رو باز کن", WakePhrase.extractAfterWake("دانا تلگرام رو باز کن"))
        assertNull(WakePhrase.extractAfterWake("دانایی"))
        assertEquals("چراغ قوه رو روشن کن", WakePhrase.extractAfterWake("سلام دانا چراغ قوه رو روشن کن"))
        assertNull(WakePhrase.extractAfterWake("در فیلم گفت سلام دانا"))
        assertNull(WakePhrase.extractAfterWake("سلام دانایی"))
    }

    @Test fun aiOutputHasStrictAllowlistAndContactValidation() {
        assertEquals(DanaAction.Call("رضا"), AiDecisionGate.accept("CALL", "رضا"))
        assertEquals(DanaAction.Sms("مریم", "سلام"), AiDecisionGate.accept("SMS_DRAFT", "مریم", "سلام"))
        assertNull(AiDecisionGate.accept("DELETE_ALL"))
        assertNull(AiDecisionGate.accept("CALL", "\n0912"))
    }

    @Test fun modelImporterRejectsZipTraversalAndPreservesExistingModel() {
        val home = createTempDir(prefix = "dana-model-test-")
        val root = File(home, "model")
        fun zip(vararg entries: Pair<String, String>): ByteArray {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { archive -> entries.forEach { (path, text) ->
                archive.putNextEntry(ZipEntry(path))
                archive.write(text.toByteArray())
                archive.closeEntry()
            } }
            return bytes.toByteArray()
        }
        try {
            val good = zip("vosk-model-small-fa-0.42/am/final.mdl" to "fixture",
                "vosk-model-small-fa-0.42/conf/mfcc.conf" to "fixture")
            val stages = mutableListOf<WakeModelInstaller.Progress>()
            WakeModelInstaller.install(ByteArrayInputStream(good), root) { stages.add(it) }
            assertTrue(stages.isNotEmpty())
            assertTrue(stages.last().stage.contains("نصب شد"))
            assertTrue(WakeModelInstaller.installed(root))
            val malicious = zip("../path-escape.txt" to "unsafe")
            assertTrue(runCatching { WakeModelInstaller.install(ByteArrayInputStream(malicious), root) }.isFailure)
            assertFalse(File(home, "path-escape.txt").exists())
            assertTrue(WakeModelInstaller.installed(root))
        } finally { home.deleteRecursively() }
    }
}
