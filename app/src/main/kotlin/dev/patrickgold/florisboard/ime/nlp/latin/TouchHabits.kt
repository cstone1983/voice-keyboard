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
import kotlin.math.abs

/**
 * Voice Keyboard: where this person's touches land on each letter key, relative to its centre, so a
 * touch is read as the key it was aimed at (someone who always hits the right edge of "v" gets "v", not
 * "b"). Learned from finished words: the letters of the word that stood (typed as is, or what autocorrect
 * turned it into) against where the taps for them landed. Separate for the split and the normal
 * keyboard, because thumbs reach differently on each.
 *
 * Offsets are in key widths. A key needs [MIN_SAMPLES] samples before its offset is used, and an offset
 * is capped at [MAX_SHIFT_X]/[MAX_SHIFT_Y] so a bad stretch of typing cannot move a key far.
 */
class TouchHabitModel {
    /** "s:v" / "n:v" → (dx, dy, samples). */
    internal val keys = HashMap<String, FloatArray>()
    var changes = 0
        internal set

    /** Learns from one finished word: [intended] letters, [points] the taps (`x0,y0,x1,y1…`, key widths). */
    @Synchronized
    fun learn(intended: String, points: FloatArray, layout: KeyProximityInfo.Layout, split: Boolean) {
        if (intended.length < 2 || points.size != intended.length * 2) return
        for (i in intended.indices) {
            val c = intended[i].lowercaseChar()
            if (!c.isLetter()) continue
            val px = points[i * 2]
            val py = points[i * 2 + 1]
            if (px.isNaN() || py.isNaN()) continue
            val idx = layout.indexOf(c)
            if (idx < 0) continue
            val dx = px - layout.xAt(idx)
            val dy = py - layout.yAt(idx)
            // Further than a key and a bit from the key meant: not an aim, a different word.
            if (dx * dx + dy * dy > MAX_SAMPLE_DIST * MAX_SAMPLE_DIST) continue
            val e = keys.getOrPut(keyOf(split, c)) { FloatArray(3) }
            val n = (e[2] + 1f).coerceAtMost(WINDOW)
            e[0] += (dx - e[0]) / n
            e[1] += (dy - e[1]) / n
            e[2] = n
            changes++
        }
    }

    /** The learned offset of [c]'s key, in key widths, or null while it has too few samples. */
    @Synchronized
    fun offsetOf(c: Char, split: Boolean): FloatArray? {
        val e = keys[keyOf(split, c.lowercaseChar())] ?: return null
        if (e[2] < MIN_SAMPLES) return null
        return floatArrayOf(e[0].coerceIn(-MAX_SHIFT_X, MAX_SHIFT_X), e[1].coerceIn(-MAX_SHIFT_Y, MAX_SHIFT_Y))
    }

    /**
     * Which of the candidate keys a touch at ([x], [y]) was aimed at: the one whose *learned* aim point
     * is nearest. [centers] is `cx0, cy0, …` for [chars], all in key widths. Returns the index into
     * [chars], or -1 to keep the key that was hit ([hit]) — also when the gain is too small to matter.
     */
    @Synchronized
    fun pick(x: Float, y: Float, chars: CharArray, centers: FloatArray, hit: Int, split: Boolean): Int {
        if (hit < 0) return -1
        var best = -1
        var bestD = Float.MAX_VALUE
        var hitD = Float.MAX_VALUE
        var anyLearned = false
        for (i in chars.indices) {
            val off = offsetOf(chars[i], split)
            if (off != null) anyLearned = true
            val ex = centers[i * 2] + (off?.get(0) ?: 0f)
            val ey = centers[i * 2 + 1] + (off?.get(1) ?: 0f)
            val d = (x - ex) * (x - ex) + (y - ey) * (y - ey)
            if (i == hit) hitD = d
            if (d < bestD) { bestD = d; best = i }
        }
        if (!anyLearned || best == hit || best < 0) return -1
        return if (hitD - bestD > MIN_GAIN) best else -1
    }

    @Synchronized
    fun clear() { keys.clear(); changes++ }

