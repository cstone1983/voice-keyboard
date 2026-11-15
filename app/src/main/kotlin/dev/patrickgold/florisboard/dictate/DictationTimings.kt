/*
 * Copyright (C) 2026 Voice Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Voice Keyboard: how long dictations take, for the timing screen and for `adb logcat -s VKTiming`.
 * Only durations and counts are kept — never any text. In memory only; the last [KEEP] dictations.
 */
object DictationTimings {
    const val LOG_TAG = "VKTiming"
    private const val KEEP = 20

    data class Chunk(
        val index: Int,
        /** Length of the chunk's audio. */
        val audioMs: Long,
        /** Time of the successful (or last) request, without waiting in the queue. */
        val transcribeMs: Long,
        val attempts: Int,
        val ok: Boolean,
    )

    data class Entry(
        val finishedAtMillis: Long,
        val chunked: Boolean,
        val recordedMs: Long,
        val chunks: List<Chunk>,
        /** From "stop" until the last chunk (or the single upload) was transcribed. */
        val transcribeAfterStopMs: Long,
        /** Cleanup (rewording) and insertion, after transcription. */
        val finalizeMs: Long,
        /** From "stop" until the final text was in the field. */
        val stopToTextMs: Long,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val chunks = mutableListOf<Chunk>()
    @Volatile private var stopNanos = 0L

    /** A new dictation starts recording. */
    @Synchronized
    fun begin() {
        chunks.clear()
        stopNanos = 0L
    }

    /** The user pressed stop. */
    fun markStop() {
        stopNanos = SystemClock.elapsedRealtimeNanos()
    }

    @Synchronized
    fun addChunk(chunk: Chunk) {
        chunks.add(chunk)
        Log.i(
            LOG_TAG,
            "chunk=${chunk.index} audioMs=${chunk.audioMs} transcribeMs=${chunk.transcribeMs} " +
                "attempts=${chunk.attempts} ok=${chunk.ok}",
        )
    }

    /** The final text is in the field. No-op unless a stop was marked (replays and resends are not timed). */
    @Synchronized
    fun finish(chunked: Boolean, recordedSeconds: Long, finalizeStartedNanos: Long) {
        val stop = stopNanos
        if (stop == 0L) return
        stopNanos = 0L
        val now = SystemClock.elapsedRealtimeNanos()
        val entry = Entry(
            finishedAtMillis = System.currentTimeMillis(),
            chunked = chunked,
            recordedMs = recordedSeconds * 1000,
            chunks = chunks.sortedBy { it.index },
            transcribeAfterStopMs = ((finalizeStartedNanos - stop) / 1_000_000).coerceAtLeast(0),
            finalizeMs = (now - finalizeStartedNanos) / 1_000_000,
            stopToTextMs = (now - stop) / 1_000_000,
        )
        chunks.clear()
        _entries.value = (listOf(entry) + _entries.value).take(KEEP)
        dev.patrickgold.florisboard.vk.learning.VkLearning.attachTimings(entry)
        Log.i(
            LOG_TAG,
            "done mode=${if (chunked) "chunked" else "single"} recordedS=$recordedSeconds chunks=${entry.chunks.size} " +
                "transcribeAfterStopMs=${entry.transcribeAfterStopMs} finalizeMs=${entry.finalizeMs} " +
                "stopToTextMs=${entry.stopToTextMs} chunkMs=${entry.chunks.map { it.transcribeMs }}",
        )
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /** Audio length of a 16-bit mono 16 kHz WAV from its size. */
    fun wavDurationMs(bytes: Long): Long = ((bytes - 44).coerceAtLeast(0) / 32)
}
