# دروازه انتشار و وضعیت دقیق آزمون

**پروژه:** DANA Android v1.3 RC4

| الزام | وضعیت تا هنگام بسته‌بندی |
|---|---|
| بررسی Manifest و XML | PASS |
| اعتبار ارجاع IDها و کلاس‌ها | PASS |
| آزمون موتور فرمان Kotlin / time / safety / alias consent | ۱۴۴ PASS |
| آزمون نمونه سرور AI | ۱۰ PASS |
| بررسی امنیت مسیر ZIP مدل Vosk | PASS در آزمون مستقل |
| مطابقت SHA-256 نسخه Gradle 8.9 با سایت رسمی | بررسی مقدار پین‌شده، دانلود محلی در دسترس نبود |
| Gradle `assembleDebug` / `lintDebug` / `assembleRelease` | NOT RUN؛ Android SDK این محیط وجود ندارد |
| Gradle JUnit Android-dependent | NOT RUN |
| مسیر امضای Release | READY IN SOURCE؛ اجرای واقعی به keystore شخصی و Android SDK نیاز دارد |
| نصب و تست روی گوشی اندروید | NOT RUN |
| زمان پاسخ، دقت گفتار، و مصرف باتری | NOT MEASURED |

معیار قبولی نهایی: اجرای موفق GitHub Action یا ساخت محلی، نصب روی گوشی، عبور از چک‌لیست دستگاه، تأیید مجوزها و تست تعامل‌های حساس. بیدارباش و AI آنلاین در این مرحله اختیاری/آزمایشی باقی می‌مانند.
