# DANA v1.9.5 — گزارش بررسی

- نتیجه بررسی ساختار فایل‌ها، AndroidManifest و XML: PASS (`python3 scripts/verify_static.py`).
- آزمون مستقل Kotlin: 104/104 فرمان پایه + 6/6 محاسبه زمان + 73/73 رگرسیون، با `kotlinc` و Java روی رایانه، بدون Android SDK.
- آزمون‌های پایتون gateway: 12/12 PASS (`python3 -m unittest discover -s gateway/tests -v`).
- بررسی صحت آرشیو ZIP و مقایسه با نسخه 1.9.4: PASS.

**این‌ها به معنی کامپایل موفق APK یا آزمایش روی گوشی نیستند.** SDK اندروید در محیط حاضر موجود نیست. اجرای `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug` باید در GitHub Actions انجام شود و عملکرد واقعی چراغ قوه و بازشدن برنامه‌ها باید روی دستگاه آزمایش شود.

اصلاحات مبتنی بر داده واقعی گزارش 2026-10-10: عبارت Vosk «تلگرام رو اجرا کنم» و متن روی صفحه «دوربین رو روشن کن».
