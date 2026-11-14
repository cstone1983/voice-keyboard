package dev.patrickgold.florisboard.vk.learning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VkCleanupTest {
    @Test
    fun `short input is not cleaned`() {
        assertFalse(VkCleanup.shouldClean(""))
        assertFalse(VkCleanup.shouldClean(" a "))
        assertTrue(VkCleanup.shouldClean("hey"))
    }

    @Test
    fun `sanity check rejects improvised output`() {
        val input = "um so I think we should meet tomorrow"
        assertTrue(VkCleanup.isSane(input, "I think we should meet tomorrow."))
        assertFalse(VkCleanup.isSane(input, "text"))
        assertFalse(VkCleanup.isSane(input, "<text>I think</text>"))
        assertFalse(VkCleanup.isSane(input, ""))
        assertFalse(VkCleanup.isSane(input, "Ok."))
        assertFalse(VkCleanup.isSane(input, input.repeat(5)))
    }

    @Test
    fun `spoken punctuation`() {
        assertEquals(
            "Hi John, are you coming? I am.\nSee you!",
            VkCleanup.spokenPunctuation("Hi John comma are you coming question mark I am period new line see you exclamation mark"),
        )
        assertEquals("We have a trial period of two weeks.", VkCleanup.spokenPunctuation("We have a trial period of two weeks full stop"))
    }
}
