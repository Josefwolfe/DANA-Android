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
