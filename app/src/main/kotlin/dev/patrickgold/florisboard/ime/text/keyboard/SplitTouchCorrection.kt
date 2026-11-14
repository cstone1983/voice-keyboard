/*
 * Copyright (C) 2026 Voice Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.text.keyboard

/**
 * Voice Keyboard: where a touch on a split keyboard was most likely aimed.
 *
 * Measured on the user's Fold (typing test, 2026-11-14): on each half the thumbs land pulled toward that
 * half's centre — far keys fall short (r→e, a→s, p→o, b→n) — and on the right half about a row low
 * (o→k, h→n). So the touch is spread outward from the half's centre by [spread] (0.15 = 15 %), and on the
 * right half lifted by [liftRight] row heights. Only the letter rows take part; the caller keeps the
 * original key when the corrected point lands on no letter or digit.
 */
object SplitTouchCorrection {
    /**
     * @param halfWidth width of one half; [gap] the space between the halves; [width] the whole keyboard.
     * @param letterRowsHeight height of the rows above the bottom (space) row; [rowPitch] one row's height.
     */
    fun adjust(
        x: Float,
        y: Float,
        width: Float,
        halfWidth: Float,
        gap: Float,
        letterRowsHeight: Float,
        rowPitch: Float,
        spread: Float,
        liftRight: Float,
    ): Pair<Float, Float> {
        val left = x < halfWidth + gap / 2f
        val cx = if (left) halfWidth / 2f else width - halfWidth / 2f
        val cy = letterRowsHeight / 2f
        var nx = cx + (x - cx) * (1f + spread)
        var ny = cy + (y - cy) * (1f + spread)
        if (!left) ny -= liftRight * rowPitch
        // Never across the gap, never above the keyboard.
        nx = if (left) nx.coerceIn(0f, halfWidth - 1f) else nx.coerceIn(halfWidth + gap + 1f, width)
        ny = ny.coerceAtLeast(0f)
        return nx to ny
    }
}
