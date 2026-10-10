package com.dana.ai

import android.content.Context
import android.content.Intent

/** Resolves only installed launcher activities; no arbitrary components or shell commands. */
object AppLauncher {
    private val known = mapOf(
        "واتساپ" to "com.whatsapp", "واتس اپ" to "com.whatsapp",
        "تلگرام" to "org.telegram.messenger", "کروم" to "com.android.chrome",
        "اینستاگرام" to "com.instagram.android", "یوتیوب" to "com.google.android.youtube",
        "گوگل مپ" to "com.google.android.apps.maps"
    )

    fun resolve(context: Context, requested: String): Intent? {
        val name = CommandEngine.normalize(requested).removePrefix("برنامه ").trim()
        if (name.isBlank() || name.length > 80) return null
        val pm = context.packageManager
        val packageId = known.entries.firstOrNull { name == it.key }?.value
        val knownLaunch = packageId?.let { pm.getLaunchIntentForPackage(it) }
        if (knownLaunch != null) return knownLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = pm.queryIntentActivities(query, 0).filter {
            val label = CommandEngine.normalize(it.loadLabel(pm).toString())
            label == name || label.contains(name) || name.contains(label) && label.length > 2
        }.distinctBy { it.activityInfo.packageName }
        // Never guess between several similar apps.
        if (matches.size != 1) return null
        val activity = matches.single().activityInfo
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(activity.packageName, activity.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
