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

/**
 * Voice Keyboard: what this person's own corrections taught the keyboard.
 *
 *  - **Fixes**: a word typed and then changed by hand ("thw" → "the", "thisbis" → "this is"). Offered in
 *    the strip after one sighting; corrected automatically from [AUTO_AFTER] sightings, when the typed
 *    word is not a word of its own.
 *  - **Blocked**: an auto-correction the person took back ("typed" → "wrong"). Never applied again.
 *  - **Key mix-ups**: how often a tap meant for one key landed on another ("b" for "v"). Autocorrect
 *    treats such a pair as nearer for this person.
 *
 * Only words and letter pairs are kept, never text. One file per person (see [PersonalFixes]).
 */
class PersonalFixModel {
    data class Fix(val typo: String, val fix: String, val count: Int, val lastUsed: Long)

    internal val fixes = HashMap<String, Fix>()          // key(typo, fix)
    internal val blocked = HashMap<String, Long>()       // key(typed, wrong) -> when
    internal val confusion = HashMap<String, Int>()      // "b>v" -> count
    var changes = 0
        internal set

    @Synchronized
    fun noteFix(typo: String, fix: String, nowSec: Long = now()) {
        val t = typo.lowercase().trim()
        val f = fix.trim()
        if (t.isEmpty() || f.isEmpty() || t == f.lowercase()) return
        // Changing it back undoes the opposite lesson.
        fixes.remove(key(f.lowercase(), t))
        val k = key(t, f.lowercase())
        val old = fixes[k]
        fixes[k] = Fix(t, f, (old?.count ?: 0) + 1, nowSec)
        if (fixes.size > MAX_FIXES) prune()
        changes++
    }

    @Synchronized
    fun noteBlocked(typed: String, wrong: String, nowSec: Long = now()) {
        val t = typed.lowercase().trim()
        val w = wrong.lowercase().trim()
        if (t.isEmpty() || w.isEmpty() || t == w) return
        blocked[key(t, w)] = nowSec
        fixes.remove(key(t, w))
        if (blocked.size > MAX_BLOCKED) blocked.entries.sortedBy { it.value }.take(blocked.size - MAX_BLOCKED).forEach { blocked.remove(it.key) }
        changes++
    }

    /** Times a correction was taken back and the typed word then kept (local; see [noteRejection]). */
    internal val rejections = HashMap<String, Int>()

    /**
     * The person took the correction [typed] → [wrong] back and kept what they typed. Blocked from the
     * [BLOCK_AFTER]th time: a backspace right after a correction is also how people delete the space
     * or keep going, so one is not enough to say "never again".
     */
    @Synchronized
    fun noteRejection(typed: String, wrong: String, nowSec: Long = now()) {
        val k = key(typed.lowercase().trim(), wrong.lowercase().trim())
        val n = (rejections[k] ?: 0) + 1
        rejections[k] = n
        changes++
        if (n >= BLOCK_AFTER) noteBlocked(typed, wrong, nowSec)
    }

    @Synchronized
    fun isBlocked(typed: String, wrong: String): Boolean = blocked.containsKey(key(typed.lowercase(), wrong.lowercase()))

    /** The best learned fix for [typo] (most sightings), or null. */
    @Synchronized
    fun fixFor(typo: String): Fix? {
        val t = typo.lowercase()
        return fixes.values.filter { it.typo == t && !isBlocked(t, it.fix) }.maxByOrNull { it.count }
    }

    @Synchronized
    fun noteConfusion(from: Char, to: Char) {
        val a = from.lowercaseChar()
        val b = to.lowercaseChar()
        if (a == b) return
        val k = "$a>$b"
        confusion[k] = ((confusion[k] ?: 0) + 1).coerceAtMost(CONFUSION_CAP)
        changes++
    }

    /** Letters swapped at equal positions of a same-length fix: those are key mix-ups. */
    @Synchronized
    fun noteConfusionsOf(typo: String, fix: String) {
        if (typo.length != fix.length) return
        var diffs = 0
        for (i in typo.indices) if (!typo[i].equals(fix[i], ignoreCase = true)) diffs++
        if (diffs == 0 || diffs > 2) return
        for (i in typo.indices) if (!typo[i].equals(fix[i], ignoreCase = true)) noteConfusion(typo[i], fix[i])
    }

