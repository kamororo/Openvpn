package com.renamer

/** 1.75 (worker C): the in-app version-history screen shows the
 *  user's own feature list — VERBATIM, header + bullets — instead
 *  of per-version changelog entries. No per-version changelog text
 *  lives here or in VersionHistoryActivity anymore; the old 1.74
 *  entries were deleted, not moved.
 *
 *  The list is the user's own words, kept exactly as given
 *  (including «بک برنامه» and «فیلترشکن خای» — do NOT "fix" the
 *  spelling, and do NOT invent an English translation: the same
 *  verbatim Persian list is shown in both languages, so the screen
 *  never shows words the user did not write).
 *
 *  Pure logic (no Android imports), so it is JVM-testable
 *  standalone (see TestMain175C.kt). VersionHistoryActivity
 *  renders HEADER_FA as the entry highlight and one numbered row
 *  per FEATURE_LINES_FA bullet; the version number (1.75 / code
 *  76) is still shown by the activity, as before. */
object VersionHistoryPure {

    const val VERSION = "1.75"
    const val CODE = 76

    const val HEADER_FA = "ویژگی های نسخه جدید :"

    val FEATURE_LINES_FA: List<String> = listOf(
        "•بخش v2ray اضافه شد + امکان دریافت کانفیگ رایگان",
        "•بخش wireguard و amnezia اضافه شد + امکان دریافت کانفیگ رایگان",
        "•بخش cloudflare اضافه شد ( امکان ساخت کانفیگ v2ray شخصی و proxy شخصی )",
        "•بخش اتصال هوشمند اضافه شد ( بک برنامه را برای مثال تلگرام انتخاب میکنید و زمانی که تلگرام را باز بکنید بدون نیاز به باز کردن فیلتر شکن ، فیلترشکن انتخابی شما بصورت خودکار روشن میشود و به محض بستن تلگرام فیلتر شکن خاموش میشود ، این حالت در زمانی کار میکند که فیلتر شکن دیگه ای روشن نباشد ، در نتیجه در فیلترشکن خای دیگر اختلال ایجاد نمیکند )",
        "•بخش اتصال اضطراری اضافه شد ، روی اتصال بزنید و کمی صبر کنید .",
        "•بخش تست سرعت اضافه شد."
    )

    /** The whole feature-list text, header + bullets, one per line. */
    val FEATURE_TEXT_FA: String =
        (listOf(HEADER_FA) + FEATURE_LINES_FA).joinToString("\n")

    /** What the history screen shows for the current version:
     *  the feature list, nothing else. */
    fun screenText(): String = FEATURE_TEXT_FA
}
