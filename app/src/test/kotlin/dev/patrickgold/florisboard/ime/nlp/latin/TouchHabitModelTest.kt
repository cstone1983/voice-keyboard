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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TouchHabitModelTest {
    // One row "v b n", keys one width apart, centres at x = 0, 1, 2.
    private val layout = KeyProximityInfo.Layout(
        intArrayOf('v'.code, 'b'.code, 'n'.code),
        floatArrayOf(0f, 1f, 2f),
        floatArrayOf(0f, 0f, 0f),
    )
    private val chars = charArrayOf('v', 'b', 'n')
    private val centers = floatArrayOf(0f, 0f, 1f, 0f, 2f, 0f)

    private fun TouchHabitModel.teachV(dx: Float, times: Int) =
        repeat(times) { learn("vv", floatArrayOf(dx, 0f, dx, 0f), layout, split = true) }

    @Test
    fun `an offset is used only after enough samples`() {
        val m = TouchHabitModel()
        m.teachV(0.3f, 3) // 6 samples
        assertNull(m.offsetOf('v', split = true))
        m.teachV(0.3f, 1) // 8
        assertEquals(0.3f, assertNotNull(m.offsetOf('v', split = true))[0], 1e-4f)
    }

    @Test
    fun `a touch just over the edge is read as the key this person aims for`() {
        val m = TouchHabitModel()
        m.teachV(0.35f, 10)
        // 0.55 is inside "b" (hit index 1), but this person's "v" lands at 0.35: nearer.
        assertEquals(0, m.pick(0.55f, 0f, chars, centers, hit = 1, split = true))
        // Well inside "b": stays "b".
        assertEquals(-1, m.pick(0.95f, 0f, chars, centers, hit = 1, split = true))
    }

    @Test
    fun `split and normal keyboards are learned apart`() {
        val m = TouchHabitModel()
        m.teachV(0.35f, 10)
        assertNull(m.offsetOf('v', split = false))
        assertEquals(-1, m.pick(0.55f, 0f, chars, centers, hit = 1, split = false))
    }

    @Test
    fun `offsets are capped and far taps ignored`() {
        val m = TouchHabitModel()
        repeat(10) { m.learn("vv", floatArrayOf(0.9f, 0f, 0.9f, 0f), layout, split = true) }
        assertEquals(TouchHabitModel.MAX_SHIFT_X, assertNotNull(m.offsetOf('v', true))[0], 1e-4f)
        val far = TouchHabitModel()
        repeat(10) { far.learn("vv", floatArrayOf(1.9f, 0f, 1.9f, 0f), layout, split = true) }
        assertNull(far.offsetOf('v', true))
    }

    @Test
    fun `json round trip and merge keeps the key with more samples`() {
        val a = TouchHabitModel().apply { teachV(0.2f, 10) }
        val b = TouchHabitModel().apply { teachV(-0.1f, 5); loadJson(toJson()) }
        b.merge(a.toJson())
        assertEquals(0.2f, assertNotNull(b.offsetOf('v', true))[0], 1e-3f)
    }
}
