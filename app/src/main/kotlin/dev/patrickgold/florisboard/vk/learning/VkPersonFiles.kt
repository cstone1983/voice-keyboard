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
import dev.patrickgold.florisboard.ime.nlp.latin.PersonalWordCounts

/**
 * Voice Keyboard: which on-device files belong to the person selected in the Voice Keyboard settings
 * (learned words and word pairs, the personal dictionary, word counts, touch habits).
 *
 * Each person has their own files, named with a hash of the person's id (never the id itself), filled
 * from their profile on the server ([VkPersonSync]). Nobody inherits what is already on the phone: the
 * files from before people could be chosen ([base] names) are used only while nobody is selected, and
 * are added to a person's profile only when the user asks ([VkPersonSync.adoptPhoneData]).
 */
object VkPersonFiles {
    private val prefs by FlorisPreferenceStore

    /** Hash of the selected person's id, or null when nobody is selected. */
    fun personKey(): String? = VkIdentity.currentUser(prefs)?.let { PersonalWordCounts.keyFor(it) }

    /** The file (or database) name for [base] that belongs to the selected person. */
    @Suppress("UNUSED_PARAMETER")
    fun nameFor(context: Context, base: String): String {
        val key = personKey() ?: return base
        return "${base}_$key"
    }
}
