package com.dana.ai

/** Treat every remote model response as untrusted. No arbitrary Android intents or shell commands. */
object AiDecisionGate {
    private fun clean(value: String?, max: Int): String? {
        val raw = value ?: return null
        if (raw.any { it.isISOControl() }) return null
        val normalized = raw.trim()
        return normalized.takeIf { it.isNotBlank() && it.length <= max }
    }

    fun accept(
        action: String?, target: String? = null, message: String? = null,
        hour: Int? = null, minute: Int? = null, minutes: Int? = null,
        dayOffset: Int? = null, answer: String? = null
    ): DanaAction? {
        val name = clean(target, 120)
        val label = clean(message, 160)
        return when (action?.uppercase(java.util.Locale.ROOT)) {
            "TIME" -> DanaAction.Time
            "BATTERY" -> DanaAction.Battery
            "TODAY_DATE" -> DanaAction.TodayDate
            "HELP" -> DanaAction.Help
            "CHAT_ANSWER" -> clean(answer, 450)?.let { DanaAction.Answer(it) }
            "OPEN_CAMERA" -> DanaAction.Camera
            "OPEN_CONTACTS" -> DanaAction.Contacts
            "OPEN_CALENDAR" -> DanaAction.Calendar
            "OPEN_APP" -> name?.takeIf { it.length <= 80 }?.let { DanaAction.App(it) }
            "CALL" -> name?.takeIf { it.length <= 80 }?.let { DanaAction.Call(it) }
            "SMS_DRAFT" -> name?.takeIf { it.length <= 80 }?.let {
                DanaAction.Sms(it, message?.takeIf { body ->
                    body.length <= 450 && body.isNotBlank() && body.none { ch -> ch.isISOControl() }
                })
            }
            "FLASH_ON" -> DanaAction.Flashlight(true)
            "FLASH_OFF" -> DanaAction.Flashlight(false)
            "VOLUME_UP" -> DanaAction.Volume(1)
            "VOLUME_DOWN" -> DanaAction.Volume(-1)
            "MUSIC_RANDOM" -> DanaAction.Music(null)
            "MUSIC_SEARCH" -> name?.let { DanaAction.Music(it) }
            "MEDIA_PAUSE" -> DanaAction.Media("pause")
            "MEDIA_RESUME" -> DanaAction.Media("resume")
            "MEDIA_NEXT" -> DanaAction.Media("next")
            "MEDIA_PREVIOUS" -> DanaAction.Media("previous")
            "MEDIA_STOP" -> DanaAction.Media("stop")
            "WIFI_ON" -> DanaAction.Connectivity("wifi", true)
            "WIFI_OFF" -> DanaAction.Connectivity("wifi", false)
            "BLUETOOTH_ON" -> DanaAction.Connectivity("bluetooth", true)
            "BLUETOOTH_OFF" -> DanaAction.Connectivity("bluetooth", false)
            "WEB_SEARCH" -> name?.let { DanaAction.WebSearch(it) }
            "YOUTUBE_SEARCH" -> name?.let { DanaAction.YouTubeSearch(it) }
            "NAVIGATE" -> name?.let { DanaAction.Navigate(it) }
            "SET_TIMER" -> minutes?.takeIf { it in 1..1440 }?.let { DanaAction.Timer(it, label) }
            "SET_ALARM" -> if (hour != null && hour in 0..23 && minute != null && minute in 0..59 &&
                (dayOffset == null || dayOffset in 0..7)) {
                if (dayOffset == null) DanaAction.Alarm(hour, minute, label)
                else DanaAction.DatedAlarm(dayOffset, null, hour, minute, label)
            } else null
            else -> null
        }
    }
}
