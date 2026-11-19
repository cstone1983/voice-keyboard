/*
 * Copyright (C) 2026 Voice Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import dev.patrickgold.florisboard.ime.nlp.latin.EditDiffLearner.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MistakeLearningTest {
    private fun t(s: String) = EditDiffLearner.tokens(s)

    @Test
    fun `a fix a few words back is found`() {
        val before = t("I will see thw dog at the park")
        val after = t("I will see the dog at the park later")
        assertEquals(listOf<Event>(Event.Fixed("thw", "the")), EditDiffLearner.events(before, after, cutStart = false, cutEnd = false))
    }

    @Test
    fun `the last word fixed right away is found too`() {
        val e = EditDiffLearner.events(t("see you tomorow"), t("see you tomorrow"), cutStart = false, cutEnd = false)
        assertEquals(listOf<Event>(Event.Fixed("tomorow", "tomorrow")), e)
    }

    @Test
    fun `typing on, changing your mind and the cut edges teach nothing`() {
        // New words only.
        assertTrue(EditDiffLearner.events(t("one two three"), t("one two three four five"), false, false).isEmpty())
        // A different word, not a fix.
        assertTrue(EditDiffLearner.events(t("I want pizza now"), t("I want tacos now"), false, false).isEmpty())
        // Only the case changed.
        assertTrue(EditDiffLearner.events(t("hi there bob ok"), t("hi there Bob ok"), false, false).isEmpty())
        // At a cut edge of the window.
        assertTrue(EditDiffLearner.events(t("thw dog ran far away"), t("the dog ran far away"), cutStart = true, cutEnd = false).isEmpty())
    }

    @Test
    fun `two words joined by a stray letter or no space are found`() {
        assertEquals(
            listOf<Event>(Event.Split("thisbis", "this", "is", 'b')),
            EditDiffLearner.events(t("well thisbis great"), t("well this is great"), false, false),
        )
        assertEquals(
            listOf<Event>(Event.Split("seeyou", "see", "you", null)),
            EditDiffLearner.events(t("ok seeyou soon"), t("ok see you soon"), false, false),
        )
    }

    @Test
    fun `joined words are split when both halves are words`() {
        val freq = mapOf("this" to 250, "is" to 250, "thi" to 0, "see" to 230, "you" to 250)
        val known = { w: String -> freq[w] ?: 0 }
        assertEquals(Triple("this", "is", 250), EditDiffLearner.splitJoined("thisbis", EditDiffLearner.SPACE_NEIGHBOURS, known))
        assertEquals("see", EditDiffLearner.splitJoined("seeyou", EditDiffLearner.SPACE_NEIGHBOURS, known)?.first)
        assertNull(EditDiffLearner.splitJoined("qzxv", EditDiffLearner.SPACE_NEIGHBOURS, known))
    }

    @Test
    fun `fixes are suggested at once and automatic from the second time`() {
        val m = PersonalFixModel()
        m.noteFix("thw", "the")
        assertEquals(1, m.fixFor("thw")!!.count)
        m.noteFix("thw", "the")
        assertTrue(m.fixFor("Thw")!!.count >= PersonalFixModel.AUTO_AFTER)
        // Changing it back undoes the lesson.
        m.noteFix("the", "thw")
        assertNull(m.fixFor("thw"))
    }

    @Test
    fun `a correction taken back is blocked and its fix dropped`() {
        val m = PersonalFixModel()
        m.noteFix("teh", "the")
        m.noteBlocked("teh", "the")
        assertTrue(m.isBlocked("TEH", "The"))
        assertNull(m.fixFor("teh"))
    }

    @Test
    fun `keys mixed up often read as nearer`() {
        val m = PersonalFixModel()
        assertEquals(1f, m.distanceFactor('b', 'v'))
        repeat(8) { m.noteConfusionsOf("bery", "very") }
        assertTrue(m.distanceFactor('b', 'v') < 0.5f)
        // A rewrite of several letters is not a set of key mix-ups.
        m.noteConfusionsOf("abcd", "wxyz")
        assertEquals(0, m.confusionOf('a', 'w'))
    }

    @Test
    fun `fixes survive a save and load`() {
        val m = PersonalFixModel().apply {
            noteFix("thisbis", "this is"); noteBlocked("ok", "oak"); noteConfusion('b', 'v')
        }
        val n = PersonalFixModel().apply { loadJson(m.toJson()) }
        assertEquals("this is", n.fixFor("thisbis")!!.fix)
        assertTrue(n.isBlocked("ok", "oak"))
        assertEquals(1, n.confusionOf('b', 'v'))
    }

    @Test
    fun `a backspaced slip says which key the tap was meant for`() {
        TouchTrace.reset()
        // "b" tapped at (5.1, 2), taken back, "v" typed: the word is "very".
        fun tap(c: Char, x: Float) { TouchTrace.pendingTapNormalizedForTest(x, 2f); TouchTrace.commit(c.toString()) }
        tap('b', 5.1f); TouchTrace.pop(); tap('v', 4.2f); tap('e', 2.1f); tap('r', 3.1f); tap('y', 5.1f)
        val r = TouchTrace.repairsFor("very")
        assertEquals(1, r.size)
        assertEquals('b', r[0].tapped)
        assertEquals('v', r[0].meant)
        assertEquals(5.1f, r[0].x, 1e-4f)
        TouchTrace.reset()
        assertTrue(TouchTrace.repairsFor("very").isEmpty())
    }

    @Test
    fun `a slip tap moves the key's aim point`() {
        val layout = KeyProximityInfo.Layout(intArrayOf('v'.code, 'b'.code, ' '.code), floatArrayOf(4f, 5f, 5f), floatArrayOf(2f, 2f, 3f))
        val m = TouchHabitModel()
        repeat(4) { m.learnTap('v', 4.4f, 2f, layout, split = false) } // 8 samples
        assertEquals(0.4f, assertNotNull(m.offsetOf('v', false))[0].coerceAtMost(0.4f), 0.06f)
        // The space bar is learned too.
        repeat(4) { m.learnTap(' ', 5f, 2.7f, layout, split = false) }
        assertNotNull(m.offsetOf(' ', false))
    }
}
