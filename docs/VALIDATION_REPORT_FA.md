# گزارش اعتبارسنجی DANA Android 1.3 RC4

تاریخ بسته‌بندی: 2026-10-08

## آزمون‌های قابل اجرا در این محیط
- بررسی XML / Manifest / View IDs / تنظیمات امنیتی: PASS
- آزمون هسته Kotlin: 88/88 PASS
- آزمون ReminderTimeResolver: 6/6 PASS
- رگرسیون Wake / Safety / AI gate / model import: 50/50 PASS
- جمع آزمون‌های Kotlin مستقل: 144/144 PASS
- آزمون Gateway Python: 10/10 PASS
- YAML دو workflow: PASS
- Python syntax: PASS
- اسکن ACTION_CALL / CALL_PHONE / SEND_SMS: PASS (هیچ موردی وجود ندارد)
- اسکن ساده کلید خصوصی و secret تعبیه‌شده: PASS

## مواردی که این گزارش تأیید نمی‌کند
این محیط Android SDK ندارد، بنابراین Gradle Android compile، lint واقعی Android، تولید APK و تست روی گوشی در این محیط انجام نشده است. Workflowهای GitHub Actions برای اجرای همین مراحل در محیط Android آماده‌اند.

## تغییرات ایمنی RC4
- تماس پس از تأیید فقط با ACTION_DIAL آماده می‌شود؛ تماس مستقیم حذف شده است.
- مجوز CALL_PHONE حذف شده است.
- پیامک فقط Draft است و SEND_SMS وجود ندارد.
- NotificationListenerService با exported=false ثبت شده است.
- AI فقط HTTPS و opt-in است و secret داخل APK قرار نمی‌گیرد.
