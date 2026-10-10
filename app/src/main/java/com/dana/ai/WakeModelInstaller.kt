package com.dana.ai

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Installs an independently downloaded Apache-2.0 Vosk Farsi model via SAF. No network on startup. */
object WakeModelInstaller {
    private const val MAX_UNCOMPRESSED = 384L * 1024 * 1024
    data class Progress(val fileCount: Int, val extractedBytes: Long, val stage: String)

    fun installed(root: File) = File(root, "am/final.mdl").isFile &&
        File(root, "am/final.mdl").length() > 0L &&
        File(root, "conf/mfcc.conf").isFile &&
        File(root, "conf/mfcc.conf").length() > 0L

    fun install(stream: InputStream, root: File, onProgress: ((Progress) -> Unit)? = null) {
        val temp = File(root.parentFile, "${root.name}.install")
        val backup = File(root.parentFile, "${root.name}.old")
        temp.deleteRecursively()
        check(temp.mkdirs()) { "نمی‌توان پوشه مدل را ساخت." }
        var total = 0L
        var count = 0
        var lastReport = 0L
        onProgress?.invoke(Progress(0, 0L, "در حال خواندن فایل ZIP…"))
        try {
            ZipInputStream(stream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    require(count <= 2000) { "تعداد فایل‌های بسته بیش از حد مجاز است." }
                    val raw = entry.name.replace('\\', '/')
                    val relative = if (raw.startsWith("vosk-model-")) raw.substringAfter('/', "") else raw
                    if (relative.isEmpty()) { zip.closeEntry(); continue }
                    require(!relative.startsWith('/') && !relative.split('/').contains("..") && !relative.contains(':')) {
                        "مسیر غیرمجاز در فایل ZIP."
                    }
                    val dst = File(temp, relative)
                    require(dst.canonicalPath.startsWith(temp.canonicalPath + File.separator)) { "مسیر فایل امن نیست." }
                    if (entry.isDirectory) {
                        check(dst.mkdirs() || dst.isDirectory)
                    } else {
                        check(dst.parentFile?.mkdirs() == true || dst.parentFile?.isDirectory == true)
                        dst.outputStream().buffered().use { output ->
                            val bytes = ByteArray(8192)
                            while (true) {
                                val size = zip.read(bytes)
                                if (size < 0) break
                                total += size
                                require(total <= MAX_UNCOMPRESSED) { "حجم مدل از حد مجاز بیشتر است." }
                                output.write(bytes, 0, size)
                                if (total - lastReport >= 2L * 1024 * 1024) {
                                    lastReport = total
                                    onProgress?.invoke(Progress(count, total, "در حال استخراج مدل فارسی…"))
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            onProgress?.invoke(Progress(count, total, "در حال بررسی فایل‌های مدل…"))
            require(installed(temp)) { "این ZIP مدل کوچک فارسی Vosk نیست (am/final.mdl یا conf/mfcc.conf پیدا نشد)." }
            backup.deleteRecursively()
            if (root.exists()) check(root.renameTo(backup)) { "نسخه قبلی مدل قابل انتقال نیست." }
            if (!temp.renameTo(root)) {
                backup.renameTo(root)
                error("مدل جدید نصب نشد.")
            }
            backup.deleteRecursively()
            onProgress?.invoke(Progress(count, total, "مدل استخراج و نصب شد."))
        } catch (e: Exception) { temp.deleteRecursively(); throw e }
    }
}
