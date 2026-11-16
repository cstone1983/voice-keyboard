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

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.dictionary.LearnedWordsStore
import dev.patrickgold.florisboard.ime.nlp.latin.PersonalWords
import dev.patrickgold.florisboard.ime.nlp.latin.WordLearningGate

/**
 * Voice Keyboard: the selected person's own words (names, places, terms the keyboard learned or they
 * added to their dictionary), most used first, as a short hint for speech-to-text so it spells them the
 * way this person does. Built in the background when a dictation starts; the transcription uses the last
 * one built (reading the stores on the recording path would delay the upload).
 */
object VkVocabularyHint {
    private val prefs by FlorisPreferenceStore
    const val MAX_WORDS = 20
    private const val MAX_CHARS = 180

    @Volatile var current: String = ""
        private set
    @Volatile private var builtFor: String? = null

    fun enabled(): Boolean = prefs.dictate.vkVocabularyHint.get()

    /** The hint for the selected person, or "" (also when it was built for someone else). */
    fun hint(): String =
        if (enabled() && builtFor == (VkPersonFiles.personKey() ?: "")) current else ""

    suspend fun refresh(context: Context) {
        if (!enabled()) { current = ""; return }
        val who = VkPersonFiles.personKey() ?: ""
        val candidates = LinkedHashSet<String>()
        runCatching {
            val lang = prefs.dictate.activeInputLanguage.get().ifBlank { "en" }.substringBefore('-').substringBefore('_')
            LearnedWordsStore.snapshot(context, lang)
                .entriesStartingWith("", WordLearningGate.scoreFloorFor(WordLearningGate.SIGHTINGS_FOR_SUGGESTIONS), 200)
                .forEach { candidates.add(it.first) }
        }
        runCatching {
            val dm = DictionaryManager.default()
            dm.loadUserDictionariesIfNecessary()
            dm.florisUserDictionaryDao()?.queryAll()?.forEach { candidates.add(it.word) }
        }
        val counts = PersonalWords.current(context, prefs)
        val words = rank(candidates.toList()) { counts.scoreOf(it) }
        current = build(words)
        builtFor = who
    }

    /** Most used first; words used equally keep their order. Only single words that look like words. */
    internal fun rank(words: List<String>, uses: (String) -> Double): List<String> =
        words.asSequence()
            .map { it.trim() }
            .filter { w -> w.length in 2..30 && w.none { it.isWhitespace() } && w.any { it.isLetter() } && '@' !in w }
            .distinctBy { it.lowercase() }
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<String>> { uses(it.value) }.thenBy { it.index })
            .map { it.value }
            .toList()

    /** "Tyler, Proxmox, Stoneyshome." — at most [MAX_WORDS] words and [MAX_CHARS] characters. */
    internal fun build(words: List<String>): String {
        val sb = StringBuilder()
        for (w in words.take(MAX_WORDS)) {
            val add = if (sb.isEmpty()) w else ", $w"
            if (sb.length + add.length + 1 > MAX_CHARS) break
            sb.append(add)
        }
        return if (sb.isEmpty()) "" else "$sb."
    }
}
