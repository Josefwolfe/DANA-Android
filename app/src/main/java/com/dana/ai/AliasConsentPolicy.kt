package com.dana.ai

/** Pure policy: contact aliases are remembered only after a separate affirmative response. */
object AliasConsentPolicy {
    fun allowed(alias: String?, explicitlyConfirmed: Boolean, privateMode: Boolean,
                learningEnabled: Boolean): Boolean =
        explicitlyConfirmed && !privateMode && learningEnabled &&
            !alias.isNullOrBlank() && alias.length <= 40 &&
            alias.none(Char::isISOControl)
}
