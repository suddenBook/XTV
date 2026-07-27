package com.xtv.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The one place a colour, a gap or a text level is decided.
 *
 * Before this existed the app ran two independent colour systems on the same screen: `Card` and
 * `Button` took their container, border and focus colours from an unmodified `darkColorScheme()`,
 * while every background and every piece of text used a literal hex spread across five files —
 * twenty of them, plus eleven different alpha values standing in for a text hierarchy. Nothing was
 * ever aligned to anything, which is what "the cards look stuck onto the background" actually was.
 *
 * [XtvPalette] feeds the Material slots in [XtvTheme]; the components then dress themselves. What
 * stays here is only what Material has no slot for.
 */
internal object XtvPalette {

    /** Behind everything. Slightly cool rather than pure black, which reads as depth on an OLED. */
    val Background = Color(0xFF0B0B0D)

    /** Raised planes that are not interactive: dialog bodies, inline notices. */
    val Surface = Color(0xFF141417)

    /** What `Card` actually paints itself with. Must sit visibly above [Surface]. */
    val SurfaceVariant = Color(0xFF1C1C21)

    /** Focus and separation. `Card` reads this for its border. */
    val Border = Color(0xFF33333B)
    val BorderStrong = Color(0xFF4D4D57)

    /** X blue. One meaning only: "this is the thing you are choosing". */
    val Accent = Color(0xFF1D9BF0)
    val OnAccent = Color(0xFF04121C)

    /** Money, budget, and anything the user should slow down for — never for plain emphasis. */
    val Warning = Color(0xFFFFB74D)
    val OnWarning = Color(0xFF241800)

    /** Irreversible actions only. */
    val Danger = Color(0xFFFF6B6B)
    val OnDanger = Color(0xFF2B0708)

    val Scrim = Color(0xFF000000)
}

/**
 * Four text levels, and only four.
 *
 * These are opaque colours rather than alphas over white. An alpha composites against whatever is
 * behind it, so the same `0.7f` looked like three different greys on the background, on a card and
 * on the player's gradient — which is how eleven of them accumulated.
 */
internal object XtvText {
    /** Titles and the number the user came to read. */
    val Primary = Color(0xFFF2F3F5)

    /** Supporting line under a title. */
    val Secondary = Color(0xFFA9ADB5)

    /** Metadata that must not compete: counts, timestamps, footnotes. */
    val Tertiary = Color(0xFF74787F)

    /** Present but unavailable. Rare by design — an unusable control is normally not drawn at all. */
    val Disabled = Color(0xFF4A4D54)
}

/**
 * Spacing and rhythm.
 *
 * Widths are deliberately absent. Rows lay their children out with `weight(1f)`, so a row of three
 * always spans exactly the same measure as the hero above it. The previous hard-coded 680 / 240+300
 * / 240×3 could not line up at any screen size, and produced three different ragged right edges.
 */
internal object XtvSpacing {
    /** TV overscan-safe inset. Content outside this can be cropped by the panel. */
    val ScreenH = 48.dp
    val ScreenV = 27.dp

    /** Between siblings in a row. */
    val Gap = 20.dp

    /** Between stacked blocks. */
    val Row = 16.dp

    /** Between a section heading and its content. */
    val Heading = 12.dp

    /** Between a major section and the next. */
    val Section = 28.dp

    val CardPadH = 24.dp
    val CardPadV = 20.dp

    /**
     * One height for every full-weight card on the home screen.
     *
     * The resume bar and the offer cards are the same kind of thing — a press that starts watching
     * — so they are the same size. Letting each size itself to its own text made the bar noticeably
     * shorter than the row beneath it, which read as two unrelated components rather than one set of
     * choices. Content is centred inside, not laid out from the top, so the shared height never
     * clips a line.
     */
    val CardHeight = 112.dp

    /** Secondary entries: same grid, deliberately shallower, so the row reads as lighter. */
    val CompactPadH = 20.dp
    val CompactPadV = 12.dp

    val DialogPad = 28.dp
    val DialogMaxWidth = 620.dp
}
