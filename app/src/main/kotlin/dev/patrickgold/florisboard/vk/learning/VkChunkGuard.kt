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
 * Voice Keyboard: Whisper sometimes "hears" its prompt on a quiet or very short chunk and returns the
 * previous text (or the vocabulary prompt) again — the "previous transcription appended at the end" bug.
 * A chunk whose words mostly (> 60 %) already stand at the end of the text so far, or in the prompt, is such
 * an echo and is dropped.
 */
object VkChunkGuard {
    private const val ECHO_SHARE = 0.6

    fun isEcho(chunk: String, previousText: String, prompt: String?): Boolean {
        val words = words(chunk)
        if (words.isEmpty()) return false
        // The end of what was said so far: about twice the chunk's length, enough to contain a repeat.
        val tail = words(previousText).takeLast(words.size * 2 + 5).toSet()
        val promptWords = words(prompt.orEmpty()).toSet()
        val inTail = words.count { it in tail }.toDouble() / words.size
        val inPrompt = words.count { it in promptWords }.toDouble() / words.size
        // Short answers ("yes", "ok") are often legitimately repeated; only judge chunks of 3+ words.
        if (words.size < 3) return false
        return inTail > ECHO_SHARE || inPrompt > ECHO_SHARE
    }

    private fun words(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
}
