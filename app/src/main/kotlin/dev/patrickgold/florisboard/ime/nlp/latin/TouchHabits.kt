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
    fun learn(
        intended: String,
        points: FloatArray,
        layout: KeyProximityInfo.Layout,
        split: Boolean,
        drawnShift: (Char) -> FloatArray? = { null },
    ) {
        if (intended.length < 2 || points.size != intended.length * 2) return
        for (i in intended.indices) {
            val c = intended[i].lowercaseChar()
            // Letters, and the space bar (a space typed as a letter, "thisbis" for "this is").
            if (!c.isLetter() && c != ' ') continue
            val px = points[i * 2]
            val py = points[i * 2 + 1]
            if (px.isNaN() || py.isNaN()) continue
            val idx = layout.indexOf(c)
            if (idx < 0) continue
            // Relative to where the key is drawn: a reshaped key must not teach its own reshaping.
            val shift = drawnShift(c)
            val dx = px - layout.xAt(idx) - (shift?.get(0) ?: 0f)
            val dy = py - layout.yAt(idx) - (shift?.get(1) ?: 0f)
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

    /** Learns one tap at ([x], [y]) (key widths) that was meant for [meant] (a backspaced-over slip). */
    @Synchronized
    fun learnTap(
        meant: Char,
        x: Float,
        y: Float,
        layout: KeyProximityInfo.Layout,
        split: Boolean,
        drawnShift: (Char) -> FloatArray? = { null },
    ) {
        val c = meant.lowercaseChar()
        if (!c.isLetter() && c != ' ') return
        val idx = layout.indexOf(c)
        if (idx < 0 || x.isNaN() || y.isNaN()) return
        val shift = drawnShift(c)
        val dx = x - layout.xAt(idx) - (shift?.get(0) ?: 0f)
        val dy = y - layout.yAt(idx) - (shift?.get(1) ?: 0f)
        if (dx * dx + dy * dy > MAX_SAMPLE_DIST * MAX_SAMPLE_DIST) return
        val e = keys.getOrPut(keyOf(split, c)) { FloatArray(3) }
        // A slip says more than a hit: it counts as two samples.
        repeat(2) {
            val n = (e[2] + 1f).coerceAtMost(WINDOW)
            e[0] += (dx - e[0]) / n
            e[1] += (dy - e[1]) / n
            e[2] = n
        }
        changes++
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
        const val MIN_SAMPLES = 12f
        /** Old samples fade: the mean is over roughly the last this many. */
        const val WINDOW = 60f
        const val MAX_SHIFT_X = 0.30f
        const val MAX_SHIFT_Y = 0.22f
        private const val MAX_SAMPLE_DIST = 1.2f
        /** Squared key widths the learned key must be nearer by before it overrides the key hit. */
        private const val MIN_GAIN = 0.06f
        private val ENTRY = Regex("\"([sn]:.)\":\\[(-?[0-9.]+),(-?[0-9.]+),([0-9.]+)]")

        fun keyOf(split: Boolean, c: Char) = (if (split) "s:" else "n:") + c
        private fun fmt(f: Float) = String.format(java.util.Locale.ROOT, "%.4f", f)
    }
}

/** The app's [TouchHabitModel] for the selected person, saved in `files/vk_learning/touch2_<hash>.json` (v2: the v1 data learned from habit-corrected points and is not used). */
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

    // ---- What the person sees: the touch map and the reshaped keys --------------------------------

    /** The last layout of each kind (split / normal), for the touch map. */
    @Volatile private var layoutSplit: KeyProximityInfo.Layout? = null
    @Volatile private var layoutNormal: KeyProximityInfo.Layout? = null

    /** The keyboard was laid out: remember its geometry for the touch map. */
    fun noteLayout(split: Boolean) {
        val l = KeyProximityInfo.snapshot() ?: return
        if (split) layoutSplit = l else layoutNormal = l
    }

    fun layoutFor(split: Boolean): KeyProximityInfo.Layout? = if (split) layoutSplit else layoutNormal

    /**
     * Where each key is *drawn*, nudged towards where this person aims ("s:v" → dx, dy in key widths).
     * Taken only when the keyboard opens ([freezeVisual]), so keys never move under the fingers while
     * typing. Drawing only: which key a touch belongs to is decided as before.
     */
    private val _visualOffsets = kotlinx.coroutines.flow.MutableStateFlow<Map<String, FloatArray>>(emptyMap())
    val visualOffsets: kotlinx.coroutines.flow.StateFlow<Map<String, FloatArray>> = _visualOffsets

    /** The drawn shift of [c]'s key now, in key widths (null when keys are not reshaped). */
    fun drawnShiftOf(c: Char, split: Boolean): FloatArray? =
        _visualOffsets.value[(if (split) "s:" else "n:") + c.lowercaseChar()]

    fun reshapeEnabled(): Boolean = prefs.keyboard.vkReshapeKeys.get() && enabled()

    /** The keyboard opened: take the current habits as the key shapes until it opens again. */
    fun freezeVisual() {
        if (!reshapeEnabled()) {
            if (_visualOffsets.value.isNotEmpty()) _visualOffsets.value = emptyMap()
            return
        }
        val m = current()
        val out = HashMap<String, FloatArray>()
        synchronized(m) {
            for ((k, _) in m.keys) {
                val split = k.startsWith("s:")
                val c = k.substringAfter(':').firstOrNull() ?: continue
                val off = m.offsetOf(c, split) ?: continue
                out[k] = floatArrayOf(
                    (off[0] * VISUAL_SCALE).coerceIn(-VISUAL_MAX, VISUAL_MAX),
                    (off[1] * VISUAL_SCALE).coerceIn(-VISUAL_MAX, VISUAL_MAX),
                )
            }
        }
        _visualOffsets.value = out
    }

    /** Keys are drawn this share of the way to the learned aim point, at most [VISUAL_MAX] key widths. */
    private const val VISUAL_SCALE = 0.6f
    private const val VISUAL_MAX = 0.15f

    /** Whether the keyboard last touched was split; set on every letter touch. */
    @Volatile var lastSplit: Boolean = false

    /**
     * Learns from the slips fixed inside [word] by backspacing ([TouchTrace.repairsFor]): each such tap
     * was meant for the letter typed in its place. Also counts them as the person's key mix-ups.
     */
    fun learnRepairs(word: String) {
        val repairs = TouchTrace.repairsFor(word)
        if (repairs.isEmpty() || repairs.size > maxOf(2, word.length / 2)) return
        val layout = KeyProximityInfo.snapshot()
        val m = current()
        val fixes = PersonalFixes.current()
        for (r in repairs) {
            val split = lastSplit
            if (enabled() && layout != null) m.learnTap(r.meant, r.x, r.y, layout, split) { c -> drawnShiftOf(c, split) }
            fixes.noteConfusion(r.tapped, r.meant)
        }
    }

    /**
     * Learns from a finished word: [intended] is what stands in the text, [typed] what the taps spelled
     * (the same word, or what autocorrect replaced). Only same-length pairs: then letter i was aimed at
     * intended[i].
     */
    fun learnWord(intended: String, typed: String) {
        if (!enabled() || intended.length != typed.length) return
        val pts = TouchTrace.pointsFor(typed) ?: return
        val layout = KeyProximityInfo.snapshot() ?: return
        val split = lastSplit
        current().learn(intended, pts, layout, split) { c -> drawnShiftOf(c, split) }
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

    private fun fileFor(who: String): File? = dir?.let { File(it.apply { mkdirs() }, "touch2_$who.json") }
}
