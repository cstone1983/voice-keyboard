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
import java.security.MessageDigest
import kotlin.math.ln
import kotlin.math.pow

/**
 * Voice Keyboard: how often the current person uses each word, so the suggestion strip offers *their*
 * common words first ("ty" → "tyler" for one person, "type" for another).
 *
 * One small file per person, `files/vk_learning/words_<hash>.json` (on the device only, outside backup).
 * A word counts each time it is finished — typed, picked from the strip, auto-corrected to, or dictated.
 * Counts fade with a [HALF_LIFE_DAYS] half-life so habits that change are followed.
 *
 * Only ranking changes: a word is never offered here that the dictionary (or the personal dictionary)
 * does not already know, so a typo typed often does not become a suggestion.
 */
class PersonalWordCounts(private val dir: File?) {

    private class Entry(var score: Double, var lastUsed: Long)

    private var userKey: String? = null
    private var words = HashMap<String, Entry>()
    private var dirty = 0
    private var lastSaveSec = 0L

    /** Selects whose counts are used; loads that person's file. Cheap when nothing changed. */
    @Synchronized
    fun useUser(user: String?) {
        val key = keyFor(user)
        if (key == userKey) return
        flush()
        userKey = key
        words = load(key)
    }

    /** Counts one use of each word in [text] (a single word or a dictated sentence). */
    @Synchronized
    fun count(text: String, nowSec: Long = System.currentTimeMillis() / 1000L) {
        for (raw in WORD.findAll(text)) {
            val w = raw.value.lowercase().trim('\'')
            if (w.length < MIN_LENGTH || w.length > MAX_LENGTH) continue
            val e = words[w]
            if (e == null) {
                words[w] = Entry(1.0, nowSec)
            } else {
                e.score = decayed(e.score, e.lastUsed, nowSec) + 1.0
                e.lastUsed = nowSec
            }
            dirty++
        }
        if (words.size > MAX_WORDS) prune(nowSec)
        if (dirty >= SAVE_EVERY || (dirty > 0 && nowSec - lastSaveSec > SAVE_AFTER_SEC)) flush(nowSec)
    }

    /** The decayed use count of [word] (lowercase). */
    @Synchronized
    fun scoreOf(word: String, nowSec: Long = System.currentTimeMillis() / 1000L): Double =
        words[word.lowercase()]?.let { decayed(it.score, it.lastUsed, nowSec) } ?: 0.0

