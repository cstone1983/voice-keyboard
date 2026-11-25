/*
 * Copyright (C) 2026 Voice Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.vk.learning

import dev.patrickgold.florisboard.ime.nlp.dictatedWordPairs
import dev.patrickgold.florisboard.ime.nlp.latin.PersonalWordCounts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VkPersonalizationTest {
    @Test
    fun `vocabulary hint puts the most used words first and stays short`() {
        val uses = mapOf("Tyler" to 9.0, "Proxmox" to 3.0)
        val ranked = VkVocabularyHint.rank(listOf("Proxmox", "morgan", "Tyler", "tyler", "a b", "me@x.com")) { uses[it] ?: 0.0 }
        assertEquals(listOf("Tyler", "Proxmox", "morgan"), ranked)
        assertEquals("Tyler, Proxmox, morgan.", VkVocabularyHint.build(ranked))
        val long = VkVocabularyHint.build((1..50).map { "Word$it" })
        assertTrue(long.length <= 181)
        assertEquals("", VkVocabularyHint.build(emptyList()))
    }

    @Test
    fun `dictated pairs stay inside a clause and skip numbers`() {
        assertEquals(
            listOf("see" to "you", "you" to "tomorrow", "bring" to "the"),
            dictatedWordPairs("See you tomorrow. Bring the 5 chairs").map { it.first.lowercase() to it.second },
        )
        assertEquals(listOf("hi" to "there"), dictatedWordPairs("Hi there, Tyler").map { it.first.lowercase() to it.second })
    }

    @Test
    fun `merging counts keeps the larger and never adds`() {
        val now = 1_800_000_000L
        val c = PersonalWordCounts(null).apply { useUser("a") }
        repeat(4) { c.count("kayak", now) }
        c.merge(mapOf("kayak" to (3.0 to now), "kettle" to (5.0 to now)), now)
        assertEquals(4.0, c.scoreOf("kayak", now), 1e-9)
        assertEquals(5.0, c.scoreOf("kettle", now), 1e-9)
        c.merge(mapOf("kayak" to (7.0 to now)), now)
        assertEquals(7.0, c.scoreOf("kayak", now), 1e-9)
    }
}
