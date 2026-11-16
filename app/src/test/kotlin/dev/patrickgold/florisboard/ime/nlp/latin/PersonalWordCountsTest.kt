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

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PersonalWordCountsTest {
    private val now = 1_800_000_000L

    @Test
    fun `most used words come first and need three uses`() {
        val c = PersonalWordCounts(null).apply { useUser("a") }
        repeat(10) { c.count("tomorrow", now) }
        repeat(3) { c.count("together", now) }
        repeat(2) { c.count("today", now) }
        val top = c.frequentStartingWith("to", 5, now)
        assertEquals(listOf("tomorrow", "together"), top.map { it.first })
        assertTrue(top[0].second > top[1].second)
    }

    @Test
    fun `sentences count every word, case and punctuation ignored`() {
        val c = PersonalWordCounts(null).apply { useUser("a") }
        repeat(3) { c.count("Hey Tyler, see you tomorrow.", now) }
        assertEquals(3.0, c.scoreOf("tyler", now), 1e-9)
        assertEquals(3.0, c.scoreOf("tomorrow", now), 1e-9)
    }

    @Test
    fun `counts fade with a thirty day half life`() {
        assertEquals(2.0, PersonalWordCounts.decayed(4.0, now, now + 30 * 86_400L), 1e-9)
    }

    @Test
    fun `rank stays between learned words and the very top`() {
        assertEquals(209, PersonalWordCounts.rankFor(3.0))
        assertEquals(250, PersonalWordCounts.rankFor(10_000.0))
    }

    @Test
    fun `each person has their own file and counts survive a reload`() {
        val dir = Files.createTempDirectory("pwc").toFile()
        val c = PersonalWordCounts(dir)
        c.useUser("alice"); repeat(4) { c.count("kayak", now) }
        c.useUser("bob"); repeat(4) { c.count("kettle", now) }
        c.flush(now)
        val reloaded = PersonalWordCounts(dir)
        reloaded.useUser("alice")
        assertEquals(listOf("kayak"), reloaded.frequentStartingWith("k", 5, now).map { it.first })
        reloaded.useUser("bob")
        assertEquals(listOf("kettle"), reloaded.frequentStartingWith("k", 5, now).map { it.first })
        assertNotEquals(PersonalWordCounts.keyFor("alice"), PersonalWordCounts.keyFor("bob"))
        dir.deleteRecursively()
    }
}
