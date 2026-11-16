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
    fun `an added sentence is rejected (server log 2026-11-14)`() {
        val raw = "Sweet. We decided we're going to get Red Barn on the way down."
        val cleaned = "Sweet. We decided we're going to get Red Barn on the way down. Let's just go for all of it?"
        assertEquals("more_words", VkCleanup.rejectReason(raw, cleaned))
    }

    @Test
    fun `normal cleanup passes, invented sentence of the same length does not`() {
        assertEquals(null, VkCleanup.rejectReason("um so I think we should uh meet tomorrow", "So I think we should meet tomorrow."))
        assertEquals("new_sentence", VkCleanup.rejectReason("we meet at noon okay", "Totally awesome party tonight."))
        assertEquals("more_sentences", VkCleanup.rejectReason("see you at noon then bye", "See you at noon. Then bye."))
        assertEquals("too_short", VkCleanup.rejectReason("please send me the report from last week", "Send it."))
    }

    @Test
    fun `spoken punctuation`() {
        assertEquals(
            "Hi John, are you coming? I am.\nSee you!",
            VkCleanup.spokenPunctuation("Hi John comma are you coming question mark I am period new line see you exclamation mark"),
        )
        assertEquals("We have a trial period of two weeks.", VkCleanup.spokenPunctuation("We have a trial period of two weeks full stop"))
        // Existing dots are not touched: hosts, abbreviations, file names.
        assertEquals(
            "Open voice.example.com, e.g. on Node.js today.",
            VkCleanup.spokenPunctuation("Open voice.example.com comma e.g. on Node.js today full stop"),
        )
    }

    @Test
    fun `spoken comma never doubles a comma that is already there`() {
        assertEquals("Hi, there", VkCleanup.spokenPunctuation("Hi, comma there"))
        assertEquals("Hi, there", VkCleanup.spokenPunctuation("Hi, comma, there"))
        assertEquals("Hi, there", VkCleanup.spokenPunctuation("Hi comma, there"))
        assertEquals("Hi, there", VkCleanup.spokenPunctuation("Hi comma , there"))
        assertEquals("Done. Next", VkCleanup.spokenPunctuation("Done. comma Next"))
        assertEquals("Done.", VkCleanup.spokenPunctuation("Done, period"))
        assertEquals("Okay? Yes", VkCleanup.spokenPunctuation("Okay, question mark yes"))
    }
}
