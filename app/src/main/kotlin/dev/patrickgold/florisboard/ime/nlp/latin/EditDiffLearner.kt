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

import kotlin.math.max
import kotlin.math.min

/**
 * Voice Keyboard: finds the words the person fixed by hand, anywhere in the last few dozen words — the
 * word just typed, or one a few words back they went back to.
 *
 * At each word end the keyboard keeps a window of the text around the cursor. The next window is compared
 * with it word by word: new words typed are insertions and say nothing; a word that became a *similar*
 * word is a fix; a word that became two words with a stray letter (or a missing space) between them is a
 * joined-words fix. A word swapped for a different one is the person changing their mind and is ignored,
 * and so is anything at the edges of the window, where it was merely cut off.
 *
 * Pure functions; the windows themselves live only in memory and are never stored.
 */
object EditDiffLearner {

    sealed class Event {
        /** [typo] was changed to [fix] (one word). */
        data class Fixed(val typo: String, val fix: String) : Event()

        /** [joined] became the two words [first] and [second]; [stray] the letter between them, if any. */
        data class Split(val joined: String, val first: String, val second: String, val stray: Char?) : Event()
    }

    /** Words of a window. Punctuation stays attached so the alignment is not fooled by it. */
    fun tokens(text: String): List<String> = text.split(WS).filter { it.isNotEmpty() }

    /**
     * The fixes between two windows of the same text. [cutStart]/[cutEnd]: whether either window was cut
     * off there (then changes touching that end are only the cut, and are ignored).
     */
    fun events(before: List<String>, after: List<String>, cutStart: Boolean = true, cutEnd: Boolean = true): List<Event> {
        if (before.isEmpty() || after.isEmpty()) return emptyList()
        val out = ArrayList<Event>()
        for ((oldPart, newPart, atEdge) in hunks(before, after, cutStart, cutEnd)) {
            if (atEdge) continue
            val e = classify(oldPart, newPart) ?: continue
            out.add(e)
        }
        return out
    }

    internal data class Hunk(val old: List<String>, val new: List<String>, val atEdge: Boolean)

    /** The changed runs between [a] and [b], from a longest-common-subsequence alignment of the words. */
    internal fun hunks(a: List<String>, b: List<String>, cutStart: Boolean = true, cutEnd: Boolean = true): List<Hunk> {
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else max(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<Hunk>()
        var i = 0
        var j = 0
        var oi = -1
        var oj = -1
        fun flush(ei: Int, ej: Int) {
            if (oi < 0) return
            val old = a.subList(oi, ei)
            val new = b.subList(oj, ej)
            // Touching either end of a window: that is where the windows were cut, not where text changed.
            val atEdge = (cutStart && (oi < EDGE || oj < EDGE)) || (cutEnd && (ei > n - EDGE || ej > m - EDGE))
            out.add(Hunk(old, new, atEdge))
            oi = -1; oj = -1
        }
        while (i < n || j < m) {
            if (i < n && j < m && a[i] == b[j]) {
                flush(i, j); i++; j++
            } else {
                if (oi < 0) { oi = i; oj = j }
                if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) j++ else i++
            }
        }
        flush(n, m)
        return out
    }

    private fun clean(w: String) = w.trim { !it.isLetterOrDigit() && it != '\'' && it != '’' }

    private fun isWord(w: String) = w.length in 1..32 && w.all { it.isLetter() || it == '\'' || it == '’' }

    internal fun classify(old: List<String>, new: List<String>): Event? {
        if (old.size == 1 && new.size == 1) {
            val a = clean(old[0])
            val b = clean(new[0])
            if (!isWord(a) || !isWord(b) || a.length < 2 || b.length < 2) return null
            if (a.equals(b, ignoreCase = true)) return null // only the case changed
            val d = distance(a.lowercase(), b.lowercase())
            // Similar words only: anything further is a different word, i.e. a change of mind.
            if (d > 2 || d > max(a.length, b.length) / 2) return null
            return Event.Fixed(a, b)
        }
        if (old.size == 1 && new.size == 2) {
            val joined = clean(old[0])
            val first = clean(new[0])
            val second = clean(new[1])
            if (!isWord(joined) || !isWord(first) || !isWord(second)) return null
            val j = joined.lowercase()
            val f = first.lowercase()
            val s = second.lowercase()
            if (j == f + s) return Event.Split(joined, first, second, null)
            if (j.length == f.length + s.length + 1 && j.startsWith(f) && j.endsWith(s)) {
                return Event.Split(joined, first, second, joined[f.length])
            }
        }
        return null
    }

    /** Optimal string alignment distance (adjacent swaps count one). */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > 3) return 9
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /**
     * The reading of [word] as two words joined by a mistyped space: a letter next to the space bar
     * between them ([strayKeys]), or no space at all. [known] says whether a lowercase word exists and how
     * common it is (0 = not a word). Returns the two words and the rarer one's frequency, best first.
     */
    fun splitJoined(word: String, strayKeys: Set<Char>, known: (String) -> Int): Triple<String, String, Int>? {
        val w = word.lowercase()
        if (w.length < 4 || !w.all { it.isLetter() }) return null
        var best: Triple<String, String, Int>? = null
        fun consider(i: Int, j: Int) {
            val left = word.substring(0, i)
            val right = word.substring(j)
            if (!okPart(left) || !okPart(right)) return
            val fl = known(left.lowercase())
            val fr = known(right.lowercase())
            val score = min(fl, fr)
            if (score <= 0) return
            if (best == null || score > best!!.third) best = Triple(left, right, score)
        }
        for (i in 1 until w.length) {
            consider(i, i) // missing space
            if (i < w.length - 1 && w[i] in strayKeys) consider(i, i + 1) // a letter instead of space
        }
        return best
    }

    private fun okPart(p: String) = p.length >= 2 || p.equals("a", true) || p.equals("i", true)

    /** The letters next to the space bar on a QWERTY layout. */
    val SPACE_NEIGHBOURS = setOf('c', 'v', 'b', 'n', 'm', 'x')

    private const val EDGE = 2
    private val WS = Regex("\\s+")
}
