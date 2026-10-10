package com.dana.ai

/** Offline-only choice for wake-triggered commands. Never selects a network recognizer. */
object WakeAsrRoutingPolicy {
    fun useAndroidOnDevice(sdk: Int, onDeviceAvailable: Boolean, failedThisSession: Boolean): Boolean =
        sdk >= 31 && onDeviceAvailable && !failedThisSession

    fun retryWithVosk(currentStage: String, stopped: Boolean): Boolean =
        !stopped && currentStage == "command_on_device"
}
