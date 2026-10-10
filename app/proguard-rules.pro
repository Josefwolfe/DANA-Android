# DANA RC3: shrinking is intentionally disabled for the first device-test release.
# Keep this file so release builds have a stable place for future R8 rules.
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**
