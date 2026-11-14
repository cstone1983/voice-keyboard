/*
 * Copyright (C) 2026 Voice Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.audio

/**
 * Voice Keyboard: where to cut a dictation into chunks, fed one VAD window at a time.
 *
 * - After speech, a pause of [silenceWindowsNeeded] silent windows ends the chunk, once the chunk is at
 *   least [minSegmentSamples] long. The cut goes into the middle of the silence, so both chunks keep some.
 * - A chunk reaching [maxSegmentSamples] is cut anyway, at the quietest window of the last second.
 * - A chunk without speech is never cut (it is skipped later as silence).
 *
 * Positions are sample counts since the recording started. [vadDelaySamples] is how much silence the VAD
 * has already swallowed before it reports a silent window (Silero's minimum silence).
 */
class SimpleCutPolicy(
    private val minSegmentSamples: Long,
    private val silenceWindowsNeeded: Int,
    private val maxSegmentSamples: Long,
    private val windowSamples: Int,
    private val vadDelaySamples: Int,
    ringWindows: Int,
) {
    private val ringEnergy = DoubleArray(ringWindows)
    private val ringEnd = LongArray(ringWindows)
    private var ringCount = 0
    private var ringNext = 0
    private var segmentStart = 0L
    private var hadSpeech = false
    private var silentRun = 0

    /** A cut was made elsewhere (manual "Next"): the next chunk starts at [at] and needs fresh speech. */
    fun reset(at: Long) {
        segmentStart = at
        hadSpeech = false
        silentRun = 0
        ringCount = 0
    }

    /**
     * One VAD window ending at sample [windowEnd]. Returns the sample to cut at, or null.
     */
    fun onWindow(speech: Boolean, energy: Double, windowEnd: Long): Long? {
        ringEnergy[ringNext] = energy
        ringEnd[ringNext] = windowEnd
        ringNext = (ringNext + 1) % ringEnergy.size
        if (ringCount < ringEnergy.size) ringCount++
        if (speech) {
            hadSpeech = true
            silentRun = 0
        } else {
            silentRun++
        }
        if (!hadSpeech) return null
        val length = windowEnd - segmentStart
        if (!speech && silentRun >= silenceWindowsNeeded && length >= minSegmentSamples) {
            val silence = silentRun.toLong() * windowSamples + vadDelaySamples
            val cut = (windowEnd - silence / 2).coerceAtLeast(segmentStart + 1)
            cutAt(cut, nextHasSpeech = false)
            return cut
        }
        if (maxSegmentSamples > 0 && length >= maxSegmentSamples) {
            var best = -1
            for (k in 0 until ringCount) {
                val i = (ringNext - 1 - k + ringEnergy.size) % ringEnergy.size
                if (ringEnd[i] - windowSamples / 2 <= segmentStart) continue
                if (best < 0 || ringEnergy[i] < ringEnergy[best]) best = i
            }
            val cut = if (best >= 0) ringEnd[best] - windowSamples / 2 else windowEnd
            cutAt(cut, nextHasSpeech = speech)
            return cut
        }
        return null
    }

    private fun cutAt(cut: Long, nextHasSpeech: Boolean) {
        segmentStart = cut
        hadSpeech = nextHasSpeech
        silentRun = 0
        ringCount = 0
    }
}
