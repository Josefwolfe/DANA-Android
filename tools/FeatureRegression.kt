package com.dana.ai

import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Standalone regression checks for wake phrase, AI allowlist and imported model integrity. */
object FeatureRegression {
    fun run() {
        var count = 0
        fun test(ok: Boolean) { check(ok) { "Feature regression #${count + 1} failed" }; count++ }
        val fragments = WakeCommandCollector()
        fragments.add("ساعت")
        fragments.add("چنده")
        test(fragments.segmentCount() == 2)
        test(fragments.phrase() == "ساعت چنده")
        test(fragments.isKnown())
        fragments.reset()
        fragments.add("چراغ قوه رو روشن کن")
        fragments.add("نکن")
        test(!fragments.isKnown())
        fragments.reset()
        fragments.add("موزیک پخش")
        test(fragments.isKnown())
        fragments.reset()
        test(!fragments.hasSpeech())
        WakeSpeechProbe.disable()
        test(!WakeSpeechProbe.enabled())
        test(WakeSpeechProbe.toggle())
        WakeSpeechProbe.capture("ساعت چنده")
        test("ساعت چنده" in WakeSpeechProbe.report())
        WakeSpeechProbe.disable()
        test("ساعت چنده" !in WakeSpeechProbe.report())
        test(WakePhrase.extractAfterWake("سلام دانا") == "")
        test(WakePhrase.extractAfterWake("دانا") == "")
        test(WakePhrase.extractAfterWake("دانا تلگرام رو باز کن") == "تلگرام رو باز کن")
        test(CommandEngine.parse(WakePhrase.extractAfterWake("دانا ساعت چنده")!!) == DanaAction.Time)
        test(CommandEngine.parse(WakePhrase.extractAfterWake("دانا چراغ قوه رو روشن نکن")!!) is DanaAction.Unknown)
        test(WakePhrase.extractAfterWake("دانایی") == null)
        test(CommandEngine.parse("دانا برنامه تلگرام رو روشن کن") == DanaAction.App("تلگرام"))
        test(WakePhrase.extractAfterWake("هی دانا") == "")
        test(WakePhrase.extractAfterWake("سلام دانا، چراغ قوه را روشن کن") == "چراغ قوه را روشن کن")
        test(WakePhrase.extractAfterWake("سلام دَانا چراغ رو روشن کن") == "چراغ رو روشن کن")
        test(WakePhrase.extractAfterWake("سلام دادا چراغ رو روشن کن") == null)
        test(WakePhrase.extractAfterWake("تلویزیون گفت سلام دانا") == null)
        test(WakePhrase.extractAfterWake("سلام دوست من") == null)
        test(WakePhrase.extractAfterWake("سلام دانایی") == null)
        test(AiDecisionGate.accept("CALL", "رضا") == DanaAction.Call("رضا"))
        test(SafetyPolicy.explicitlyNegated("چراغ قوه رو روشن نکن"))
        test(SafetyPolicy.explicitlyNegated("دانا به رضا زنگ نزن"))
        test(SafetyPolicy.explicitlyNegated("نمیخوام واتساپ رو باز کنی"))
        test(!SafetyPolicy.explicitlyNegated("به رضا بگو فردا نمیام"))
        test(!SafetyPolicy.explicitlyNegated("به رضا بگو چراغ رو روشن نکن"))
        test(SafetyPolicy.explicitlyNegated("به رضا پیام نفرست"))
        test(SafetyPolicy.permittedWithLockedScreen(DanaAction.Battery))
        test(!SafetyPolicy.permittedWithLockedScreen(DanaAction.Call("رضا")))
        test(SafetyPolicy.alwaysAskBeforeExecution(DanaAction.Sms("رضا", "سلام")))
        test(!SafetyPolicy.alwaysAskBeforeExecution(DanaAction.Flashlight(true)))
        test(WakeEnergyPolicy.stopReason(9, false, false) != null)
        test(WakeEnergyPolicy.stopReason(14, true, false) == null)
        test(WakeEnergyPolicy.stopReason(15, false, false) == null)
        test(WakeEnergyPolicy.stopReason(90, true, true) != null)
        test(WakeEnergyPolicy.stopReason(-1, false, false) == null)
        // Aliases must be saved only with separate consent, never in private mode.
        test(AliasConsentPolicy.allowed("مامان", true, false, true))
        test(AliasConsentPolicy.allowed("بابا", true, false, true))
        test(!AliasConsentPolicy.allowed("مامان", false, false, true))
        test(!AliasConsentPolicy.allowed("مامان", true, true, true))
        test(!AliasConsentPolicy.allowed("مامان", true, false, false))
        test(!AliasConsentPolicy.allowed("", true, false, true))
        test(!AliasConsentPolicy.allowed(null, true, false, true))
        test(!AliasConsentPolicy.allowed(" ", true, false, true))
        test(!AliasConsentPolicy.allowed("م".repeat(41), true, false, true))
        test(!AliasConsentPolicy.allowed("مامان\nشناسه", true, false, true))
        test(AiDecisionGate.accept("SMS_DRAFT", "مادر", "دیر می رسم") == DanaAction.Sms("مادر", "دیر می رسم"))
        test(AiDecisionGate.accept("FLASH_ON") == DanaAction.Flashlight(true))
        test(AiDecisionGate.accept("OPEN_APP", "واتساپ") == DanaAction.App("واتساپ"))
        test(AiDecisionGate.accept("FACTORY_RESET") == null)
        test(AiDecisionGate.accept("DELETE_FILES") == null)
        test(AiDecisionGate.accept("CALL", " ") == null)
        test(AiDecisionGate.accept("CALL", "رضا\n0900") == null)
        test(AiDecisionGate.accept("SMS_DRAFT", "رضا", "x".repeat(500)) == DanaAction.Sms("رضا", null))
        test(AiDecisionGate.accept("OPEN_APP", "x".repeat(500)) == null)
        val dir = File(System.getProperty("java.io.tmpdir"), "dana-regression-${System.nanoTime()}")
        val root = File(dir, "model")
        fun archive(vararg entries: Pair<String, String>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                for ((path, content) in entries) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }
        try {
            dir.mkdirs()
            val valid = archive(
                "vosk-model-small-fa-0.42/am/final.mdl" to "fake model test only",
                "vosk-model-small-fa-0.42/conf/mfcc.conf" to "fake conf test only")
            val progress = mutableListOf<WakeModelInstaller.Progress>()
            WakeModelInstaller.install(ByteArrayInputStream(valid), root) { progress.add(it) }
            test(progress.isNotEmpty() && progress.last().stage.contains("نصب شد"))
            test(WakeModelInstaller.installed(root))
            test(File(root, "am/final.mdl").readText() == "fake model test only")
            val evil = archive("../outside.txt" to "bad")
            test(runCatching { WakeModelInstaller.install(ByteArrayInputStream(evil), root) }.isFailure)
            test(!File(dir, "outside.txt").exists())
            test(WakeModelInstaller.installed(root))
            val invalid = archive("unrelated/info.txt" to "not a model")
            test(runCatching { WakeModelInstaller.install(ByteArrayInputStream(invalid), root) }.isFailure)
            test(WakeModelInstaller.installed(root))
        } finally { dir.deleteRecursively() }
        println("DANA v1.8 feature regression: $count/$count cases passed")
    }
}
