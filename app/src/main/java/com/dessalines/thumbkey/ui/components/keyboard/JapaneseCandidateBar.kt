package com.dessalines.thumbkey.ui.components.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dessalines.thumbkey.IMEService
import com.dessalines.thumbkey.textprocessors.JapaneseTextProcessor

private val BAR_HEIGHT = 44.dp

/**
 * Candidate bar for the Japanese layouts.
 *
 * While typing it lists whole-reading suggestions (the best conversion first); tap one to commit
 * it. After space it shows the 文節 being converted, with the focused one highlighted, that 文節's
 * candidates, and buttons to move between 文節 (◀ ▶) and shorten or lengthen the focused one (短 長).
 */
@Composable
fun JapaneseCandidateBar(
    processor: JapaneseTextProcessor,
    ime: IMEService,
    width: Dp,
    keyPadding: Int,
    skk: Boolean,
) {
    LaunchedEffect(Unit) { processor.warmUp(ime) }
    LaunchedEffect(skk) { processor.setSkk(skk) }

    val state = processor.bar
    val listState = rememberLazyListState()
    LaunchedEffect(state.version) {
        if (state.selected >= 0) {
            listState.animateScrollToItem(state.selected)
        } else {
            listState.scrollToItem(0)
        }
    }

    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    Row(
        modifier =
            Modifier
                .padding(horizontal = keyPadding.dp, vertical = 2.dp)
                .width(width - (keyPadding * 2).dp)
                .height(BAR_HEIGHT)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.status.isNotEmpty()) {
            Text(
                text = state.status,
                color = primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        if (state.converting) {
            Text(
                text =
                    buildAnnotatedString {
                        state.preview.forEachIndexed { i, t ->
                            if (i == state.focus) {
                                withStyle(
                                    SpanStyle(
                                        color = primary,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                ) { append(t) }
                            } else {
                                append(t)
                            }
                        }
                    },
                color = onSurface,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = width * 0.35f).padding(horizontal = 8.dp),
            )
            Box(
                modifier =
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight(0.6f)
                        .background(MaterialTheme.colorScheme.outline),
            )
        }

        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(state.candidates) { index, candidate ->
                val selected = index == state.selected
                Text(
                    text = candidate,
                    color = if (selected) primary else onSurface,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 17.sp,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .clickable { processor.onCandidateTapped(ime, index) }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                )
            }
        }

        if (state.converting) {
            BarButton("◀") { processor.onMoveFocus(ime, -1) }
            BarButton("▶") { processor.onMoveFocus(ime, 1) }
            BarButton("短") { processor.onResize(ime, -1) }
            BarButton("長") { processor.onResize(ime, 1) }
        }
    }
}

@Composable
private fun BarButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxHeight()
                .width(34.dp)
                .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
