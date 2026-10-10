> **نسخه 1.9.5:** [اصلاح تشخیص «تلگرام رو اجرا کنم»، فرمان دوربین و گزارش چراغ‌قوه](PERSIAN_COMMAND_FIX_V1_9_5_FA.md).

> **نسخه 1.9.4:** [رفع مشکل «فرمان شنیده می‌شود اما اجرا نمی‌شود» و راهنمای آزمایش](WAKE_DISPATCH_FIX_V1_9_4_FA.md).

> **نسخه 1.9.3:** [راهنمای فعال‌سازی صدای فارسی و آزمایش TTS](PERSIAN_TTS_V1_9_3_FA.md).

> **اصلاح v1.9.1 بیدارباش آفلاین:** [رفع فرمان UNKNOWN و راهنمای آزمایش](WAKEFIX_V1_9_1_FA.md).

> **نسخه 1.9 Offline Diagnostics Beta:** [راهنمای دکمه خطایابی، گزارش کامل و اشتراک‌گذاری](DIAGNOSTICS_V1_9_FA.md).

> **نسخه 1.8 Offline VoiceFix Beta:** [راهنمای ارتقا](UPGRADE_V1_8_VOICEFIX_FA.md) و [رفع اشکال Vosk و حالت خصوصی](docs/VOICE_TROUBLESHOOTING_V1_8_FA.md).

> **نسخه 1.7 Offline Beta:** [راهنمای ارتقای آفلاین و نصب در GitHub](UPGRADE_V1_7_OFFLINE_FA.md).

> **نسخه 1.6 Beta:** راهنمای قابلیت‌های جدید و محدودیت‌ها: [UPGRADE_V1_6_FA.md](UPGRADE_V1_6_FA.md).

> **افزوده‌شده در v1.5 Beta:** برای موسیقی محلی، فرمان‌های وای‌فای/بلوتوث و آلارم تاریخ‌دار، فایل [UPGRADE_V1_5_FA.md](UPGRADE_V1_5_FA.md) را بخوانید.

# DANA Android v1.3 RC4 — از اینجا شروع کن

این بسته سورس آماده Build خارجی است؛ APK داخل ZIP نیست.

## APK آزمایشی روی ویندوز

1. Android Studio را نصب کن و پروژه را باز کن.
2. از SDK Manager، Android 35 و Build Tools 35.0.0 را نصب کن.
3. JDK 17 یا جدیدتر در دسترس باشد.
4. در Terminal پروژه اجرا کن: `scripts\build_apk.bat`
5. خروجی: `app\build\outputs\apk\debug\app-debug.apk`

## APK آزمایشی با GitHub

پروژه را در یک Repository قرار بده و Action با نام **Build DANA installable APK** را اجرا کن. Artifact شامل APK و SHA-256 است.

## APK Release نهایی

راهنمای `docs/RELEASE_BUILD_FA.md` را بخوان. کلید Release به‌صورت امن و خارج از سورس نگهداری می‌شود.

## قبل از Build

`python scripts/preflight.py` وضعیت JDK و Android SDK را بررسی می‌کند. `python scripts/verify_static.py` ساختار و تنظیمات امنیتی را بررسی می‌کند.

## تست روی گوشی

بعد از نصب، `docs/DEVICE_TEST_CHECKLIST_FA.md` را مرحله‌به‌مرحله انجام بده؛ مخصوصاً میکروفون، Wake word، تماس، چراغ‌قوه، ریبوت، مصرف باتری و حالت Doze.
