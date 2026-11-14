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

/**
 * Voice Keyboard: guards around the automatic cleanup (a small local model that improvises when it is
 * given almost nothing, server finding 2026-11-14), and spoken punctuation before it.
 */
object VkCleanup {
    const val MIN_INPUT_CHARS = 3
    const val TEMPERATURE = 0.2

    /** Whether the transcription is worth sending to cleanup at all. */
    fun shouldClean(input: String): Boolean = input.trim().length >= MIN_INPUT_CHARS

    /** Answer budget: about twice the input in tokens (≈ 4 characters a token), plus room. */
    fun maxTokens(input: String): Int = input.length / 2 + 50

    /** True when the cleanup output can be used; otherwise the raw transcription goes in. */
    fun isSane(input: String, output: String): Boolean = rejectReason(input, output) == null

    /**
     * Why a cleanup answer must not be used, or null when it may. The cleanup may only remove fillers and fix
     * grammar and punctuation, so its answer must not grow (server finding 2026-11-14: a style note was copied
     * into the text as a whole new sentence):
     * - empty, "text", or `<text>` tags;
     * - more words than the raw text + 2, or more sentences than the raw text;
     * - a sentence whose words are mostly (> 60 %) not in the raw text;
     * - shorter than half of the raw text.
     */
    fun rejectReason(input: String, output: String): String? {
        val out = output.trim()
        if (out.isEmpty()) return "empty"
        if (out.equals("text", ignoreCase = true)) return "text"
        if (out.contains("<text", ignoreCase = true) || out.contains("</text", ignoreCase = true)) return "tags"
        val rawWords = words(input)
        val outWords = words(out)
        if (outWords.size > rawWords.size + 2) return "more_words"
        if (sentences(out).size > sentences(input).size.coerceAtLeast(1)) return "more_sentences"
        val known = rawWords.map(::norm).toSet()
        for (s in sentences(out)) {
            val ws = words(s).map(::norm).filter { it.isNotEmpty() }
            if (ws.size < 2) continue
            val unknown = ws.count { it !in known }
            if (unknown.toDouble() / ws.size > 0.6) return "new_sentence"
        }
        if (out.length < input.trim().length * 0.5) return "too_short"
        return null
    }

    private fun words(s: String) = s.split(Regex("\\s+")).filter { w -> w.any { it.isLetterOrDigit() } }

    private fun norm(w: String) = w.trim { !it.isLetterOrDigit() }.lowercase()

    /** Sentences: pieces ending in . ! ? or a line break, that contain a letter or digit. */
    private fun sentences(s: String) =
        s.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { p -> p.any { it.isLetterOrDigit() } }

    private val SPOKEN = listOf(
        Regex("""\s*,?\s*\b(new paragraph)\b[.,]?\s*""", RegexOption.IGNORE_CASE) to "\n\n",
        Regex("""\s*,?\s*\b(new line|newline)\b[.,]?\s*""", RegexOption.IGNORE_CASE) to "\n",
        Regex("""\s*,?\s*\b(question mark)\b[.,?]?""", RegexOption.IGNORE_CASE) to "?",
        Regex("""\s*,?\s*\b(exclamation (?:mark|point))\b[.,!]?""", RegexOption.IGNORE_CASE) to "!",
        Regex("""\s*,?\s*\b(full stop)\b[.,]?""", RegexOption.IGNORE_CASE) to ".",
        // "period" only where it ends a sentence (followed by the end, a line break or a capital), so "a
        // trial period of two weeks" stays as it is.
        Regex("""\s*,?\s*\bperiod\b[.,]?(?=\s*$|\s*\n|\s+[A-Z])""") to ".",
        Regex("""\s*,?\s*\bPeriod\b[.,]?(?=\s*$|\s*\n)""") to ".",
        Regex("""\s*\b(comma)\b[,.]?""", RegexOption.IGNORE_CASE) to ",",
    )

    /** "comma", "period", "question mark", "new line" … said aloud become the marks themselves. */
    fun spokenPunctuation(text: String): String {
        var out = text
        for ((re, mark) in SPOKEN) out = re.replace(out, Regex.escapeReplacement(mark))
        // A mark directly followed by a letter gets its space back ("hi,there" -> "hi, there").
        out = out.replace(Regex("""([,.?!])(?=\p{L})"""), "$1 ")
        // Capital after a sentence end that came from a spoken mark.
        out = out.replace(Regex("""([.?!]\s+|\n)(\p{Ll})""")) { m -> m.groupValues[1] + m.groupValues[2].uppercase() }
        return out.trim()
    }
}