    /** How often [typed] was hit when [meant] was meant (either way round counts a little). */
    @Synchronized
    fun confusionOf(typed: Char, meant: Char): Int =
        (confusion["${typed.lowercaseChar()}>${meant.lowercaseChar()}"] ?: 0)

    /**
     * Multiplier for the squared key distance between [a] and [b]: 1 for a pair never mixed up, down to
     * [MIN_DISTANCE_FACTOR] for one mixed up often, so autocorrect reads it as a near slip.
     */
    fun distanceFactor(a: Char, b: Char): Float {
        val n = confusionOf(a, b) + confusionOf(b, a) / 2
        if (n <= 0) return 1f
        return (1f / (1f + n / 4f)).coerceAtLeast(MIN_DISTANCE_FACTOR)
    }

    @Synchronized
    fun removeFix(typo: String, fix: String) { fixes.remove(key(typo.lowercase(), fix.lowercase())); changes++ }

    @Synchronized
    fun unblock(typed: String, wrong: String) {
        val k = key(typed.lowercase(), wrong.lowercase())
        blocked.remove(k); rejections.remove(k); changes++
    }

    @Synchronized
    fun clear() { fixes.clear(); blocked.clear(); confusion.clear(); rejections.clear(); changes++ }

    @Synchronized
    fun allFixes(): List<Fix> = fixes.values.sortedWith(compareByDescending<Fix> { it.count }.thenByDescending { it.lastUsed })

    @Synchronized
    fun allBlocked(): List<Pair<String, String>> = blocked.entries.sortedByDescending { it.value }.map { split(it.key) }

    @Synchronized
    fun topConfusions(n: Int): List<Pair<String, Int>> = confusion.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

    // ---- Export / import, for the profile sync and the file ----

    @Synchronized
    fun exportFixes(): Map<String, Fix> = HashMap(fixes)

    @Synchronized
    fun exportBlocked(): Map<String, Long> = HashMap(blocked)

    @Synchronized
    fun exportConfusion(): Map<String, Int> = HashMap(confusion)

    @Synchronized
    fun replaceAll(f: Map<String, Fix>, b: Map<String, Long>, c: Map<String, Int>) {
        fixes.clear(); fixes.putAll(f)
        blocked.clear(); blocked.putAll(b)
        confusion.clear(); confusion.putAll(c)
        changes++
    }

    @Synchronized
    fun toJson(): String {
        val sb = StringBuilder("{\"fixes\":[")
        fixes.values.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append('[').append(q(f.typo)).append(',').append(q(f.fix)).append(',').append(f.count).append(',').append(f.lastUsed).append(']')
        }
        sb.append("],\"blocked\":[")
        blocked.entries.forEachIndexed { i, (k, t) ->
            if (i > 0) sb.append(',')
            val (a, b) = split(k)
            sb.append('[').append(q(a)).append(',').append(q(b)).append(',').append(t).append(']')
        }
        sb.append("],\"rejections\":{")
        rejections.entries.forEachIndexed { i, (k, n) ->
            if (i > 0) sb.append(',')
            sb.append(q(k)).append(':').append(n)
        }
        sb.append("},\"confusion\":{")
        confusion.entries.forEachIndexed { i, (k, n) ->
            if (i > 0) sb.append(',')
            sb.append(q(k)).append(':').append(n)
        }
        return sb.append("}}").toString()
    }

    @Synchronized
    fun loadJson(text: String) {
        fixes.clear(); blocked.clear(); confusion.clear()
        val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }
            .getOrNull() ?: return
        PersonalFixCodec.readFixes(obj["fixes"])?.let { fixes.putAll(it) }
        PersonalFixCodec.readBlocked(obj["blocked"])?.let { blocked.putAll(it) }
        PersonalFixCodec.readConfusion(obj["confusion"])?.let { confusion.putAll(it) }
        (obj["rejections"] as? kotlinx.serialization.json.JsonObject)?.forEach { (k, v) ->
            (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.let { rejections[k] = it }
        }
    }

    private fun prune() {
        fixes.values.sortedWith(compareBy<Fix> { it.count }.thenBy { it.lastUsed })
            .take(fixes.size - MAX_FIXES)
            .forEach { fixes.remove(key(it.typo, it.fix.lowercase())) }
    }

    companion object {
        /** Sightings of the same fix before it is applied without asking. */
        const val AUTO_AFTER = 2
        /** Takebacks of the same correction before it is never made again. */
        const val BLOCK_AFTER = 2
        const val MAX_FIXES = 2000
        const val MAX_BLOCKED = 2000
        private const val CONFUSION_CAP = 1000
        private const val MIN_DISTANCE_FACTOR = 0.35f
        private const val SEP = '\u0001'

        fun key(a: String, b: String) = "$a$SEP$b"
        fun split(k: String): Pair<String, String> = k.substringBefore(SEP) to k.substringAfter(SEP)
        private fun now() = System.currentTimeMillis() / 1000L
        private fun q(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
    }
}

