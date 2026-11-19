/*
 * Copyright (C) 2026 Voice Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.dictate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.ime.nlp.latin.TouchHabitModel
import dev.patrickgold.florisboard.ime.nlp.latin.TouchHabits

/**
 * Voice Keyboard: where this person's taps land on each key, as learned ([TouchHabits]). Each key is
 * drawn at its place; a dot shows where taps for it usually land (centre = right on the key). A dot only
 * appears once a key has enough taps to be used. Split and normal keyboard are learned apart.
 */
@Composable
fun VkTouchMap(modifier: Modifier = Modifier) {
    var split by remember { mutableStateOf(TouchHabits.lastSplit) }
    val layout = remember(split) { TouchHabits.layoutFor(split) }
    val model = remember(split) { TouchHabits.current() }
    val keyColor = MaterialTheme.colorScheme.outline
    val dotColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()

    Column(modifier = modifier) {
        Text("Touch map", style = MaterialTheme.typography.titleSmall)
        Text(
            "Where your taps usually land on each key. A dot right in the middle means you hit the key's " +
                "centre; a dot off to one side is where the keyboard now expects you.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row {
            FilterChip(selected = !split, onClick = { split = false }, label = { Text("Folded") })
            FilterChip(selected = split, onClick = { split = true }, label = { Text("Unfolded (split)") }, modifier = Modifier)
        }
        if (layout == null) {
            Text(
                "Open the keyboard in this layout once to see its map.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        val keys = (0 until layout.size).filter {
            val c = layout.codeAt(it)
            c > 32 && Character.isLetter(c)
        }
        if (keys.isEmpty()) return@Column
        val minX = keys.minOf { layout.xAt(it) } - 0.6f
        val maxX = keys.maxOf { layout.xAt(it) } + 0.6f
        val minY = keys.minOf { layout.yAt(it) } - 0.7f
        val maxY = keys.maxOf { layout.yAt(it) } + 0.7f
        val learned = keys.count { model.offsetOf(layout.codeAt(it).toChar(), split) != null }
        Text("$learned of ${keys.size} keys learned", style = MaterialTheme.typography.labelMedium)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height((180 * (maxY - minY) / ((maxX - minX) / 3.5f)).coerceIn(120f, 320f).dp),
        ) {
            val scale = minOf(size.width / (maxX - minX), size.height / (maxY - minY))
            fun px(x: Float) = (x - minX) * scale
            fun py(y: Float) = (y - minY) * scale
            for (i in keys) {
                val cx = px(layout.xAt(i))
                val cy = py(layout.yAt(i))
                val w = 0.86f * scale
                val h = 1.1f * scale
                drawRoundRect(
                    color = keyColor,
                    topLeft = Offset(cx - w / 2, cy - h / 2),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(0.12f * scale),
                    style = Stroke(width = 1.dp.toPx()),
                )
                val label = layout.codeAt(i).toChar().toString()
                val measured = measurer.measure(label, TextStyle(fontSize = 11.sp, color = textColor))
                drawText(measured, topLeft = Offset(cx - measured.size.width / 2f, cy - h / 2 + 2.dp.toPx()))
                val off = model.offsetOf(layout.codeAt(i).toChar(), split) ?: continue
                val dx = cx + off[0] * scale
                val dy = cy + off[1] * scale
                drawLine(color = dotColor.copy(alpha = 0.5f), start = Offset(cx, cy), end = Offset(dx, dy), strokeWidth = 1.5.dp.toPx())
                drawCircle(color = dotColor, radius = 4.dp.toPx(), center = Offset(dx, dy))
            }
        }
        Text(
            "Shifts are capped at ${(TouchHabitModel.MAX_SHIFT_X * 100).toInt()} % of a key, so one bad " +
                "stretch of typing cannot move a key far.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
