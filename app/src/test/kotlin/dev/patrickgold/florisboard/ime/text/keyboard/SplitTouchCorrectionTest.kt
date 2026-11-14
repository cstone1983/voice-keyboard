package dev.patrickgold.florisboard.ime.text.keyboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitTouchCorrectionTest {
    // 800 wide, halves 300, gap 200; three letter rows of 100 (300 high), spread 15 %, lift 30 %.
    private fun adj(x: Float, y: Float) = SplitTouchCorrection.adjust(x, y, 800f, 300f, 200f, 300f, 100f, 0.15f, 0.30f)

    @Test
    fun `left half spreads outward from its centre, no lift`() {
        val (x, y) = adj(250f, 50f) // near the inner top corner of the left half (T)
        assertEquals(150f + 100f * 1.15f, x, 0.01f)
        assertEquals(150f + (50f - 150f) * 1.15f, y, 0.01f)
    }

    @Test
    fun `right half spreads and lifts`() {
        val (x, y) = adj(560f, 160f)
        assertEquals(650f + (560f - 650f) * 1.15f, x, 0.01f)
        assertEquals(150f + 10f * 1.15f - 30f, y, 0.01f)
    }

    @Test
    fun `never crosses the gap or leaves the top`() {
        val (lx, _) = adj(299f, 150f)
        assertTrue(lx < 300f)
        val (rx, _) = adj(501f, 150f)
        assertTrue(rx > 500f)
        val (_, ty) = adj(700f, 0f)
        assertTrue(ty >= 0f)
    }
}
