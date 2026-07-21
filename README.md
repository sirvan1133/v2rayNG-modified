# v2rayNG Modified

اولین ویرایش v2ray با نوار مصرف ترافیک و نقشهٔ زندهٔ سینمایی VPN

نسخهٔ شخصی‌سازی‌شدهٔ v2rayNG با قابلیت‌های اضافه، از جمله نمایش زندهٔ نقشه و موقعیت اتصال VPN

Personalized v2rayNG with additional features

## Screenshots

| Light theme | Dark theme |
| --- | --- |
| <img src="screenshot-light.png" alt="Cinematic VPN world map — light theme" width="280"> | <img src="screenshot-dark.png" alt="Cinematic VPN world map — dark theme" width="280"> |

---

## تغییرات / Changes

### v2.6.0 — Weather overlay & IP location

- نمایش آب‌وهوای زنده به صورت overlay روی نقشه جهان (آسمان، خورشید، ابر، باران، برف، مه، رعد و برق)
- دمای هوا در سمت راست صفحه با نمایش کم‌رنگ و شیشه‌ای
- انیمیشن اسلاید: هنگام اتصال VPN آب‌وهوا از چپ وارد می‌شود، هنگام قطع به راست خارج می‌شود
- موقعیت آب‌وهوا بر اساس IP (بدون نیاز به GPS)
- تقویم شمسی ۵ روزه با نمایش وضعیت آب‌وهوا
- کارت شیشه‌ای اطلاعات آب‌وهوا (رطوبت، باد، دید)
- رفع باگ: دانلود خودکار فایل geoip-only-cn-private.dat در صورت نبود
- مخفی شدن خودکار لیست کانفیگ هنگام اتصال VPN
- افکت شیشه‌ای برای سرور انتخاب شده

### v2.6.0 — Weather overlay & IP location

- Live weather overlay on the world map (sky, sun, clouds, rain, snow, fog, thunder)
- Temperature displayed on the right side with dimmer glassmorphic style
- Slide animation: weather slides in from left when VPN connects, slides out to right when disconnected
- Weather location based on IP (no GPS required)
- 5-day Persian calendar with weather forecast
- Glass metrics card (humidity, wind, visibility)
- Auto-download geoip-only-cn-private.dat if missing
- Auto-hide server list on VPN connect
- Glassmorphic selected server effect

### v2.5.7 — Smooth map rendering

- حذف رندر هم‌زمان چند کش بزرگ نقشه در زمان حرکت
- بهینه‌سازی دنبالهٔ Marker و کاهش ایجاد Shader در هر فریم برای حرکت روان‌تر

### v2.5.7 — Smooth map rendering

- Removed multi-texture map compositing while moving
- Optimized the marker trail to reduce per-frame shader creation and improve smoothness

### v2.5.6 — Cinematic VPN World Map

- نقشهٔ زندهٔ جهان بر پایهٔ داده‌های واقعی کشورها، نه تصویر پس‌زمینهٔ ثابت
- تشخیص کشور IP عمومی در حالت قطع اتصال و نمایش کشور سرور در حالت اتصال
- نشانگر زنده با پرچم و نام کشور، پالس، حلقه‌های نورانی و دنبالهٔ داده
- حرکت نرم دوربین: ابتدا نقشه به مقصد می‌رود و سپس نشانگر اتصال حرکت می‌کند
- زوم تطبیقی 3× با کش چندسطحی برای حفظ کیفیت نقشه و جلوگیری از پرش یا بارگذاری دوباره
- بهینه‌سازی رندر: لایهٔ ثابت نقشه کش می‌شود و فقط لایه‌های انیمیشنی در هر فریم به‌روزرسانی می‌شوند

### v2.5.6 — Cinematic VPN World Map

- Live world map based on real country geometry, not a static background image
- Public-IP country while disconnected; selected server country while connected
- Live endpoint with country flag/name, pulse, radar rings, glow, and a data trail
- Cinematic motion: the camera travels first, then the connection node follows
- Adaptive 3× zoom with multi-level map caching for crisp detail without reload flashes
- Cached static map layer; only animated effects redraw per frame for smoother performance


- سه دکمه در نوار بالا: بروزرسانی، پینگ، افزودن از کلیپ‌بورد
- حذف اشتراک پیش‌فرض هنگام نصب اولیه
- دکمه گزارش باگ در منوی کشویی
- آپدیت خودکار اشتراک‌ها هنگام باز شدن برنامه
- نمایش نوار مصرف ترافیک در بالای صفحه (مخصوص هر گروه/ساب)
- نمایش درصد مصرف، حجم مصرف شده، حجم باقی مانده، کل ترافیک و روزهای باقی مانده
- بررسی خودکار آپدیت روزانه از گیت‌هاب


- Three buttons in toolbar: Update Subscription, Ping All, Add from Clipboard
- Removed default subscription on fresh install
- Bug Report button in drawer menu
- Auto update subscriptions on app open
- Traffic usage bar at the top (per group/subscription)
- Show usage percent, used, remaining, total traffic and days left
- Auto daily update check from GitHub

---

## دریافت / Download

به بخش [Releases](https://github.com/sirvan1133/v2rayNG-modified/releases) مراجعه کنید.

See [Releases](https://github.com/sirvan1133/v2rayNG-modified/releases) for downloads.

### راهنمای انتخاب فایل APK مناسب

| فایل | توضیحات |
| --- | --- |
| `v2rayNG_X.Y.Z_arm64-v8a.apk` | **گوشی‌های جدید (۲۰۱۷ به بعد)** — سامسونگ S8 به بعد، شیائومی MI 6 به بعد، وان‌پلاس ۵ به بعد، گوگل پیکسل ۲ به بعد، هواوی P20 به بعد، تمام گوشی‌های میان‌رده و پرچمدار جدید |
| `v2rayNG_X.Y.Z_armeabi-v7a.apk` | **گوشی‌های قدیمی (۲۰۱۱ تا ۲۰۱۶)** — سامسونگ S3 تا S7، شیائومی Redmi Note 3/4، هواوی P10 و قدیمی‌تر، گوشی‌های بسیار ارزان قیمت |
| `v2rayNG_X.Y.Z_x86_64.apk` | **تبلت‌ها و شبیه‌سازهای ۶۴ بیتی** — شبیه‌سازهای اندروید روی کامپیوتر (BlueStacks 5, Nox, LDPlayer) |
| `v2rayNG_X.Y.Z_x86.apk` | **تبلت‌ها و شبیه‌سازهای ۳۲ بیتی** — شبیه‌سازهای قدیمی (BlueStacks 4 و پایین‌تر) |
| `v2rayNG_X.Y.Z-fdroid_arm64-v8a.apk` | نسخه F-Droid برای گوشی‌های جدید (arm64-v8a) |
| `v2rayNG_X.Y.Z-fdroid_armeabi-v7a.apk` | نسخه F-Droid برای گوشی‌های قدیمی (armeabi-v7a) |

> **نکته:** اگر مطمئن نیستید کدام نسخه را دانلود کنید، `arm64-v8a` را انتخاب کنید. اکثر گوشی‌های ۲۰۱۷ به بعد از این معماری پشتیبانی می‌کنند.

---

## حمایت / Donate

**USDT (BEP20):** `0xAab8aE16283C399e188328b9b06cECCAd47FABDe`

**TRX:** `0xAab8aE16283C399e188328b9b06cECCAd47FABDe`

---

## Source

https://github.com/sirvan1133/v2rayNG-modified

