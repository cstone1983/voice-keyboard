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

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateLanguages
import dev.patrickgold.florisboard.dictate.audio.RecordingController
import dev.patrickgold.florisboard.dictate.dictateProxyConfig
import dev.patrickgold.florisboard.dictate.provider.OpenAiCompatibleClient
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.TranscriptionRequest
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.vk.learning.VkLearning
import dev.patrickgold.florisboard.vk.learning.VkLearningApi
import dev.patrickgold.florisboard.vk.training.VkTraining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

private enum class Phase { INTRO, READY, RECORDING, TRANSCRIBING, RESULT, SUMMARY }

/**
 * Voice Keyboard: voice training. Read sentences (and free talk), see what the server heard, and send every
 * recording with its exact transcript to the server, where it counts toward Whisper fine-tuning.
 */
@Composable
fun VkTrainingScreen() = FlorisScreen {
    title = "Voice training"
    previewFieldVisible = false

    val prefs by FlorisPreferenceStore

    content {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val recorder = remember { RecordingController(context) }
        DisposableEffect(Unit) { onDispose { runCatching { recorder.cancel() } } }

        var phase by remember { mutableStateOf(Phase.INTRO) }
        var freeTalk by remember { mutableStateOf(false) }
        var items by remember { mutableStateOf<List<String>>(emptyList()) }
        var index by remember { mutableStateOf(0) }
        var heard by remember { mutableStateOf("") }
        var corrected by remember { mutableStateOf("") }
        var missed by remember { mutableStateOf<Set<Int>>(emptySet()) }
        var wer by remember { mutableStateOf(0.0) }
        var error by remember { mutableStateOf<String?>(null) }
        var recordStartedAt by remember { mutableStateOf(0L) }
        var elapsed by remember { mutableStateOf(0L) }
        var lastAudio by remember { mutableStateOf<File?>(null) }
        var lastAudioMs by remember { mutableStateOf(0L) }
        var lastTranscribeMs by remember { mutableStateOf(0L) }
        val roundWers = remember { mutableListOf<Double>() }
        var roundSeconds by remember { mutableStateOf(0L) }
        var minutes by remember { mutableStateOf<Double?>(null) }
        var ready by remember { mutableStateOf(false) }
        var learnedWords by remember { mutableStateOf<List<String>>(emptyList()) }

        val account = remember {
            prefs.dictate.providerAccounts.get()[prefs.dictate.transcriptionProviderId.get()]?.takeIf { it.isCustom }
        }

        fun refreshStats() {
            scope.launch {
                val api = VkLearningApi.forActiveServer(prefs) ?: return@launch
                runCatching {
                    val audio = withContext(Dispatchers.IO) {
                        Json.parseToJsonElement(api.get("vk/stats")).jsonObject["audio"] as? JsonObject
                    }
                    minutes = (audio?.get("minutes") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                    ready = (audio?.get("fine_tune_ready") as? JsonPrimitive)?.contentOrNull == "true"
                }
            }
        }

        LaunchedEffect(Unit) {
            refreshStats()
            val api = VkLearningApi.forActiveServer(prefs) ?: return@LaunchedEffect
            learnedWords = runCatching {
                withContext(Dispatchers.IO) {
                    val obj = Json.parseToJsonElement(api.get("vk/learned")).jsonObject
                    (obj["vocabulary"] as? JsonArray).orEmpty().mapNotNull { el ->
                        when (el) {
                            is JsonObject -> listOf("word", "term", "text", "value").firstNotNullOfOrNull {
                                (el[it] as? JsonPrimitive)?.contentOrNull
                            }
                            is JsonPrimitive -> el.contentOrNull
                            else -> null
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }

        LaunchedEffect(phase) {
            while (phase == Phase.RECORDING) {
                elapsed = SystemClock.elapsedRealtime() - recordStartedAt
                delay(200)
            }
        }

        fun startRound(talk: Boolean) {
            freeTalk = talk
            items = if (talk) VkTraining.FREE_TALK_PROMPTS.shuffled().take(2) else VkTraining.round(learnedWords)
            index = 0
            roundWers.clear()
            roundSeconds = 0
            error = null
            phase = Phase.READY
        }

        fun startRecording() {
            error = null
            scope.launch {
                runCatching { recorder.start() }
                    .onSuccess {
                        recordStartedAt = SystemClock.elapsedRealtime()
                        phase = Phase.RECORDING
                    }
                    .onFailure { error = "Could not start the microphone (${it.message})." }
            }
        }

        fun stopAndTranscribe() {
            val file = recorder.stop()
            if (file == null || !file.exists() || file.length() <= 44) {
                error = "Nothing was recorded. Try again."
                phase = Phase.READY
                return
            }
            val copy = File(context.cacheDir, "vk_training_last.wav").also { file.copyTo(it, overwrite = true) }
            lastAudio = copy
            lastAudioMs = (copy.length() - 44) / 32
            phase = Phase.TRANSCRIBING
            scope.launch {
                val acc = account
                if (acc == null) {
                    error = "Connect your own server first (Voice Keyboard → Your AI server)."
                    phase = Phase.READY
                    return@launch
                }
                val url = acc.customBaseUrl.let { if (it.endsWith("/")) it else "$it/" }
                val lang = prefs.dictate.activeInputLanguage.get()
                    .takeIf { it != DictateLanguages.DETECT }?.substringBefore('-')?.lowercase() ?: "en"
                val started = SystemClock.elapsedRealtime()
                runCatching {
                    OpenAiCompatibleClient.from(
                        ProviderRegistry.custom(url), acc.apiKey,
                        baseUrlOverride = url,
                        proxy = prefs.dictate.dictateProxyConfig(),
                        timeoutSeconds = prefs.dictate.requestTimeout.get().toLong(),
                    ).transcribe(TranscriptionRequest(copy, acc.transcriptionModel, language = lang))
                }.onSuccess { result ->
                    lastTranscribeMs = SystemClock.elapsedRealtime() - started
                    heard = result.text.trim()
                    corrected = heard
                    if (!freeTalk) {
                        val (w, m) = VkTraining.score(items[index], heard)
                        wer = w
                        missed = m
                    }
                    phase = Phase.RESULT
                }.onFailure {
                    error = "The server could not transcribe this (${it.message ?: it.javaClass.simpleName})."
                    phase = Phase.READY
                }
            }
        }

        fun submitAndNext() {
            val audio = lastAudio ?: return
            val final = if (freeTalk) corrected.trim() else items[index]
            if (final.isNotEmpty()) {
                VkLearning.submitTraining(
                    context, raw = heard, final = final, audio = audio, audioMs = lastAudioMs,
                    transcribeMs = lastTranscribeMs, sttModel = account?.transcriptionModel.orEmpty(),
                    kind = if (freeTalk) "free_talk" else "read",
                )
                roundSeconds += lastAudioMs / 1000
                if (!freeTalk) roundWers.add(wer)
            }
            if (index + 1 < items.size) {
                index++
                phase = Phase.READY
            } else {
                phase = Phase.SUMMARY
                refreshStats()
            }
        }

        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Progress toward the server's fine-tuning target.
            val m = minutes
            Text(
                when {
                    ready -> "Voice training: ready (${"%.0f".format(m ?: 0.0)} minutes collected)"
                    m != null -> "Voice training: %.0f of %d minutes collected".format(m, VkTraining.TARGET_MINUTES)
                    else -> "Voice training"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            LinearProgressIndicator(
                progress = { ((m ?: 0.0) / VkTraining.TARGET_MINUTES).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            when (phase) {
                Phase.INTRO -> {
                    Text(
                        "Read sentences aloud (or talk freely) so your server can learn your voice. After each one " +
                            "you see what it heard. Every recording is sent to your server with its exact text and " +
                            "kept there for up to a year, for training speech recognition on your voice.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Tip: speak naturally, at your normal pace, where you usually dictate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { startRound(false) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Start reading (${VkTraining.ROUND_SIZE} sentences, about 3 minutes)")
                    }
                    OutlinedButton(onClick = { startRound(true) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Free talk (2 prompts, about 1 minute each)")
                    }
                }

                Phase.READY, Phase.RECORDING, Phase.TRANSCRIBING -> {
                    Text(
                        "${if (freeTalk) "Prompt" else "Sentence"} ${index + 1} of ${items.size}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            items.getOrElse(index) { "" },
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    if (freeTalk) {
                        Text(
                            "Answer in your own words for 30 to 60 seconds.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    when (phase) {
                        Phase.READY -> Button(onClick = { startRecording() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Record")
                        }
                        Phase.RECORDING -> Button(onClick = { stopAndTranscribe() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Stop  (%d s)".format(elapsed / 1000))
                        }
                        else -> {
                            Text("Transcribing on your server…")
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (phase == Phase.READY) {
                        TextButton(onClick = {
                            if (index + 1 < items.size) index++ else phase = Phase.SUMMARY
                        }) { Text("Skip") }
                    }
                }

                Phase.RESULT -> {
                    if (freeTalk) {
                        Text("What your server heard. Fix any mistakes, then send:", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = corrected,
                            onValueChange = { corrected = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                        )
                    } else {
                        Text("You read:", style = MaterialTheme.typography.labelLarge)
                        val expectedWords = items[index].split(Regex("\\s+"))
                        Text(
                            buildAnnotatedString {
                                // Words are matched in order; misheard ones are struck through and bold.
                                var wi = 0
                                expectedWords.forEachIndexed { k, token ->
                                    val hasWord = VkTraining.words(token).isNotEmpty()
                                    val isMissed = hasWord && wi in missed
                                    if (isMissed) {
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.error)) {
                                            append(token)
                                        }
                                    } else {
                                        append(token)
                                    }
                                    if (hasWord) wi += VkTraining.words(token).size
                                    if (k < expectedWords.size - 1) append(" ")
                                }
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("Your server heard:", style = MaterialTheme.typography.labelLarge)
                        Text(heard.ifEmpty { "(nothing)" }, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Accuracy: %.0f %%".format((1.0 - wer).coerceAtLeast(0.0) * 100),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { phase = Phase.READY }, modifier = Modifier.weight(1f)) {
                            Text("Record again")
                        }
                        Button(onClick = { submitAndNext() }, modifier = Modifier.weight(1f)) {
                            Text(if (index + 1 < items.size) "Send and next" else "Send and finish")
                        }
                    }
                }

                Phase.SUMMARY -> {
                    Text("Round finished", style = MaterialTheme.typography.titleLarge)
                    Text("Recorded about $roundSeconds seconds; it is on its way to your server.")
                    if (roundWers.isNotEmpty()) {
                        val acc = (1.0 - roundWers.average()).coerceAtLeast(0.0) * 100
                        Text("Accuracy this round: %.0f %%".format(acc), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "Uploads wait for Wi-Fi if \"Upload voice clips on Wi-Fi only\" is on. The minutes above update " +
                            "once the server has them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { startRound(false) }, modifier = Modifier.fillMaxWidth()) { Text("Another reading round") }
                    OutlinedButton(onClick = { startRound(true) }, modifier = Modifier.fillMaxWidth()) { Text("Free talk") }
                    TextButton(onClick = { phase = Phase.INTRO; refreshStats() }) { Text("Done") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
