package com.xtv.app.ui.notice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.xtv.app.R
import com.xtv.app.ui.common.rememberInitialFocus
import com.xtv.app.ui.theme.XtvPalette
import com.xtv.app.ui.theme.XtvSpacing
import com.xtv.app.ui.theme.XtvText
import kotlinx.coroutines.delay

/** Long enough to read twice from a sofa, short enough that it is gone before it becomes furniture. */
private const val BRIEF_MS = 6_000L

@Composable
private fun NoticeText.resolve(): String = when (this) {
    is NoticeText.Plain -> stringResource(id)
    is NoticeText.Quantity -> pluralStringResource(id, count, count)
    is NoticeText.Formatted -> stringResource(id, arg)
}

/**
 * The quiet two weights, drawn as a single line under whatever they are about.
 *
 * A [NoticeWeight.BRIEF] notice removes itself; a [NoticeWeight.STANDING] one does not, and earns a
 * dot in the warning colour so the difference is visible from across a room rather than only from
 * how long it has been there.
 */
@Composable
fun InlineNotice(
    notice: Notice,
    onExpire: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notice.weight == NoticeWeight.BRIEF) {
        LaunchedEffect(notice) {
            delay(BRIEF_MS)
            onExpire()
        }
    }
    val standing = notice.weight == NoticeWeight.STANDING
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (standing) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(XtvPalette.Warning, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            notice.text.resolve(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (standing) XtvText.Secondary else XtvText.Tertiary,
        )
    }
}

/**
 * The loud weight.
 *
 * Reserved for the two outcomes where the viewer is out of pocket with nothing to play: a batch
 * that carried no video, and a request whose billing could not be determined. Both used to be told
 * in the same grey footnote as "try again shortly", which is the single worst piece of information
 * hierarchy in the app — the one message that costs money looked exactly like the one that costs
 * nothing.
 */
@Composable
fun LoudNotice(
    notice: Notice,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = rememberInitialFocus(notice)
    Surface(
        modifier = modifier.widthIn(max = XtvSpacing.DialogMaxWidth),
        shape = MaterialTheme.shapes.medium,
        colors = SurfaceDefaults.colors(
            containerColor = XtvPalette.Surface,
            contentColor = XtvText.Primary,
        ),
        border = Border(
            border = BorderStroke(1.dp, XtvPalette.Warning),
            shape = MaterialTheme.shapes.medium,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(XtvSpacing.CardPadH)) {
            notice.title?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.titleMedium,
                    color = XtvPalette.Warning,
                )
                Spacer(Modifier.height(XtvSpacing.Heading))
            }
            Text(
                notice.text.resolve(),
                style = MaterialTheme.typography.bodyLarge,
                color = XtvText.Secondary,
            )
            Spacer(Modifier.height(XtvSpacing.Row))
            Row(horizontalArrangement = Arrangement.spacedBy(XtvSpacing.Gap)) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(focus),
                ) {
                    Text(stringResource(R.string.notice_dismiss))
                }
            }
        }
    }
}
