package com.dana.ai

import org.junit.Assert.*
import org.junit.Test

class WakeAsrRoutingPolicyTest {
    @Test fun choosesOnlyExplicitlyOnDeviceRecognition() {
        assertTrue(WakeAsrRoutingPolicy.useAndroidOnDevice(36, true, false))
        assertFalse(WakeAsrRoutingPolicy.useAndroidOnDevice(30, true, false))
        assertFalse(WakeAsrRoutingPolicy.useAndroidOnDevice(36, false, false))
        assertFalse(WakeAsrRoutingPolicy.useAndroidOnDevice(36, true, true))
    }

    @Test fun fallbackOnlyDuringLiveOnDeviceCommand() {
        assertTrue(WakeAsrRoutingPolicy.retryWithVosk("command_on_device", false))
        assertFalse(WakeAsrRoutingPolicy.retryWithVosk("wake", false))
        assertFalse(WakeAsrRoutingPolicy.retryWithVosk("reply", false))
        assertFalse(WakeAsrRoutingPolicy.retryWithVosk("command_on_device", true))
    }
}
