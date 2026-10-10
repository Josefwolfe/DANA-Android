# DANA Android 1.3 RC4 — راهنمای Build نهایی

این بسته برای ساخت خارج از محیط ChatGPT آماده شده است.

## مسیر پیشنهادی ۱: GitHub Actions
1. محتوای پوشه `DANA-Android` را در ریشه مخزن قرار دهید.
2. Workflow `Build DANA installable APK` را اجرا کنید.
3. CI، JDK 17، Android SDK 35 و Gradle 8.9 را نصب می‌کند و بدون وابستگی به `gradle-wrapper.jar`، تست، lint و `assembleDebug` را اجرا می‌کند.
4. artifact خروجی شامل `app-debug.apk` و SHA-256 است.

## مسیر پیشنهادی ۲: Android Studio / سیستم محلی
پیش‌نیازها: JDK 17+، Android SDK Platform 35 و Build Tools 35.0.0.
- Windows: `scripts\build_apk.bat`
- Linux/macOS: `bash scripts/build_apk.sh`

اسکریپت ابتدا Wrapper موجود را استفاده می‌کند؛ اگر JAR موجود نباشد ولی Gradle نصب باشد از Gradle محلی استفاده می‌کند؛ و در غیر این صورت Wrapper رسمی Gradle 8.9 را با checksum مشخص دریافت می‌کند.

## Release امضاشده
چهار متغیر محیطی زیر را فقط در محیط امن Build تنظیم کنید:
- `DANA_KEYSTORE_PATH`
- `DANA_KEYSTORE_PASSWORD`
- `DANA_KEY_ALIAS`
- `DANA_KEY_PASSWORD`

سپس `scripts/build_release.sh` یا `scripts\build_release.bat` را اجرا کنید.

## نکات امنیتی RC4
- تماس مستقیم (`ACTION_CALL`) حذف شده؛ پس از تأیید کاربر فقط Dialer با شماره آماده باز می‌شود.
- مجوز `CALL_PHONE` حذف شده است.
- پیامک فقط به شکل پیش‌نویس باز می‌شود و ارسال نهایی با کاربر است.
- NotificationListener فقط توسط سیستم bind می‌شود و exported=false است.
- ارتباط AI فقط HTTPS است و کلید داخل APK قرار نمی‌گیرد.
- Wake word هنوز ویژگی آزمایشی است و باید روی گوشی واقعی از نظر مصرف باتری و رفتار صفحه خاموش تست شود.
