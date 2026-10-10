# گزارش بررسی سورس DANA v1.9.7

- `python3 scripts/verify_static.py`: موفق؛ شامل بررسی مسیر Whisper JNI، مدل و GitHub Actions.
- `python3 -m unittest discover -s gateway/tests -q`: ۱۲ آزمون موفق.
- `kotlinc` روی WhisperNative، WhisperModelInstaller و WhisperMetrics: موفق.
- `kotlinc` روی WhisperCommandRecognizer با stubs API اندروید: موفق (فقط syntax/typecheck محدود؛ نه SDK واقعی).
- `clang++ -fsyntax-only` روی JNI با سربرگ سازگار آزمایشی: موفق (نه با کتابخانه واقعی).
- آزمایش واردکردن مدل ساختگی و حفظ مدل قبلی پس از خطای ورودی: موفق.
- `scripts/preflight.py`: عدم دسترسی به Android SDK و adb؛ پیش‌نیاز ساخت APK در این محیط وجود ندارد.

**انجام‌نشده:** کامپایل Gradle با Android SDK/NDK و whisper.cpp واقعی، تست JNI روی ARM64، تست تأخیر/مصرف باتری و آزمایش عملکرد روی Redmi Note 15. این سورس یک نامزد آزمایشی است، نه نسخه تضمین‌شده.
