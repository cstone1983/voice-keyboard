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

/** One small correction: [before] was dictated, the user made it [after]; [context] shows where ("___"). */
data class CorrectionEdit(val before: String, val after: String, val context: String)

/**
 * Word-level diff between what was dictated and what the user left in the field.
 *
 * - Nothing changed, or more than [MAX_CHANGED_SHARE] of the words changed (a rewrite): no edits.
 * - Otherwise one edit per changed run, with up to [CONTEXT_WORDS] unchanged words on each side.
 * - Text added only at the very start or end is the user typing on, not a correction, and is skipped.
 *
 * Words are compared without surrounding punctuation and case, but reported as written.
 */
object CorrectionDiff {
    const val MAX_CHANGED_SHARE = 0.40
    const val CONTEXT_WORDS = 3

    fun edits(inserted: String, final: String): List<CorrectionEdit> {
        val a = words(inserted)
        val b = words(final)
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val ops = diff(a.map(::norm), b.map(::norm))
        val changedA = ops.count { it.kind != Kind.SAME && it.ai >= 0 }
        if (changedA == 0 && ops.none { it.kind == Kind.INSERT }) return emptyList()
        if (changedA.toDouble() / a.size > MAX_CHANGED_SHARE) return emptyList()

        // Group consecutive non-SAME ops into runs.
        val result = mutableListOf<CorrectionEdit>()
        var i = 0
        while (i < ops.size) {
            if (ops[i].kind == Kind.SAME) { i++; continue }
            val start = i
            while (i < ops.size && ops[i].kind != Kind.SAME) i++
            val run = ops.subList(start, i)
            val before = run.filter { it.ai >= 0 && it.kind != Kind.INSERT }.joinToString(" ") { a[it.ai] }
            val after = run.filter { it.bi >= 0 && it.kind != Kind.DELETE }.joinToString(" ") { b[it.bi] }
            val atEdge = start == 0 || i == ops.size
            if (before.isEmpty() && atEdge) continue // typed on before/after the dictation
            if (norm(before) == norm(after)) continue // only case or punctuation around a word
            val left = ops.subList(0, start).filter { it.kind == Kind.SAME }.takeLast(CONTEXT_WORDS).map { b[it.bi] }
            val right = ops.subList(i, ops.size).filter { it.kind == Kind.SAME }.take(CONTEXT_WORDS).map { b[it.bi] }
            val context = (left + "___" + right).joinToString(" ")
            result.add(CorrectionEdit(before, after, context))
        }
        return result
    }

    private fun words(s: String): List<String> = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun norm(w: String): String = w.trim { !it.isLetterOrDigit() }.lowercase()

    private enum class Kind { SAME, DELETE, INSERT, REPLACE }
    private data class Op(val kind: Kind, val ai: Int, val bi: Int)

    /** LCS alignment; adjacent delete+insert pairs become REPLACE. */
    private fun diff(a: List<String>, b: List<String>): List<Op> {
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (x in n - 1 downTo 0) for (y in m - 1 downTo 0) {
            lcs[x][y] = if (a[x] == b[y]) lcs[x + 1][y + 1] + 1 else maxOf(lcs[x + 1][y], lcs[x][y + 1])
        }
        val raw = mutableListOf<Op>()
        var x = 0
        var y = 0
        while (x < n && y < m) {
            when {
                a[x] == b[y] -> { raw.add(Op(Kind.SAME, x, y)); x++; y++ }
                lcs[x + 1][y] >= lcs[x][y + 1] -> { raw.add(Op(Kind.DELETE, x, -1)); x++ }
                else -> { raw.add(Op(Kind.INSERT, -1, y)); y++ }
            }
        }
        while (x < n) { raw.add(Op(Kind.DELETE, x, -1)); x++ }
        while (y < m) { raw.add(Op(Kind.INSERT, -1, y)); y++ }
        // Pair deletes with inserts inside each changed run so a mishear reads as one replacement.
        val out = mutableListOf<Op>()
        var k = 0
        while (k < raw.size) {
            if (raw[k].kind == Kind.SAME) { out.add(raw[k]); k++; continue }
            val start = k
            while (k < raw.size && raw[k].kind != Kind.SAME) k++
            val dels = raw.subList(start, k).filter { it.kind == Kind.DELETE }
            val ins = raw.subList(start, k).filter { it.kind == Kind.INSERT }
            val pairs = minOf(dels.size, ins.size)
            for (p in 0 until pairs) out.add(Op(Kind.REPLACE, dels[p].ai, ins[p].bi))
            dels.drop(pairs).forEach { out.add(it) }
            ins.drop(pairs).forEach { out.add(it) }
        }
        return out
    }
}
