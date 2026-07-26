package com.xtv.app.core.budget

/**
 * Renders a day-of-month for the "resets on the 26th" clause.
 *
 * Keyed off a `translatable="false"` marker string rather than [java.util.Locale], because the two
 * can disagree: a device set to Dutch resolves the English `values/` strings while its locale still
 * reads `nl`, which would print "resets on the 26". The marker travels with the string set that
 * actually resolved, so the suffix always matches the sentence it lands in.
 *
 * `android.icu.text.MessageFormat` would do this properly with `{0,ordinal}`, but it is an
 * `android.*` class — stubbed to throw in plain JVM unit tests — and this needs to stay testable.
 */
object Ordinals {

    /**
     * @param style the value of `R.string.ordinal_style`: `en` for English ordinals, anything else
     *   for a bare number, where the locale's own suffix lives in the string resource ("26 日").
     */
    fun dayOfMonth(day: Int, style: String): String? {
        if (day !in 1..31) return null
        if (style != EN) return day.toString()
        // 11th, 12th and 13th break the last-digit rule, and are the reason this is not a lookup.
        val suffix = if (day in 11..13) "th" else when (day % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$day$suffix"
    }

    private const val EN = "en"
}
