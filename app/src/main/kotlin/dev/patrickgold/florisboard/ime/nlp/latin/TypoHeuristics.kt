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

/**
 * Voice Keyboard: "is this short word an ordinary slip of a very common word?"
 *
 * The learning gate only trusted the dictionary from six letters up (a four-letter string is one edit
 * from *something*), so short typos with no clear tap reading were learned as words, and the third time
 * promoted into the personal dictionary — after which autocorrect never touched them again. The user's
 * data showed exactly that: "habe", "tbe", "nkt", "wotk", "wkth", "oit" in the personal dictionary.
 *
 * The test here is much narrower than "one edit from something", which is why it is safe on short
 * words: one edit *of a kind fingers make* — a key next to the right one (QWERTY), two neighbouring
 * letters swapped, a letter doubled or missing — from a word that is among the most common of all
 * ([COMMON_FREQ] on the 128..255 dictionary scale). Names are almost never that.
 */
object TypoHeuristics {
    /** Only the very common words count as "what was meant". */
    const val COMMON_FREQ = 200

    private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val pos: Map<Char, Pair<Int, Int>> = buildMap {
        ROWS.forEachIndexed { r, row -> row.forEachIndexed { i, c -> put(c, r to i) } }
    }

    /** Whether [a] and [b] are neighbouring keys on QWERTY (same row ±1, or the rows above/below). */
    fun adjacent(a: Char, b: Char): Boolean {
        val pa = pos[a.lowercaseChar()] ?: return false
        val pb = pos[b.lowercaseChar()] ?: return false
        if (pa == pb) return false
        val dr = pb.first - pa.first
        if (dr == 0) return kotlin.math.abs(pb.second - pa.second) == 1
        if (kotlin.math.abs(dr) != 1) return false
        // Staggered rows: a key touches the two keys below/above it that overlap it.
        val (upper, lower) = if (dr == 1) pa to pb else pb to pa
        return lower.second == upper.second || lower.second == upper.second - 1
    }

    /**
     * The very common word [word] is most likely a slip of, or null. [freq] gives a lowercase word's
     * dictionary frequency (null = not a word). Words with digits, or capitals after the first letter
     * (names, product names), are never slips here.
     */
    fun likelySlipOf(word: String, freq: (String) -> Int?): String? {
        if (word.length < 2 || word.length > 8) return null
        if (!word.all { it.isLetter() }) return null
        if (word.drop(1).any { it.isUpperCase() }) return null
        val w = word.lowercase()
        if ((freq(w) ?: 0) >= COMMON_FREQ) return null // a common word itself
        var best: String? = null
        var bestFreq = COMMON_FREQ - 1
        fun consider(c: String) {
            if (c == w || c.isEmpty()) return
            val f = freq(c) ?: return
            if (f > bestFreq) { bestFreq = f; best = c }
        }
        for (i in w.indices) {
            // A neighbouring key hit instead.
            for (k in pos.keys) if (adjacent(w[i], k)) consider(w.substring(0, i) + k + w.substring(i + 1))
            // An extra letter: doubled, or a neighbour hit as well.
            if (w.length >= 3) {
                val extra = w[i]
                val isDouble = (i > 0 && w[i - 1] == extra) || (i < w.length - 1 && w[i + 1] == extra)
                val isNeighbour = (i > 0 && adjacent(w[i - 1], extra)) || (i < w.length - 1 && adjacent(w[i + 1], extra))
                if (isDouble || isNeighbour) consider(w.removeRange(i, i + 1))
            }
            // Two neighbouring letters swapped.
            if (i < w.length - 1) consider(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
        }
        // A letter missing (only from 3 letters, and only for the commonest words).
        if (w.length >= 3) {
            for (i in 0..w.length) for (k in pos.keys) {
                val c = w.substring(0, i) + k + w.substring(i)
                val f = freq(c) ?: continue
                if (f >= 220 && f > bestFreq) { bestFreq = f; best = c }
            }
        }
        return best
    }
}