    /** Merges another device's model: per key, the one with more samples. */
    @Synchronized
    fun merge(remoteJson: String) {
        val other = TouchHabitModel().apply { loadJson(remoteJson) }
        for ((k, v) in other.keys) {
            val mine = keys[k]
            if (mine == null || v[2] > mine[2]) { keys[k] = v.copyOf(); changes++ }
        }
    }

    @Synchronized
    fun toJson(): String = keys.entries.joinToString(",", "{", "}") { (k, v) ->
        "\"$k\":[${fmt(v[0])},${fmt(v[1])},${fmt(v[2])}]"
    }

    @Synchronized
    fun loadJson(text: String) {
        keys.clear()
        for (m in ENTRY.findAll(text)) {
            val a = m.groupValues[2].toFloatOrNull() ?: continue
            val b = m.groupValues[3].toFloatOrNull() ?: continue
            val n = m.groupValues[4].toFloatOrNull() ?: continue
            if (abs(a) > 2f || abs(b) > 2f) continue
            keys[m.groupValues[1]] = floatArrayOf(a, b, n.coerceIn(0f, WINDOW))
        }
    }

    companion object {
        const val MIN_SAMPLES = 8f
        /** Old samples fade: the mean is over roughly the last this many. */
        const val WINDOW = 60f
        const val MAX_SHIFT_X = 0.35f
        const val MAX_SHIFT_Y = 0.30f
        private const val MAX_SAMPLE_DIST = 1.2f
        /** Squared key widths the learned key must be nearer by before it overrides the key hit. */
        private const val MIN_GAIN = 0.02f
        private val ENTRY = Regex("\"([sn]:.)\":\\[(-?[0-9.]+),(-?[0-9.]+),([0-9.]+)]")

        fun keyOf(split: Boolean, c: Char) = (if (split) "s:" else "n:") + c
        private fun fmt(f: Float) = String.format(java.util.Locale.ROOT, "%.4f", f)
    }
}

/** The app's [TouchHabitModel] for the selected person, saved in `files/vk_learning/touch_<hash>.json`. */
object TouchHabits {
    private val prefs by dev.patrickgold.florisboard.app.FlorisPreferenceStore
    @Volatile private var dir: File? = null
    @Volatile private var loadedFor: String? = null
    @Volatile private var model = TouchHabitModel()
    private var savedAt = 0

    fun attach(context: android.content.Context) {
        if (dir == null) dir = File(context.applicationContext.filesDir, "vk_learning")
    }

    fun enabled(): Boolean = prefs.keyboard.vkTouchHabits.get()

    /** Whether the keyboard last touched was split; set on every letter touch. */
    @Volatile var lastSplit: Boolean = false

    /**
     * Learns from a finished word: [intended] is what stands in the text, [typed] what the taps spelled
     * (the same word, or what autocorrect replaced). Only same-length pairs: then letter i was aimed at
     * intended[i].
     */
    fun learnWord(intended: String, typed: String) {
        if (!enabled() || intended.length != typed.length) return
        val pts = TouchTrace.pointsFor(typed) ?: return
        val layout = KeyProximityInfo.snapshot() ?: return
        current().learn(intended, pts, layout, lastSplit)
    }

    /** The selected person's model (loads it when the person changed). */
    @Synchronized
    fun current(): TouchHabitModel {
        val who = dev.patrickgold.florisboard.vk.learning.VkPersonFiles.personKey() ?: "default"
        if (who != loadedFor) {
            save()
            model = TouchHabitModel().also { m ->
                runCatching { fileFor(who)?.takeIf { it.isFile }?.readText() }.getOrNull()?.let { m.loadJson(it) }
            }
            savedAt = model.changes
            loadedFor = who
        }
        return model
    }

    /** Saves when enough changed since the last save. */
    @Synchronized
    fun saveIfDue() {
        if (model.changes - savedAt >= 40) save()
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

    /** The touch habits learned while nobody was selected, as JSON ("" when there are none). */
    fun unassignedJson(): String =
        runCatching { fileFor("default")?.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()

    private fun fileFor(who: String): File? = dir?.let { File(it.apply { mkdirs() }, "touch_$who.json") }
}
