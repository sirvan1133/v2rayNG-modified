package com.v2ray.ang.weather

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

/**
 * Jalali (Shamsi) and Hijri (Qamari) calendar support for the weather scene.
 *
 * Jalali conversion uses the well-known arithmetic algorithm (jalaali-js
 * lineage).  The lunar date prefers the platform Umm-al-Qura implementation
 * (HijrahDate, available through core-library desugaring) and falls back to
 * the tabular Kuwaiti algorithm, so a ±1 day drift versus moon sighting is
 * possible — normal for computed lunar calendars.
 */
object PersianCalendarHelper {

    data class PersianDayInfo(
        val jalaliYear: Int, val jalaliMonth: Int, val jalaliDay: Int,
        val monthName: String,
        val weekdayFull: String, val weekdayShort: String,
        val hijriYear: Int, val hijriMonth: Int, val hijriDay: Int,
        val hijriMonthName: String,
        val occasion: String?, val isHoliday: Boolean, val isFriday: Boolean
    )

    private val monthNames = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )
    private val hijriMonthNames = arrayOf(
        "محرم", "صفر", "ربیع‌الاول", "ربیع‌الثانی", "جمادی‌الاول", "جمادی‌الثانی",
        "رجب", "شعبان", "رمضان", "شوال", "ذی‌القعده", "ذی‌الحجه"
    )

    private data class Occasion(val title: String, val holiday: Boolean)

    /** Fixed Shamsi occasions, keyed by (month, day). */
    private val shamsiOccasions = mapOf(
        (1 to 1) to Occasion("جشن نوروز", true),
        (1 to 2) to Occasion("عید نوروز", true),
        (1 to 3) to Occasion("عید نوروز", true),
        (1 to 4) to Occasion("عید نوروز", true),
        (1 to 12) to Occasion("روز جمهوری اسلامی", true),
        (1 to 13) to Occasion("روز طبیعت", true),
        (2 to 10) to Occasion("روز ملی خلیج فارس", false),
        (3 to 14) to Occasion("رحلت امام خمینی", true),
        (3 to 15) to Occasion("قیام ۱۵ خرداد", true),
        (5 to 17) to Occasion("روز خبرنگار", false),
        (6 to 1) to Occasion("روز پزشک", false),
        (7 to 7) to Occasion("روز آتش‌نشانی", false),
        (7 to 13) to Occasion("روز نیروی انتظامی", false),
        (8 to 13) to Occasion("روز دانش‌آموز", false),
        (9 to 16) to Occasion("روز دانشجو", false),
        (9 to 30) to Occasion("شب یلدا", false),
        (11 to 22) to Occasion("پیروزی انقلاب اسلامی", true),
        (12 to 29) to Occasion("ملی شدن صنعت نفت", true)
    )

    /** Fixed Qamari occasions, keyed by (month, day). */
    private val qamariOccasions = mapOf(
        (1 to 9) to Occasion("تاسوعای حسینی", true),
        (1 to 10) to Occasion("عاشورای حسینی", true),
        (2 to 20) to Occasion("اربعین حسینی", true),
        (2 to 28) to Occasion("رحلت پیامبر اکرم", true),
        (2 to 30) to Occasion("شهادت امام رضا", true),
        (3 to 8) to Occasion("شهادت امام حسن عسکری", true),
        (3 to 17) to Occasion("میلاد پیامبر اکرم", true),
        (6 to 3) to Occasion("شهادت حضرت زهرا", true),
        (7 to 13) to Occasion("ولادت امام علی", true),
        (7 to 27) to Occasion("مبعث پیامبر اکرم", true),
        (8 to 15) to Occasion("نیمه شعبان", true),
        (9 to 21) to Occasion("شهادت امام علی", true),
        (10 to 1) to Occasion("عید سعید فطر", true),
        (10 to 2) to Occasion("تعطیل عید فطر", true),
        (10 to 25) to Occasion("شهادت امام صادق", true),
        (12 to 10) to Occasion("عید سعید قربان", true),
        (12 to 18) to Occasion("عید سعید غدیر خم", true)
    )

    fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

    /** Today plus the following days, in order. */
    fun upcomingDays(count: Int = 5): List<PersianDayInfo> {
        val today = LocalDate.now()
        return (0 until count).map { dayInfo(today.plusDays(it.toLong())) }
    }

    private fun dayInfo(date: LocalDate): PersianDayInfo {
        val j = gregorianToJalali(date.year, date.monthValue, date.dayOfMonth)
        val h = islamicFromGregorian(date)
        val shamsi = shamsiOccasions[j[1] to j[2]]
        val qamari = qamariOccasions[h[1] to h[2]]
        return PersianDayInfo(
            jalaliYear = j[0], jalaliMonth = j[1], jalaliDay = j[2],
            monthName = monthNames[(j[1] - 1).coerceIn(0, 11)],
            weekdayFull = weekdayFull(date.dayOfWeek),
            weekdayShort = weekdayShort(date.dayOfWeek),
            hijriYear = h[0], hijriMonth = h[1], hijriDay = h[2],
            hijriMonthName = hijriMonthNames[(h[1] - 1).coerceIn(0, 11)],
            occasion = shamsi?.title ?: qamari?.title,
            isHoliday = (shamsi?.holiday == true) || (qamari?.holiday == true),
            isFriday = date.dayOfWeek == DayOfWeek.FRIDAY
        )
    }

    private fun weekdayFull(day: DayOfWeek): String = when (day) {
        DayOfWeek.SATURDAY -> "شنبه"
        DayOfWeek.SUNDAY -> "یکشنبه"
        DayOfWeek.MONDAY -> "دوشنبه"
        DayOfWeek.TUESDAY -> "سه‌شنبه"
        DayOfWeek.WEDNESDAY -> "چهارشنبه"
        DayOfWeek.THURSDAY -> "پنجشنبه"
        DayOfWeek.FRIDAY -> "جمعه"
    }

    private fun weekdayShort(day: DayOfWeek): String = when (day) {
        DayOfWeek.SATURDAY -> "ش"
        DayOfWeek.SUNDAY -> "ی"
        DayOfWeek.MONDAY -> "د"
        DayOfWeek.TUESDAY -> "س"
        DayOfWeek.WEDNESDAY -> "چ"
        DayOfWeek.THURSDAY -> "پ"
        DayOfWeek.FRIDAY -> "ج"
    }

    fun toPersianDigits(value: Int): String = toPersianDigits(value.toString())

    fun toPersianDigits(value: String): String {
        val fa = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        return buildString { for (c in value) append(if (c in '0'..'9') fa[c - '0'] else c) }
    }

    /** Arithmetic Gregorian→Jalali conversion; returns [year, month, day]. */
    private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): IntArray {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + gdm[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return intArrayOf(jy, jm, jd)
    }

    /** Umm-al-Qura when the platform provides it, else the tabular algorithm. */
    private fun islamicFromGregorian(date: LocalDate): IntArray = try {
        val h = HijrahDate.from(date)
        intArrayOf(h.get(ChronoField.YEAR), h.get(ChronoField.MONTH_OF_YEAR), h.get(ChronoField.DAY_OF_MONTH))
    } catch (_: Throwable) {
        islamicFromJdn(gregorianToJdn(date.year, date.monthValue, date.dayOfMonth))
    }

    private fun gregorianToJdn(y: Int, m: Int, d: Int): Int =
        (1461 * (y + 4800 + (m - 14) / 12)) / 4 +
                (367 * (m - 2 - 12 * ((m - 14) / 12))) / 12 -
                (3 * ((y + 4900 + (m - 14) / 12) / 100)) / 4 + d - 32075

    private fun islamicFromJdn(jdn: Int): IntArray {
        var l = jdn - 1948440 + 10632
        val n = (l - 1) / 10631
        l = l - 10631 * n + 354
        val j = ((10985 - l) / 5316) * ((50 * l) / 17719) + (l / 5670) * ((43 * l) / 15238)
        l = l - ((30 - j) / 15) * ((17719 * j) / 50) - (j / 16) * ((15238 * j) / 43) + 29
        val m = (24 * l) / 709
        val d = l - (709 * m) / 24
        val y = 30 * n + j - 30
        return intArrayOf(y, m, d)
    }
}
