package com.dana.ai

/** AI output is untrusted. Only explicitly allowlisted, validated actions reach Android. */
object AiDecisionGate {
    private fun clean(value: String?, max: Int): String? = value?.trim()
        ?.takeIf { it.isNotBlank() && it.length <= max && !it.any { ch -> ch.isISOControl() } }

    fun accept(action: String?, target: String? = null, message: String? = null): DanaAction? {
        val name = clean(target, 80)
        return when (action?.uppercase(java.util.Locale.ROOT)) {
            "TIME" -> DanaAction.Time
            "BATTERY" -> DanaAction.Battery
            "OPEN_CAMERA" -> DanaAction.Camera
            "OPEN_CONTACTS" -> DanaAction.Contacts
            "OPEN_APP" -> name?.let { DanaAction.App(it) }
            "CALL" -> name?.let { DanaAction.Call(it) }
            "SMS_DRAFT" -> name?.let { DanaAction.Sms(it, message?.takeIf { body ->
                body.length <= 450 && body.isNotBlank() && body.none { ch -> ch.isISOControl() }
            }) }
            "FLASH_ON" -> DanaAction.Flashlight(true)
            "FLASH_OFF" -> DanaAction.Flashlight(false)
            "VOLUME_UP" -> DanaAction.Volume(1)
            "VOLUME_DOWN" -> DanaAction.Volume(-1)
            else -> null
        }
    }
}
