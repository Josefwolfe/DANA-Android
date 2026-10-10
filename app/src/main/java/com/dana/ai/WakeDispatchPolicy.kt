package com.dana.ai

/** Android background activity launch restrictions require an already-visible UI for these actions.
 * When the UI is not visible, DANA may only offer an explicit user-tap notification (or an
 * independently enabled, system-bound accessibility service for launching named apps). */
object WakeDispatchPolicy {
    fun requiresVisibleUi(action: DanaAction): Boolean =
        action is DanaAction.App || action is DanaAction.Camera ||
        action is DanaAction.Contacts || action is DanaAction.Calendar ||
        action is DanaAction.WebSearch || action is DanaAction.YouTubeSearch ||
        action is DanaAction.Navigate || action is DanaAction.Settings ||
        action is DanaAction.Call || action is DanaAction.Sms ||
        action is DanaAction.Timer || action is DanaAction.Brightness ||
        action is DanaAction.Connectivity || action is DanaAction.PrivateMode ||
        action is DanaAction.LearningMode || action is DanaAction.ResponseStyle
}
