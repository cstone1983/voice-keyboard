package dev.patrickgold.florisboard.vk.learning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorrectionDiffTest {
    @Test
    fun `a mishear becomes one edit with context`() {
        val e = CorrectionDiff.edits("I will meet Jenny at the cafe tomorrow.", "I will meet Jenni at the cafe tomorrow.")
        assertEquals(listOf(CorrectionEdit("Jenny", "Jenni", "I will meet ___ at the cafe")), e)
    }

    @Test
    fun `two-word replacement`() {
        val e = CorrectionDiff.edits("turn on the pro mox server now please", "turn on the Proxmox server now please")
        assertEquals(1, e.size)
        assertEquals("pro mox", e[0].before)
        assertEquals("Proxmox", e[0].after)
        assertEquals("turn on the ___ server now please", e[0].context)
    }

    @Test
    fun `nothing changed or only case and punctuation`() {
        assertTrue(CorrectionDiff.edits("hello there friend", "hello there friend").isEmpty())
        assertTrue(CorrectionDiff.edits("hello there friend", "Hello there, friend.").isEmpty())
    }

    @Test
    fun `a rewrite is ignored`() {
        assertTrue(CorrectionDiff.edits("see you at five tonight", "can we do tomorrow instead").isEmpty())
    }

    @Test
    fun `typing on after the dictation is not a correction`() {
        assertTrue(CorrectionDiff.edits("on my way", "on my way, see you soon").isEmpty())
    }

    @Test
    fun `deletion inside is reported`() {
        val e = CorrectionDiff.edits("so um I think we should go", "so I think we should go")
        assertEquals(listOf(CorrectionEdit("um", "", "so ___ I think we")), e)
    }
}
