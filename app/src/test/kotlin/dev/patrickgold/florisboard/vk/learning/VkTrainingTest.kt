package dev.patrickgold.florisboard.vk.learning

import dev.patrickgold.florisboard.vk.training.VkTraining
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VkTrainingTest {
    @Test
    fun `perfect transcription scores zero`() {
        val (wer, missed) = VkTraining.score("Turn off the living room lights.", "turn off the living room lights")
        assertEquals(0.0, wer)
        assertTrue(missed.isEmpty())
    }

    @Test
    fun `a mishear is one error and marks the word`() {
        val (wer, missed) = VkTraining.score("Can you ask Jenni to call me", "Can you ask Jenny to call me")
        assertEquals(1.0 / 7, wer, 1e-9)
        assertEquals(setOf(3), missed)
    }

    @Test
    fun `a round has the requested size and mixes in learned words`() {
        val r = VkTraining.round(listOf("Proxmox", "Jenni"), seed = 1)
        assertEquals(VkTraining.ROUND_SIZE, r.size)
        assertTrue(r.any { "Proxmox" in it || "Jenni" in it })
    }
}
