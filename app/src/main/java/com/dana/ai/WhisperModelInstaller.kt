package com.dana.ai

import java.io.File
import java.io.InputStream

/** Import a multilingual ggml-tiny.bin / ggml-base.bin using SAF, without downloading it. */
object WhisperModelInstaller {
    private const val MAX_BYTES = 1024L * 1024 * 1024
    private const val MIN_BYTES = 20L * 1024 * 1024
    private val MAGIC = byteArrayOf(0x6c, 0x6d, 0x67, 0x67) // ggml magic, little-endian
    const val DIR = "whisper_fa"
    const val FILE = "ggml-model.bin"

    fun model(root: File) = File(root, FILE)

    fun installed(root: File): Boolean {
        val file = model(root)
        if (!file.isFile || file.length() !in MIN_BYTES..MAX_BYTES) return false
        return runCatching { file.inputStream().use { checkHeader(it) } }.getOrDefault(false)
    }

    fun checkHeader(input: InputStream): Boolean {
        val bytes = ByteArray(4)
        var total = 0
        while (total < 4) {
            val n = input.read(bytes, total, 4 - total)
            if (n <= 0) return false
            total += n
        }
        return bytes.contentEquals(MAGIC)
    }

    /** Transactional install; never delete the previous good model before validating copy. */
    fun install(input: InputStream, root: File, progress: (Long) -> Unit = {}) {
        require(root.isDirectory || root.mkdirs()) { "Cannot create private model folder" }
        val target = model(root)
        val temp = File(root, "$FILE.pending")
        val backup = File(root, "$FILE.backup")
        temp.delete()
        var count = 0L
        try {
            input.buffered().use { source ->
                temp.outputStream().buffered().use { out ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        count += n
                        require(count <= MAX_BYTES) { "Model exceeds 1 GB" }
                        out.write(buffer, 0, n)
                        if (count % (4L * 1024 * 1024) < buffer.size) progress(count)
                    }
                }
            }
            require(count >= MIN_BYTES && installedFile(temp)) {
                "Invalid or too small ggml Whisper model. Choose multilingual tiny/base, not .en"
            }
            backup.delete()
            if (target.exists()) check(target.renameTo(backup)) { "Cannot back up previous model" }
            if (!temp.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("Cannot activate imported model")
            }
            backup.delete()
            progress(count)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun installedFile(file: File): Boolean = file.isFile &&
        file.length() in MIN_BYTES..MAX_BYTES &&
        file.inputStream().use { checkHeader(it) }
}
