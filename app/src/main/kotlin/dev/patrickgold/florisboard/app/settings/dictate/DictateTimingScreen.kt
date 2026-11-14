/*
 * Copyright (C) 2026 Voice Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.dictate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.dictate.DictationTimings
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import java.text.DateFormat
import java.util.Date

/** Voice Keyboard: timings of the last dictations (no text is ever kept). */
@Composable
fun DictateTimingScreen() = FlorisScreen {
    title = "Dictation timing"
    previewFieldVisible = true

    content {
        val entries by DictationTimings.entries.collectAsState()
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Stop to text: from tapping stop until the final text is in the field. Chunked dictation " +
                    "transcribes while you speak; single uploads everything after stop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entries.isEmpty()) {
                Text("No dictations timed yet.", modifier = Modifier.padding(vertical = 16.dp))
            } else {
                TextButton(onClick = { DictationTimings.clear() }) { Text("Clear") }
            }
            for (e in entries) {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "%.1f s stop to text".format(e.stopToTextMs / 1000f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${if (e.chunked) "Chunked" else "Single upload"} · ${e.recordedMs / 1000} s recorded · " +
                                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.finishedAtMillis)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Transcription after stop %.1f s · cleanup and insert %.1f s".format(
                                e.transcribeAfterStopMs / 1000f, e.finalizeMs / 1000f,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (e.chunks.isNotEmpty()) {
                            Text(
                                e.chunks.joinToString(" · ") { c ->
                                    "#${c.index + 1}: %.1f s audio → %.1f s%s".format(
                                        c.audioMs / 1000f, c.transcribeMs / 1000f,
                                        when {
                                            !c.ok -> " (failed)"
                                            c.attempts > 1 -> " (${c.attempts} tries)"
                                            else -> ""
                                        },
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
