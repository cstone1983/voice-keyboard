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

import dev.patrickgold.florisboard.ime.nlp.latin.PersonalWordCounts
import kotlin.math.max

/**
 * Voice Keyboard: the three-way merge behind the multi-device profile sync ([VkPersonSync]).
 *
 * Every device keeps a *base*: the profile as it was after its last successful sync. Against that base
 * the device can tell what happened where:
 *
 *  - **Counts** (word uses, learned words, word pairs) add up: the server's value plus what this device
 *    learned since its base. Two devices typing at the same time both keep their uses.
 *  - **Deletions**: an entry in the base that is gone here was removed here; one that is gone from the
 *    server was removed on another device. Either way it goes. A server copy that lacks a whole section
 *    says nothing about that section (no deletions from it).
 *  - **Settings**: one changed here since the base goes up; otherwise the server's comes down (another
 *    device may have changed it). Without a base for the settings (first sync on this device, or the
 *    person was just switched to) the server's win.
 *
 * Pure functions on plain maps, so it is tested without Android.
 */
object ProfileMerge {

    data class Counted(val word: String, val count: Int, val lastUsed: Long)

    data class DictWord(val word: String, val locale: String?, val shortcut: String?)

    /**
     * Counted entries by key. [remote] null = the server copy has no such section. [base] null = this
     * device has not synced this section before.
     */
    fun counted(
        remote: Map<String, Counted>?,
        local: Map<String, Counted>,
        base: Map<String, Counted>?,
    ): Map<String, Counted> {
        val out = HashMap<String, Counted>()
        val keys = HashSet<String>(local.keys).apply { remote?.let { addAll(it.keys) } }
        for (k in keys) {
            val r = remote?.get(k)
            val l = local[k]
            val b = base?.get(k)
            when {
                b != null && l == null -> Unit                                // removed on this device
                b != null && remote != null && r == null -> Unit              // removed on another device
                r != null && l != null -> {
                    val gained = if (b != null) max(0, l.count - b.count) else l.count
                    out[k] = Counted(if (l.lastUsed >= r.lastUsed) l.word else r.word, r.count + gained, max(r.lastUsed, l.lastUsed))
                }
                r != null -> out[k] = r
                l != null -> out[k] = l
            }
        }
        return out
    }

    /** Word-use scores ([score, lastUsed]), compared decayed to [nowSec]. Never deleted (they fade). */
    fun words(
        remote: Map<String, Pair<Double, Long>>?,
        local: Map<String, Pair<Double, Long>>,
        base: Map<String, Pair<Double, Long>>?,
        nowSec: Long,
    ): Map<String, Pair<Double, Long>> {
        fun d(v: Pair<Double, Long>?) = v?.let { PersonalWordCounts.decayed(it.first, it.second, nowSec) } ?: 0.0
        val out = HashMap<String, Pair<Double, Long>>()
        val keys = HashSet<String>(local.keys).apply { remote?.let { addAll(it.keys) } }
        for (k in keys) {
            val r = remote?.get(k)
            val l = local[k]
            val b = base?.get(k)
            val gained = max(0.0, d(l) - d(b))
            val score = d(r) + gained
            if (score <= 0.0) continue
            out[k] = score to nowSec
        }
        return out
    }

    /** The personal dictionary, by "word lowercase|locale". */
    fun dictionary(
        remote: Map<String, DictWord>?,
        local: Map<String, DictWord>,
        base: Map<String, DictWord>?,
    ): Map<String, DictWord> {
        val out = HashMap<String, DictWord>()
        val keys = HashSet<String>(local.keys).apply { remote?.let { addAll(it.keys) } }
        for (k in keys) {
            val r = remote?.get(k)
            val l = local[k]
            val b = base?.get(k)
            when {
                b != null && l == null -> Unit
                b != null && remote != null && r == null -> Unit
                r != null && l != null -> out[k] = if (b != null && l != b) l else r
                r != null -> out[k] = r
                l != null -> out[k] = l
            }
        }
        return out
    }

    /** Settings by name, values as their JSON text. */
    fun settings(
        remote: Map<String, String>?,
        local: Map<String, String>,
        base: Map<String, String>?,
    ): Map<String, String> {
        val out = HashMap<String, String>()
        for ((name, l) in local) {
            val r = remote?.get(name)
            val b = base?.get(name)
            out[name] = when {
                r == null -> l
                base == null -> r            // no settings base: the person's settings come from the server
                b != null && l != b -> l     // changed on this device since the last sync
                else -> r
            }
        }
        return out
    }

    /**
     * The new local value after a merge, keeping what happened on this device while the sync ran:
     * [merged] plus whatever the count grew by since [atMerge] was read ([now]).
     */
    fun keepWhileSyncing(merged: Int, atMerge: Int?, now: Int?): Int =
        merged + max(0, (now ?: 0) - (atMerge ?: 0))
}
