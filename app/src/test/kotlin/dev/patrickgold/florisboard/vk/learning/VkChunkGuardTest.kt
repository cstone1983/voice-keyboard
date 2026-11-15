package dev.patrickgold.florisboard.vk.learning

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VkChunkGuardTest {
    private val previous = "We decided to get pizza on the way home and watch the game tonight."

    @Test
    fun `a chunk repeating the end of the previous text is an echo`() {
        assertTrue(VkChunkGuard.isEcho("on the way home and watch the game tonight", previous, null))
    }

    @Test
    fun `a chunk repeating the prompt is an echo`() {
        assertTrue(VkChunkGuard.isEcho("Proxmox, Jenni, Lutron, n8n.", "", "Glossary: Proxmox, Jenni, Lutron, n8n."))
    }

    @Test
    fun `new speech is kept`() {
        assertFalse(VkChunkGuard.isEcho("Then we can clean up the kitchen before bed.", previous, "Proxmox, Jenni"))
        assertFalse(VkChunkGuard.isEcho("yes", previous, null))
    }
}
