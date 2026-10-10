package com.dana.ai

import org.junit.Assert.*
import org.junit.Test

class DiagnosticEventRulesTest {
    @Test fun onlyFixedFormatEventTokensAccepted() {
        assertEquals("MODEL_IMPORT_FAILED", DiagnosticEventRules.safeToken("MODEL_IMPORT_FAILED"))
        assertNull(DiagnosticEventRules.safeToken("call to 09123456789"))
        assertNull(DiagnosticEventRules.safeToken("SECRET=abc"))
        assertNull(DiagnosticEventRules.safeToken("فرمان شخصی"))
        assertNull(DiagnosticEventRules.safeToken("A".repeat(49)))
    }

    @Test fun errorTextIsReducedToSafeCategories() {
        assertEquals("TIMEOUT", DiagnosticEventRules.failureCategory("بارگذاری مدل بیش از ۹۰ ثانیه طول کشید"))
        assertEquals("PERMISSION", DiagnosticEventRules.failureCategory("مجوز میکروفون لازم است"))
        assertEquals("MODEL", DiagnosticEventRules.failureCategory("model /data/user/0/private/final.mdl failed"))
        assertEquals("NONE", DiagnosticEventRules.failureCategory(null))
    }
}
