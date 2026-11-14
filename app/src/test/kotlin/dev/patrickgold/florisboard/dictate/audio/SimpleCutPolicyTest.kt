package dev.patrickgold.florisboard.dictate.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimpleCutPolicyTest {
    // 16 kHz, 512-sample windows (32 ms), VAD swallows 0.25 s; 0.5 s pause => 8 silent windows.
    private val w = 512
    private fun policy() = SimpleCutPolicy(
        minSegmentSamples = 3 * 16_000L,
        silenceWindowsNeeded = 8,
        maxSegmentSamples = 15 * 16_000L,
        windowSamples = w,
        vadDelaySamples = 4_000,
        ringWindows = 32,
    )

    /** Feeds windows; returns cut positions. */
    private fun run(p: SimpleCutPolicy, pattern: List<Pair<Boolean, Int>>, energyOf: (Int, Boolean) -> Double = { _, s -> if (s) 1.0 else 0.01 }): List<Long> {
        val cuts = mutableListOf<Long>()
        var end = 0L
        var i = 0
        for ((speech, count) in pattern) repeat(count) {
            end += w
            p.onWindow(speech, energyOf(i++, speech), end)?.let(cuts::add)
        }
        return cuts
    }

    private fun windows(seconds: Double) = (seconds * 16_000 / w).toInt()

    @Test
    fun `cuts in a pause after enough speech, inside the silence`() {
        val cuts = run(policy(), listOf(true to windows(4.0), false to 10))
        assertEquals(1, cuts.size)
        val speechEnd = windows(4.0).toLong() * w
        assertTrue(cuts[0] > speechEnd - 4_000 && cuts[0] < speechEnd + 10L * w, "cut ${cuts[0]} not in pause")
    }

    @Test
    fun `no cut in a pause before the minimum length`() {
        assertTrue(run(policy(), listOf(true to windows(1.5), false to 12, true to 5)).isEmpty())
    }

    @Test
    fun `no cut without speech`() {
        assertTrue(run(policy(), listOf(false to windows(20.0))).isEmpty())
    }

    @Test
    fun `forces a cut at the maximum length at the quietest window of the last second`() {
        val quietIndex = windows(14.5)
        val cuts = run(policy(), listOf(true to windows(16.0))) { i, _ -> if (i == quietIndex) 0.001 else 1.0 }
        assertEquals(1, cuts.size)
        assertEquals((quietIndex + 1).toLong() * w - w / 2, cuts[0])
    }

    @Test
    fun `after a pause cut the next chunk needs fresh speech`() {
        val p = policy()
        val cuts = run(p, listOf(true to windows(4.0), false to windows(10.0)))
        assertEquals(1, cuts.size)
        assertNull(p.onWindow(false, 0.0, (windows(14.0) + 1).toLong() * w))
    }
}
