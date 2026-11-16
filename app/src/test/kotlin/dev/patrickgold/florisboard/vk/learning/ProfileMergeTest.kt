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

import dev.patrickgold.florisboard.vk.learning.ProfileMerge.Counted
import dev.patrickgold.florisboard.vk.learning.ProfileMerge.DictWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileMergeTest {
    private fun c(n: Int, t: Long = 100) = Counted("w", n, t)

    @Test
    fun `two devices learning at once both keep their uses`() {
        // Both synced at 10. The phone used it 5 more times, the tablet 3 (already on the server: 13).
        val merged = ProfileMerge.counted(remote = mapOf("en|w" to c(13)), local = mapOf("en|w" to c(15)), base = mapOf("en|w" to c(10)))
        assertEquals(18, merged["en|w"]!!.count)
    }

    @Test
    fun `a new device or a new word simply joins`() {
        assertEquals(10, ProfileMerge.counted(mapOf("k" to c(10)), emptyMap(), null)["k"]!!.count)
        assertEquals(4, ProfileMerge.counted(emptyMap(), mapOf("k" to c(4)), emptyMap())["k"]!!.count)
    }

    @Test
    fun `a word removed on any device is removed everywhere`() {
        val base = mapOf("k" to c(5))
        // Removed here.
        assertFalse("k" in ProfileMerge.counted(mapOf("k" to c(7)), emptyMap(), base))
        // Removed on another device.
        assertFalse("k" in ProfileMerge.counted(emptyMap(), mapOf("k" to c(6)), base))
        // A server copy without the section removes nothing.
        assertTrue("k" in ProfileMerge.counted(null, mapOf("k" to c(6)), base))
    }

    @Test
    fun `word scores add what this device gained`() {
        val now = 1_800_000_000L
        val merged = ProfileMerge.words(
            remote = mapOf("hi" to (12.0 to now)),
            local = mapOf("hi" to (14.0 to now)),
            base = mapOf("hi" to (10.0 to now)),
            nowSec = now,
        )
        assertEquals(16.0, merged["hi"]!!.first, 1e-9)
    }

    @Test
    fun `settings changed on another device come down, changed here go up`() {
        val base = mapOf("autocorrect" to "true", "spread" to "15")
        val merged = ProfileMerge.settings(
            remote = mapOf("autocorrect" to "false", "spread" to "15"), // tablet turned autocorrect off
            local = mapOf("autocorrect" to "true", "spread" to "25"),   // this phone changed spread
            base = base,
        )
        assertEquals("false", merged["autocorrect"])
        assertEquals("25", merged["spread"])
    }

    @Test
    fun `without a settings base the person's server settings win`() {
        val merged = ProfileMerge.settings(mapOf("autocorrect" to "false"), mapOf("autocorrect" to "true"), null)
        assertEquals("false", merged["autocorrect"])
    }

    @Test
    fun `dictionary words follow additions and removals`() {
        val a = DictWord("Proxmox", "en", null)
        val b = DictWord("Tyler", null, null)
        val merged = ProfileMerge.dictionary(
            remote = mapOf("proxmox|en" to a),          // tablet removed Tyler
            local = mapOf("proxmox|en" to a, "tyler|" to b, "kayak|" to DictWord("kayak", null, null)),
            base = mapOf("proxmox|en" to a, "tyler|" to b),
        )
        assertEquals(setOf("proxmox|en", "kayak|"), merged.keys)
    }

    @Test
    fun `typing during a sync is kept`() {
        assertEquals(20, ProfileMerge.keepWhileSyncing(merged = 18, atMerge = 15, now = 17))
        assertEquals(18, ProfileMerge.keepWhileSyncing(merged = 18, atMerge = 15, now = 15))
    }
}
