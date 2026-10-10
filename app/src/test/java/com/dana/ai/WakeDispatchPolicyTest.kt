package com.dana.ai

import org.junit.Assert.*
import org.junit.Test

class WakeDispatchPolicyTest {
    @Test fun parsedAppNeedsVisibleActivityOrUserAction() {
        val action = CommandEngine.parse("تلگرام رو باز کن")
        assertTrue(action is DanaAction.App)
        assertTrue(WakeDispatchPolicy.requiresVisibleUi(action))
    }

    @Test fun phoneSettingsAndContactActionsRequireUi() {
        assertTrue(WakeDispatchPolicy.requiresVisibleUi(DanaAction.Settings("wifi")))
        assertTrue(WakeDispatchPolicy.requiresVisibleUi(DanaAction.Call("علی")))
        assertTrue(WakeDispatchPolicy.requiresVisibleUi(DanaAction.Sms("مریم", "سلام")))
        assertTrue(WakeDispatchPolicy.requiresVisibleUi(DanaAction.Timer(5, null)))
    }

    @Test fun localActionsRemainAvailableWithoutActivity() {
        assertFalse(WakeDispatchPolicy.requiresVisibleUi(DanaAction.Time))
        assertFalse(WakeDispatchPolicy.requiresVisibleUi(DanaAction.Battery))
        assertFalse(WakeDispatchPolicy.requiresVisibleUi(DanaAction.Flashlight(true)))
        assertFalse(WakeDispatchPolicy.requiresVisibleUi(DanaAction.Media("pause")))
    }

    @Test fun memoryOnlyMetricsNeverContainSpokenContent() {
        WakeExecutionMetrics.received("App")
        WakeExecutionMetrics.outcome("USER_TAP_REQUIRED")
        val report = WakeExecutionMetrics.report()
        assertTrue(report.contains("Last command category: APP"))
        assertTrue(report.contains("Last dispatch outcome: USER_TAP_REQUIRED"))
        assertFalse(report.contains("تلگرام"))
    }
}
