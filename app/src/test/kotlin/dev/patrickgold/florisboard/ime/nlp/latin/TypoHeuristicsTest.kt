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

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** On the shipping English word list, with the person's own typos and own words from their profile. */
class TypoHeuristicsTest {
    private val freq: Map<String, Int> by lazy {
        val f = listOf("src/main/assets/ime/dict/en.json", "app/src/main/assets/ime/dict/en.json").map(::File).first { it.exists() }
        Regex("\"([^\"]+)\":\\s*(\\d+)").findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }
    private fun slip(w: String) = TypoHeuristics.likelySlipOf(w) { freq[it] }

    @Test
    fun `neighbouring keys on qwerty`() {
        assertTrue(TypoHeuristics.adjacent('b', 'v'))
        assertTrue(TypoHeuristics.adjacent('b', 'h'))
        assertTrue(TypoHeuristics.adjacent('a', 'q'))
        assertTrue(TypoHeuristics.adjacent('s', 'e'))
        assertFalse(TypoHeuristics.adjacent('a', 'p'))
        assertFalse(TypoHeuristics.adjacent('q', 'z'))
    }

    @Test
    fun `the typos that were learned as words are slips`() {
        assertEquals("have", slip("habe"))
        assertEquals("the", slip("tbe"))
        assertEquals("not", slip("nkt"))
        assertEquals("work", slip("wotk"))
        assertEquals("with", slip("wkth"))
        assertTrue(slip("oit") in setOf("out", "it"))
        assertEquals("when", slip("whe"))
        assertEquals("the", slip("tje"))
        assertEquals("you", slip("Yuo"))
    }

    @Test
    fun `names, own words and real words are not`() {
        for (w in listOf("proxmox", "homeassistant", "N8n", "jessy", "cloudflare", "keystore", "the", "have", "WH4E", "udm"))
            assertNull(slip(w), w)
    }
}
