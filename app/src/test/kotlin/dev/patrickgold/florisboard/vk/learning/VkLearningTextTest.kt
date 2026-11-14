package dev.patrickgold.florisboard.vk.learning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VkLearningTextTest {
    @Test
    fun `finds the dictated span between its anchors after an edit`() {
        val full = "Hi team, I will meet Jenni at the cafe. Thanks"
        assertEquals(" I will meet Jenni at the cafe.", VkLearning.locate(full, "Hi team,", " Thanks"))
    }

    @Test
    fun `span at the start or end of the field`() {
        assertEquals("hello world", VkLearning.locate("hello world", "", ""))
        assertEquals(" see you", VkLearning.locate("ok see you", "ok", ""))
    }

    @Test
    fun `anchor gone means no guess`() {
        assertNull(VkLearning.locate("something else entirely", "Hi team,", ""))
    }

    @Test
    fun `replacements are whole-word and keep case`() {
        val reps = listOf(VkLearning.Replacement("jenny", "Jenni"), VkLearning.Replacement("pro mox", "proxmox"))
        assertEquals("Ask Jenni about Proxmox, not jennyfer.", VkLearning.applyReplacements("Ask jenny about Pro mox, not jennyfer.", reps))
    }
}
