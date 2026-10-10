package com.dana.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class WhisperModelInstallerTest {
    @Test fun ggmlMagicIsLittleEndian() {
        assertTrue(WhisperModelInstaller.checkHeader(ByteArrayInputStream(byteArrayOf(0x6c, 0x6d, 0x67, 0x67))))
        assertFalse(WhisperModelInstaller.checkHeader(ByteArrayInputStream(byteArrayOf(0x67, 0x67, 0x6d, 0x6c))))
        assertFalse(WhisperModelInstaller.checkHeader(ByteArrayInputStream(byteArrayOf(0x6c))))
    }

    @Test fun invalidImportDoesNotReplacePreviousModel() {
        val dir = Files.createTempDirectory("dana-whisper-test").toFile()
        try {
            val valid = ByteArray(20 * 1024 * 1024 + 1)
            valid[0] = 0x6c; valid[1] = 0x6d; valid[2] = 0x67; valid[3] = 0x67
            WhisperModelInstaller.install(ByteArrayInputStream(valid), dir)
            assertTrue(WhisperModelInstaller.installed(dir))
            val before = WhisperModelInstaller.model(dir).length()
            try {
                WhisperModelInstaller.install(ByteArrayInputStream(byteArrayOf(1, 2, 3)), dir)
                fail("Invalid model should be rejected")
            } catch (_: IllegalArgumentException) { }
            assertEquals(before, WhisperModelInstaller.model(dir).length())
        } finally { dir.deleteRecursively() }
    }
}