    /**
     * Words starting with [prefix] (lowercase) used at least [MIN_USES] times, most used first, with the
     * strip rank each has earned ([rankFor]).
     */
    @Synchronized
    fun frequentStartingWith(
        prefix: String,
        limit: Int,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): List<Pair<String, Int>> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        return words.asSequence()
            .filter { (w, _) -> w.length > p.length && w.startsWith(p) }
            .map { (w, e) -> w to decayed(e.score, e.lastUsed, nowSec) }
            .filter { it.second >= MIN_USES }
            .sortedByDescending { it.second }
            .take(limit)
            .map { (w, s) -> w to rankFor(s) }
            .toList()
    }

    /** Every word with its (undecayed) score and last use, for the profile sync. */
    @Synchronized
    fun export(): Map<String, Pair<Double, Long>> = words.mapValues { (_, e) -> e.score to e.lastUsed }

    /**
     * Merges counts from another device: per word the larger of the two decayed scores, with the later
     * last use. Never adds: the same use may already be counted on both.
     */
    @Synchronized
    fun merge(remote: Map<String, Pair<Double, Long>>, nowSec: Long = System.currentTimeMillis() / 1000L) {
        for ((w, v) in remote) {
            val word = w.lowercase()
            if (word.length < MIN_LENGTH || word.length > MAX_LENGTH || !v.first.isFinite() || v.first <= 0) continue
            val theirs = decayed(v.first, v.second, nowSec)
            val e = words[word]
            if (e == null) {
                words[word] = Entry(v.first, v.second)
            } else {
                val mine = decayed(e.score, e.lastUsed, nowSec)
                if (theirs > mine) { e.score = theirs; e.lastUsed = nowSec }
            }
            dirty++
        }
        if (words.size > MAX_WORDS) prune(nowSec)
        flush(nowSec)
    }

    /**
     * Writes a merged profile's counts back: each word becomes its merged score plus whatever it gained
     * here while the sync ran (since [atMerge] was read). Words the merge does not mention stay.
     */
    @Synchronized
    fun applyMerged(
        merged: Map<String, Pair<Double, Long>>,
        atMerge: Map<String, Pair<Double, Long>>,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ) {
        for ((w, m) in merged) {
            val cur = words[w]?.let { decayed(it.score, it.lastUsed, nowSec) } ?: 0.0
            val before = atMerge[w]?.let { decayed(it.first, it.second, nowSec) } ?: 0.0
            val score = decayed(m.first, m.second, nowSec) + maxOf(0.0, cur - before)
            words[w] = Entry(score, nowSec)
            dirty++
        }
        if (words.size > MAX_WORDS) prune(nowSec)
        flush(nowSec)
    }

    @Synchronized
    fun clear() {
        words = HashMap()
        dirty = 0
        userKey?.let { fileFor(it)?.delete() }
    }

    @Synchronized
    fun flush(nowSec: Long = System.currentTimeMillis() / 1000L) {
        lastSaveSec = nowSec
        if (dirty == 0) return
        dirty = 0
        val file = userKey?.let { fileFor(it) } ?: return
        try {
            val sb = StringBuilder("{")
            var first = true
            for ((w, e) in words) {
                if (!first) sb.append(',')
                first = false
                sb.append('"').append(w.replace("\\", "").replace("\"", "")).append("\":[")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", e.score)).append(',').append(e.lastUsed).append(']')
            }
            sb.append('}')
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(sb.toString())
            tmp.renameTo(file)
        } catch (_: Exception) {
            // Best effort: counts are a ranking hint, never worth crashing the keyboard for.
        }
    }

    private fun prune(nowSec: Long) {
        val keep = words.entries
            .sortedByDescending { decayed(it.value.score, it.value.lastUsed, nowSec) }
            .take(PRUNE_TO)
        words = HashMap<String, Entry>().apply { keep.forEach { put(it.key, it.value) } }
    }

    private fun load(key: String): HashMap<String, Entry> {
        val out = HashMap<String, Entry>()
        val text = try { fileFor(key)?.takeIf { it.isFile }?.readText() } catch (_: Exception) { null } ?: return out
        for (m in ENTRY.findAll(text)) {
            val score = m.groupValues[2].toDoubleOrNull() ?: continue
            val last = m.groupValues[3].toLongOrNull() ?: continue
            out[m.groupValues[1]] = Entry(score, last)
        }
        return out
    }

    private fun fileFor(key: String): File? = dir?.let { File(it.apply { mkdirs() }, "words_$key.json") }

    companion object {
        const val HALF_LIFE_DAYS = 30.0
        /** Uses needed before a word is pushed up the strip. */
        const val MIN_USES = 3.0
        private const val MIN_LENGTH = 2
        private const val MAX_LENGTH = 32
        private const val MAX_WORDS = 6000
        private const val PRUNE_TO = 4000
        private const val SAVE_EVERY = 25
        private const val SAVE_AFTER_SEC = 60L

        /** Below the very top of the dictionary scale (255), above where the learned words sit (≤205). */
        private const val RANK_BASE = 196
        private const val RANK_MAX = 250
        private const val RANK_PER_LN = 12.0

        private val WORD = Regex("[\\p{L}][\\p{L}\\p{M}'’-]*")
        private val ENTRY = Regex("\"([^\"]+)\":\\[([0-9.]+),([0-9]+)]")

        /** 3 uses → 209, 10 → 223, 30 → 236, 100 → 251 → capped at 250. */
        fun rankFor(score: Double): Int =
            (RANK_BASE + RANK_PER_LN * ln(score.coerceAtLeast(1.0))).toInt().coerceIn(RANK_BASE, RANK_MAX)

        fun decayed(score: Double, lastUsed: Long, nowSec: Long): Double {
            val days = ((nowSec - lastUsed).coerceAtLeast(0)) / 86_400.0
            return score * 0.5.pow(days / HALF_LIFE_DAYS)
        }

        /** A file name that does not spell out the person's id. */
        fun keyFor(user: String?): String {
            val id = user?.trim()?.lowercase().orEmpty().ifEmpty { "default" }
            val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

/** The one [PersonalWordCounts] of the app, switched to whoever is the current person. */
object PersonalWords {
    @Volatile private var instance: PersonalWordCounts? = null

    fun get(context: android.content.Context): PersonalWordCounts =
        instance ?: synchronized(this) {
            instance ?: PersonalWordCounts(File(context.applicationContext.filesDir, "vk_learning")).also { instance = it }
        }

    /** The counts of the person selected in the Voice Keyboard settings. */
    fun current(context: android.content.Context, prefs: dev.patrickgold.florisboard.app.FlorisPreferenceModel): PersonalWordCounts =
        get(context).apply { useUser(dev.patrickgold.florisboard.vk.learning.VkIdentity.currentUser(prefs)) }
}
