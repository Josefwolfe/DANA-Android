package com.dana.ai

import org.junit.Assert.*
import org.junit.Test

class AliasConsentPolicyTest {
    @Test fun confirmedAliasCanBeRemembered() {
        assertTrue(AliasConsentPolicy.allowed("مامان", true, false, true))
    }
    @Test fun rejectsImplicitLearning() {
        assertFalse(AliasConsentPolicy.allowed("مامان", false, false, true))
    }
    @Test fun respectsPrivateModeAndLearningPreference() {
        assertFalse(AliasConsentPolicy.allowed("بابا", true, true, true))
        assertFalse(AliasConsentPolicy.allowed("بابا", true, false, false))
    }
    @Test fun rejectsMaliciousOrBlankAliases() {
        assertFalse(AliasConsentPolicy.allowed("", true, false, true))
        assertFalse(AliasConsentPolicy.allowed("م".repeat(41), true, false, true))
        assertFalse(AliasConsentPolicy.allowed("بابا\n0900", true, false, true))
    }
}
