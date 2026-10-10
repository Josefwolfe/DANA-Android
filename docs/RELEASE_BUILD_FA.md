# ساخت APK نهایی امضاشده دانا — v1.3 RC4

برای تست روی گوشی، `app-debug.apk` کافی است و Android/Gradle آن را با کلید Debug امضا می‌کند. برای نسخه Release باید کلید امضای خودت را بسازی و آن را **خارج از سورس** نگه داری.

## 1) ساخت کلید یک‌بار برای همیشه

در JDK دستور `keytool` موجود است. نمونه:

```bash
keytool -genkeypair -v -keystore dana-release.jks -alias dana -keyalg RSA -keysize 3072 -validity 10000
```

فایل `dana-release.jks` و رمزهای آن را در Git/ZIP قرار نده.

## 2) Build محلی Release

چهار متغیر محیطی زیر را تنظیم کن:

- `DANA_KEYSTORE_PATH`
- `DANA_KEYSTORE_PASSWORD`
- `DANA_KEY_ALIAS`
- `DANA_KEY_PASSWORD`

سپس در Linux/macOS: `./scripts/build_release.sh`

در Windows: `scripts\build_release.bat`

خروجی: `app/build/outputs/apk/release/app-release.apk`

## 3) Build در GitHub Actions

Workflow با نام **Build signed DANA release APK** آماده است. این Secrets را در Repository Settings > Secrets and variables > Actions قرار بده:

- `DANA_RELEASE_KEYSTORE_B64` (محتوای Base64 فایل JKS)
- `DANA_KEYSTORE_PASSWORD`
- `DANA_KEY_ALIAS`
- `DANA_KEY_PASSWORD`

Workflow بعد از Build، امضای APK را با `apksigner verify` بررسی می‌کند و SHA-256 هم تولید می‌کند.

## نکته امنیتی

کلید Release هویت نسخه‌های آینده اپ است. از آن نسخه پشتیبان آفلاین بگیر و هرگز آن را برای شخص دیگری ارسال نکن.