/** Reading the fix sections of a profile or file (shared with the profile sync). */
object PersonalFixCodec {
    private fun str(e: kotlinx.serialization.json.JsonElement?) =
        (e as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    private fun long(e: kotlinx.serialization.json.JsonElement?) =
        (e as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

    /** `[[typo, fix, count, last_used]]` → by key. */
    fun readFixes(e: kotlinx.serialization.json.JsonElement?): Map<String, PersonalFixModel.Fix>? {
        val arr = e as? kotlinx.serialization.json.JsonArray ?: return null
        val out = HashMap<String, PersonalFixModel.Fix>()
        for (el in arr) {
            val a = el as? kotlinx.serialization.json.JsonArray ?: continue
            val typo = str(a.getOrNull(0))?.lowercase()?.takeIf { it.isNotBlank() } ?: continue
            val fix = str(a.getOrNull(1))?.takeIf { it.isNotBlank() } ?: continue
            val count = long(a.getOrNull(2))?.toInt() ?: continue
            val last = long(a.getOrNull(3)) ?: 0L
            out[PersonalFixModel.key(typo, fix.lowercase())] = PersonalFixModel.Fix(typo, fix, count.coerceAtLeast(1), last)
        }
        return out
    }

    /** `[[typed, wrong, when]]` → by key. */
    fun readBlocked(e: kotlinx.serialization.json.JsonElement?): Map<String, Long>? {
        val arr = e as? kotlinx.serialization.json.JsonArray ?: return null
        val out = HashMap<String, Long>()
        for (el in arr) {
            val a = el as? kotlinx.serialization.json.JsonArray ?: continue
            val t = str(a.getOrNull(0))?.lowercase()?.takeIf { it.isNotBlank() } ?: continue
            val w = str(a.getOrNull(1))?.lowercase()?.takeIf { it.isNotBlank() } ?: continue
            out[PersonalFixModel.key(t, w)] = long(a.getOrNull(2)) ?: 0L
        }
        return out
    }

    /** `{"b>v": n}`. */
    fun readConfusion(e: kotlinx.serialization.json.JsonElement?): Map<String, Int>? {
        val obj = e as? kotlinx.serialization.json.JsonObject ?: return null
        val out = HashMap<String, Int>()
        for ((k, v) in obj) {
            if (k.length != 3 || k[1] != '>') continue
            val n = long(v)?.toInt() ?: continue
            if (n > 0) out[k] = n
        }
        return out
    }
}

/** The selected person's [PersonalFixModel], saved in `files/vk_learning/fixes_<hash>.json`. */
object PersonalFixes {
    @Volatile private var dir: File? = null
    @Volatile private var loadedFor: String? = null
    @Volatile private var model = PersonalFixModel()
    private var savedAt = 0

    fun attach(context: android.content.Context) {
        if (dir == null) dir = File(context.applicationContext.filesDir, "vk_learning")
    }

    @Synchronized
    fun current(): PersonalFixModel {
        val who = dev.patrickgold.florisboard.vk.learning.VkPersonFiles.personKey() ?: "default"
        if (who != loadedFor) {
            save()
            model = PersonalFixModel().also { m ->
                runCatching { fileFor(who)?.takeIf { it.isFile }?.readText() }.getOrNull()?.let { m.loadJson(it) }
            }
            savedAt = model.changes
            loadedFor = who
        }
        return model
    }

    @Synchronized
    fun save() {
        val who = loadedFor ?: return
        if (model.changes == savedAt) return
        savedAt = model.changes
        runCatching {
            val f = fileFor(who) ?: return
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(model.toJson())
            tmp.renameTo(f)
        }
    }

    @Synchronized
    fun clear() {
        current().clear()
        loadedFor?.let { fileFor(it)?.delete() }
        savedAt = model.changes
    }

    private fun fileFor(who: String): File? = dir?.let { File(it.apply { mkdirs() }, "fixes_$who.json") }
}
