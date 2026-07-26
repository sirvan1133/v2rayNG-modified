# v2.8.0

## English

### Stability and compatibility

- Fixed a VPN startup crash on newer Android devices and emulators with 16 KB memory pages.
- Updated the Xray core integration and improved certificate-manager compatibility.
- Reduced background battery usage by pausing UI-only activities when the app leaves the foreground while keeping the VPN connection active.

### Subscription improvements

- Added automatic extraction of the real Marzban subscription username.
- The real username is now shown in the traffic header, subscription tabs, and Subscription Group Settings.
- Removed generic subscription labels and server counts from subscription tabs for a cleaner interface.

### UI and light-theme fixes

- Fixed light-mode contrast, colors, subscription cards, configuration cards, vector map, widgets, menus, and popup presentation.
- Improved the VPN toggle in both themes while preserving the original teal connected color.
- Fixed tool menus leaving the visible screen on smaller displays or near the bottom edge.
- Added compact, responsive Add and configuration menus with borderless left-to-right fade cards.
- Added haptic feedback for the VPN toggle, Add button, configuration menus, and tool actions.

### Added and improved features

- Added a live Persian calendar with daily occasions and official holidays.
- Added adaptive calendar height, red highlighting for official holidays, and a smooth once-per-day drawer scroll hint.
- Improved live direct ping, Auto Connect, map performance and recovery, weather, and market widgets.
- Improved source-IP location caching and foreground/background behavior.

For most modern Android phones, download **arm64-v8a**. Use **universal** only if you are unsure about your device architecture.

## فارسی

### پایداری و سازگاری

- رفع کرش هنگام روشن‌کردن VPN در دستگاه‌ها و امولاتورهای جدید با صفحات حافظه ۱۶ کیلوبایتی.
- به‌روزرسانی هسته Xray و بهبود سازگاری مدیریت گواهی‌ها.
- کاهش مصرف باتری در پس‌زمینه؛ فعالیت‌های رابط کاربری پس از خروج از برنامه متوقف می‌شوند و اتصال VPN روشن باقی می‌ماند.

### بهبود بخش اشتراک

- استخراج خودکار نام کاربری واقعی اشتراک‌های Marzban.
- نمایش نام کاربری واقعی در بالای ترافیک، تب گروه ساب‌ها و تنظیمات Subscription Group.
- حذف عنوان عمومی ساب و تعداد کانفیگ از تب گروه‌ها برای ظاهر تمیزتر.

### اصلاح رابط کاربری و تم سفید

- اصلاح کنتراست و رنگ‌بندی کارت ساب، کارت کانفیگ‌ها، نقشه وکتور، ویجت‌ها، منوها و پنجره‌ها در حالت روشن.
- اصلاح کلید روشن و خاموش VPN در هر دو تم با حفظ رنگ فیروزه‌ای اصلی هنگام اتصال.
- رفع بیرون‌زدگی منوی ابزارها در نمایشگرهای کوچک و کانفیگ‌های نزدیک پایین صفحه.
- اضافه‌شدن منوهای کوچک و واکنش‌گرا برای «+» و سه‌نقطه با کارت‌های بدون کادر و محوشونده از چپ به راست.
- اضافه‌شدن بازخورد لرزشی برای کلید VPN، دکمه «+»، سه‌نقطه کانفیگ‌ها و ابزارها.

### امکانات اضافه‌شده و بهبود‌یافته

- اضافه‌شدن تقویم زنده فارسی با مناسبت‌ها و تعطیلی‌های رسمی.
- تنظیم هوشمند ارتفاع تقویم، نمایش قرمز تعطیلی‌ها و راهنمای اسکرول نرم منوی همبرگری به‌صورت روزانه.
- بهبود پینگ زنده مستقیم، اتصال خودکار، عملکرد و بازیابی نقشه، هواشناسی و قیمت ارز.
- بهبود کش موقعیت IP مبدأ و رفتار برنامه هنگام رفتن به پس‌زمینه و بازگشت.

برای بیشتر گوشی‌های اندرویدی جدید نسخه **arm64-v8a** مناسب است. اگر معماری گوشی را نمی‌دانید، نسخه **universal** را دانلود کنید.
