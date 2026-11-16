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

import dev.patrickgold.florisboard.nlpManager
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateLanguages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Voice Keyboard, Phase 2: learning from the user's own corrections, stored only on the user's server.
 *
 * Capture: after a dictation lands in a field, the dictated span is followed (by the text just before and
 * after it) while the keyboard stays on that field. When the field is sent, cleared, left or the keyboard
 * closes, the final span is compared with what was inserted ([CorrectionDiff]) and the small edits are
 * queued for `POST vk/corrections`; with "Learn my writing style" the final sent span also goes to
 * `POST vk/messages`. Only the dictated span (plus a few words of context in an edit) ever leaves the
 * phone; password fields, fields asking for no personalized learning and excluded apps are never read.
 *
 * Use: `GET vk/profile?app=<package>` (at most hourly per app) gives a Whisper prompt, replacement rules
 * for repeated mishears and a style for the cleanup model.
 */
object VkLearning {
    private const val TAG = "VKLearning"
    private const val PROFILE_MAX_AGE_MS = 60 * 60 * 1000L
    private const val QUEUE_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    private const val ANCHOR_CHARS = 30
    private const val READ_CHARS = 2000
    private const val MAX_CLIP_BYTES = 20L * 1024 * 1024

    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    // ---- Capture ---------------------------------------------------------------------------------

    /** What one dictation carries to the server besides the edits (POST vk/dictations). */
    private class Record(
        val id: String,
        val raw: String,
        val cleaned: String,
        val lowConfidence: List<String>,
        val cleanupMs: Long,
        val clip: java.io.File?,
        val cleanupRejected: Boolean,
    ) {
        /** This dictation's timings, attached when they are measured (not looked up at send time). */
        @Volatile var timings: dev.patrickgold.florisboard.dictate.DictationTimings.Entry? = null
    }

    /** The newest dictation's record, waiting for its timings (see [attachTimings]). */
    @Volatile private var lastRecord: Record? = null

    /** Called by DictationTimings when a dictation's stop-to-text is known. */
    fun attachTimings(entry: dev.patrickgold.florisboard.dictate.DictationTimings.Entry) {
        lastRecord?.let { if (it.timings == null) it.timings = entry }
    }

    // The dictation in progress: filled while it is transcribed and cleaned, taken at insert time.
    private val pendingLow = mutableListOf<String>()
    @Volatile private var pendingRaw = ""
    @Volatile private var pendingCleanupMs = 0L
    @Volatile private var pendingClip: java.io.File? = null
    @Volatile private var appContext: android.content.Context? = null

    /** A new dictation starts (recording stopped or chunked recording started). */
    fun beginDictation() {
        synchronized(pendingLow) { pendingLow.clear() }
        pendingRaw = ""
        pendingCleanupMs = 0L
        pendingClip?.delete()
        pendingClip = null
    }

    fun noteRaw(text: String) { pendingRaw = text }
    fun noteCleanupMs(ms: Long) { pendingCleanupMs = ms }
    fun addLowConfidence(words: List<String>) {
        if (words.isNotEmpty()) synchronized(pendingLow) { pendingLow.addAll(words) }
    }

    /** Whether to ask the speech server for word confidence (learning on, own server). */
    // Off (server measurement 2026-11-14): word timestamps make the CPU-only Whisper align every word, which
    // doubled transcription time, and the server learns mishears from corrections anyway. The record then
    // carries "low_confidence": []. The client path stays, for a faster server later.
    @Suppress("FunctionOnlyReturningConstant")
    fun wantsWordConfidence(): Boolean = false

    /** The whole recording of this dictation, kept for upload when "Help it learn my voice" is on. */
    fun noteAudio(context: android.content.Context, file: java.io.File?) {
        appContext = context.applicationContext
        if (!prefs.dictate.vkLearnVoice.get() || file == null || !file.exists()) return
        if (file.length() <= 44 || file.length() > MAX_CLIP_BYTES || !file.name.endsWith(".wav")) return
        runCatching {
            val dir = java.io.File(context.filesDir, "vk_clips").apply { mkdirs() }
            val copy = java.io.File(dir, java.util.UUID.randomUUID().toString() + ".wav")
            file.copyTo(copy, overwrite = true)
            pendingClip?.delete()
            pendingClip = copy
        }
    }

    private fun takeRecord(cleaned: String, cleanupRejected: Boolean): Record {
        val low = synchronized(pendingLow) { pendingLow.toList().also { pendingLow.clear() } }
        val clip = pendingClip
        pendingClip = null
        return Record(
            id = java.util.UUID.randomUUID().toString(),
            raw = pendingRaw,
            cleaned = cleaned,
            lowConfidence = low.distinct(),
            cleanupMs = pendingCleanupMs,
            clip = clip,
            cleanupRejected = cleanupRejected,
        ).also { lastRecord = it }
    }

    private class Tracked(
        val record: Record,
        val app: String,
        val fieldId: Int,
        val inserted: String,
        val prefix: String,
        val suffix: String,
        val sttModel: String,
        val lang: String,
        @Volatile var lastSpan: String,
    )

    @Volatile private var tracked: Tracked? = null

    private fun enabled(): Boolean = prefs.dictate.vkLearnCorrections.get() || prefs.dictate.vkLearnStyle.get()

    private fun excludedApps(): Set<String> =
        prefs.dictate.vkLearningExcludedApps.get().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** True when this field must never be read for learning. */
    /**
     * Dictated words count toward the person's most-used words in the suggestion strip, like typed ones
     * (on the device only; independent of the server learning switch). Never in private fields.
     */
    private fun countWordsForSuggestions(text: String) {
        val ctx = appContext ?: return
        if (text.isEmpty() || !prefs.dictate.vkPersonalWordRanking.get()) return
        if (isPrivateField(FlorisImeService.currentEditorInfo())) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            dev.patrickgold.florisboard.ime.nlp.latin.PersonalWords.current(ctx, prefs).count(text)
        }
        // …and its word pairs, for next-word prediction.
        runCatching<Unit> { ctx.nlpManager().value.learnWordPairsFromText(text) }
    }

    fun isPrivateField(info: EditorInfo?): Boolean {
        if (info == null) return true
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
        ) return true
        if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true
        val app = info.packageName.orEmpty()
        return app.isEmpty() || app in excludedApps()
    }

    /** A dictation was just inserted into the keyboard's field; the cursor sits right after it. */
    fun onDictationInserted(inserted: String, sttModel: String, cleanupRejected: Boolean = false) {
        val text = inserted.trim()
        val record = takeRecord(text, cleanupRejected)
        countWordsForSuggestions(text)
        if (!enabled() || text.isEmpty()) { record.clip?.delete(); return }
        val info = FlorisImeService.currentEditorInfo()
        if (isPrivateField(info)) { record.clip?.delete(); return }
        finish(Reason.REPLACED) // a previous dictation in progress ends here
        val app = info!!.packageName.orEmpty()
        val lang = languageCode()
        val ic = FlorisImeService.currentInputConnection()
        val before = ic?.getTextBeforeCursor(READ_CHARS, 0)?.toString()
        val after = ic?.getTextAfterCursor(READ_CHARS, 0)?.toString().orEmpty()
        val trimmedBefore = before?.trimEnd()
        if (trimmedBefore == null || !trimmedBefore.endsWith(text)) {
            // Not where we think it is: no corrections, but the dictation itself still counts (final unknown).
            scope.launch {
                kotlinx.coroutines.delay(3_000) // let the timings of this dictation land first
                sendRecord(record, app, lang, sttModel, final = "")
            }
            return
        }
        val prefix = trimmedBefore.dropLast(text.length).takeLast(ANCHOR_CHARS)
        tracked = Tracked(
            record = record,
            app = info!!.packageName.orEmpty(),
            fieldId = info.fieldId,
            inserted = text,
            prefix = prefix,
            suffix = after.take(ANCHOR_CHARS),
            sttModel = sttModel,
            lang = languageCode(),
            lastSpan = text,
        )
    }

    /** The field's text or selection changed: follow the dictated span. */
    fun onFieldChanged() {
        val t = tracked ?: return
        val info = FlorisImeService.currentEditorInfo()
        if (info == null || info.packageName != t.app || info.fieldId != t.fieldId) return
        val ic = FlorisImeService.currentInputConnection() ?: return
        val before = ic.getTextBeforeCursor(READ_CHARS, 0)?.toString() ?: return
        val after = ic.getTextAfterCursor(READ_CHARS, 0)?.toString().orEmpty()
        val full = before + after
        if (full.isBlank()) {
            // Cleared right after a send button in the app: the last version we saw was what was sent.
            finish(Reason.CLEARED)
            return
        }
        locate(full, t.prefix, t.suffix)?.let { t.lastSpan = it }
    }

    /** The editor action (Send/Go/Done) is about to run. */
    fun onEditorAction(action: Int) {
        if (tracked == null) return
        onFieldChanged()
        if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_GO ||
            action == EditorInfo.IME_ACTION_DONE
        ) {
            finish(Reason.SENT)
        }
    }

    /** The field is left or the keyboard closes. */
    fun onFieldFinished() {
        if (tracked == null) return
        onFieldChanged()
        finish(Reason.LEFT)
    }

    private enum class Reason { SENT, CLEARED, LEFT, REPLACED }

    private fun finish(reason: Reason) {
        val t = tracked ?: return
        tracked = null
        val final = t.lastSpan.trim()
        sendRecord(t.record, t.app, t.lang, t.sttModel, final)
        // Not from a dictation whose cleanup answer was thrown away: its text is not something to learn from.
        if (prefs.dictate.vkLearnCorrections.get() && !t.record.cleanupRejected) {
            val edits = CorrectionDiff.edits(t.inserted, final)
            if (edits.isNotEmpty()) {
                val body = buildJsonObject {
                    put("device", prefs.dictate.vkSignedInDevice.get().ifBlank { android.os.Build.MODEL })
                    put("app", t.app)
                    put("lang", t.lang)
                    put("stt_model", t.sttModel)
                    put("edits", buildJsonArray {
                        edits.forEach { e ->
                            add(buildJsonObject { put("before", e.before); put("after", e.after); put("context", e.context) })
                        }
                    })
                    put("ts", System.currentTimeMillis() / 1000)
                }
                enqueue("vk/corrections", body.toString())
                Log.i(TAG, "queued corrections=${edits.size} reason=$reason")
            }
        }
        if (prefs.dictate.vkLearnStyle.get() && (reason == Reason.SENT || reason == Reason.CLEARED) && final.isNotEmpty()) {
            val body = buildJsonObject {
                put("app", t.app)
                put("category", categoryOf(t.app))
                put("text", final)
                put("lang", t.lang)
            }
            enqueue("vk/messages", body.toString())
            Log.i(TAG, "queued message reason=$reason")
        }
    }

    /** POST vk/dictations (with "Learn from my corrections"), then the voice clip (with "Help it learn my voice"). */
    private fun sendRecord(r: Record, app: String, lang: String, sttModel: String, final: String) {
        val items = mutableListOf<JsonObject>()
        val sendsClip = r.clip?.exists() == true && prefs.dictate.vkLearnVoice.get()
        // The record goes with "Learn from my corrections", and with every voice clip (the clip needs it).
        if (prefs.dictate.vkLearnCorrections.get() || sendsClip) {
            val t = r.timings // captured with the dictation, however long the user took to send it
            val body = buildJsonObject {
                put("id", r.id)
                put("app", app)
                put("category", categoryOf(app))
                put("lang", lang)
                put("stt_model", sttModel)
                put("raw", r.raw)
                put("cleaned", r.cleaned)
                put("final", final)
                put("audio_ms", t?.let { e -> e.chunks.sumOf { it.audioMs }.takeIf { it > 0 } ?: e.recordedMs } ?: 0L)
                put("low_confidence", buildJsonArray { r.lowConfidence.forEach { add(JsonPrimitive(it)) } })
                put("timings", buildJsonObject {
                    put("stop_to_text_ms", t?.stopToTextMs ?: 0L)
                    put("transcribe_ms", t?.let { e -> e.chunks.sumOf { it.transcribeMs }.takeIf { it > 0 } ?: e.transcribeAfterStopMs } ?: 0L)
                    put("cleanup_ms", r.cleanupMs)
                })
            }
            items.add(postItem("vk/dictations", body.toString()))
            Log.i(TAG, "queued dictation record low=${r.lowConfidence.size}")
        }
        val clip = r.clip
        if (clip != null) {
            if (prefs.dictate.vkLearnVoice.get() && clip.exists()) items.add(audioItem(r.id, clip)) else clip.delete()
        }
        enqueueAll(items)
    }

    /** The dictated span inside [full], found between the text that stood before and after it. */
    internal fun locate(full: String, prefix: String, suffix: String): String? {
        var from = 0
        while (true) {
            val start = if (prefix.isEmpty()) 0 else full.indexOf(prefix, from).takeIf { it >= 0 }?.plus(prefix.length) ?: return null
            val end = if (suffix.isEmpty()) full.length else full.indexOf(suffix, start).takeIf { it >= 0 } ?: -1
            if (end >= start) return full.substring(start, end)
            if (prefix.isEmpty()) return null
            from = start
        }
    }

    private fun languageCode(): String {
        val code = prefs.dictate.activeInputLanguage.get()
        return if (code == DictateLanguages.DETECT) "" else code.substringBefore('-').lowercase()
    }

    private fun categoryOf(app: String): String {
        val a = app.lowercase()
        return when {
            listOf("mail", "gm", "outlook", "proton").any { a.contains(it) } && !a.contains("gmaps") -> "email"
            listOf("messag", "mms", "whatsapp", "telegram", "signal", "chat", "discord", "slack", "messenger", "sms", "teams")
                .any { a.contains(it) } -> "chat"
            else -> "other"
        }
    }

    // ---- Upload queue ----------------------------------------------------------------------------

    /**
     * Voice training (VkTrainingScreen): one recording with its exact transcript. Always uploaded — the user
     * started the training to send exactly this — as a dictation record (`final` = the script, or the
     * user-corrected free talk) followed by the audio, which the server counts toward fine-tuning.
     */
    fun submitTraining(
        context: android.content.Context,
        raw: String,
        final: String,
        audio: java.io.File,
        audioMs: Long,
        transcribeMs: Long,
        sttModel: String,
        kind: String,
    ) {
        appContext = context.applicationContext
        val id = java.util.UUID.randomUUID().toString()
        val dir = java.io.File(context.filesDir, "vk_clips").apply { mkdirs() }
        val clip = java.io.File(dir, "$id.wav")
        runCatching { audio.copyTo(clip, overwrite = true) }.onFailure { return }
        val body = buildJsonObject {
            put("id", id)
            put("app", context.packageName + ".training")
            put("category", "other")
            put("lang", languageCode().ifEmpty { "en" })
            put("stt_model", sttModel)
            // The server needs raw/cleaned text; a silent or failed transcription sends the known text instead.
            put("raw", raw.ifBlank { final })
            put("cleaned", raw.ifBlank { final })
            put("final", final)
            put("audio_ms", audioMs)
            put("low_confidence", buildJsonArray { })
            put("source", "training")
            put("training_kind", kind)
            put("timings", buildJsonObject {
                put("stop_to_text_ms", transcribeMs)
                put("transcribe_ms", transcribeMs)
                put("cleanup_ms", 0)
            })
        }
        // Record and audio in one step, so the audio can never be queued (and sent) before its record.
        enqueueAll(listOf(postItem("vk/dictations", body.toString()), audioItem(id, clip, training = true)))
        Log.i(TAG, "queued training $kind audioMs=$audioMs")
    }

    private fun audioItem(dictationId: String, file: java.io.File, training: Boolean = false): JsonObject =
        buildJsonObject {
            put("kind", "audio"); put("id", dictationId); put("file", file.absolutePath)
            if (training) put("training", true)
            put("ts", System.currentTimeMillis())
            VkIdentity.currentUser(prefs)?.let { put("as", it) }
        }

    private fun postItem(path: String, body: String): JsonObject = buildJsonObject {
        put("path", path); put("body", body); put("ts", System.currentTimeMillis())
        // Who it was captured for, so a later user switch cannot misfile it.
        VkIdentity.currentUser(prefs)?.let { put("as", it) }
    }

    /** Appends [items] to the queue in one locked write (keeps a record ahead of its audio), then sends. */
    private fun enqueueAll(items: List<JsonObject>) {
        if (items.isEmpty()) return
        scope.launch {
            queueMutex.withLock { writeQueue(readQueue() + items) }
            flush()
        }
    }

    private fun onUnmeteredNetwork(): Boolean {
        val ctx = appContext ?: return false
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        return !cm.isActiveNetworkMetered
    }

    private fun enqueue(path: String, body: String) = enqueueAll(listOf(postItem(path, body)))

    // The queue and the profile cache hold dictated text, so they live in plain files under the app's own
    // storage (files/vk_learning), not in the settings that Android backs up or moves to a new phone.
    private fun storeFile(name: String): java.io.File? {
        val ctx = appContext ?: dev.patrickgold.florisboard.florisApplicationOrNull() ?: return null
        return java.io.File(java.io.File(ctx.filesDir, "vk_learning").apply { mkdirs() }, name)
    }

    private fun readQueue(): List<JsonObject> = runCatching {
        val f = storeFile("queue.json")?.takeIf { it.exists() } ?: return emptyList()
        json.parseToJsonElement(f.readText()).jsonArray.map { it.jsonObject }
    }.getOrDefault(emptyList())

    private fun writeQueue(items: List<JsonObject>) {
        runCatching { storeFile("queue.json")?.writeText(JsonArray(items).toString()) }
    }

    /** Whether to keep an upload the server refused for another try. */
    private fun retryable(e: VkLearningApi.HttpException): Boolean = when {
        e.status == 400 -> e.message?.contains("select a user", ignoreCase = true) == true
        e.status == 401 || e.status == 403 || e.status == 408 || e.status == 429 -> true
        else -> e.status >= 500
    }

    /** Sends what is waiting; keeps what fails for later, drops what is older than seven days. */
    fun flush() {
        scope.launch {
            queueMutex.withLock {
                val api = VkLearningApi.forActiveServer(prefs) ?: return@withLock
                val now = System.currentTimeMillis()
                val left = mutableListOf<JsonObject>()
                val all = readQueue()
                // Records still waiting anywhere in the queue: their audio must wait too.
                val unsentRecords = all.filter { it["path"]?.jsonPrimitive?.contentOrNull == "vk/dictations" }
                    .mapNotNull { it["body"]?.jsonPrimitive?.contentOrNull }
                    .toMutableList()
                for (item in all) {
                    val ts = item["ts"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: now
                    val isAudio = item["kind"]?.jsonPrimitive?.contentOrNull == "audio"
                    if (now - ts > QUEUE_MAX_AGE_MS) {
                        if (isAudio) item["file"]?.jsonPrimitive?.contentOrNull?.let { java.io.File(it).delete() }
                        continue
                    }
                    if (isAudio) {
                        val id = item["id"]?.jsonPrimitive?.contentOrNull
                        val file = item["file"]?.jsonPrimitive?.contentOrNull?.let { java.io.File(it) }
                        val training = item["training"]?.jsonPrimitive?.contentOrNull == "true"
                        if (id == null || file == null || !file.exists() || !(training || prefs.dictate.vkLearnVoice.get())) {
                            file?.delete()
                            continue
                        }
                        // After its dictation record, and on Wi-Fi unless the user allows mobile data.
                        val recordPending = unsentRecords.any { it.contains("\"$id\"") }
                        if (recordPending || (prefs.dictate.vkVoiceWifiOnly.get() && !onUnmeteredNetwork())) {
                            left.add(item)
                            continue
                        }
                        val actAsAudio = item["as"]?.jsonPrimitive?.contentOrNull ?: VkIdentity.currentUser(prefs)
                        try {
                            api.postFile("vk/audio?dictation=" + java.net.URLEncoder.encode(id, "UTF-8"), file, "audio/wav", actAs = actAsAudio)
                            file.delete()
                            Log.i(TAG, "uploaded voice clip bytes=${file.length()}")
                        } catch (e: VkLearningApi.HttpException) {
                            // 404 "unknown dictation": its record has not arrived yet — try again later.
                            if (e.status == 404 || retryable(e)) left.add(item) else file.delete()
                            Log.i(TAG, "voice clip upload http=${e.status}")
                        } catch (e: Exception) {
                            left.add(item)
                        }
                        continue
                    }
                    val path = item["path"]?.jsonPrimitive?.contentOrNull ?: continue
                    val body = item["body"]?.jsonPrimitive?.contentOrNull ?: continue
                    val actAs = item["as"]?.jsonPrimitive?.contentOrNull ?: VkIdentity.currentUser(prefs)
                    if (actAs == null) { left.add(item); continue } // nobody selected yet: keep it
                    try {
                        api.post(path, body, actAs = actAs)
                        unsentRecords.remove(body)
                    } catch (e: VkLearningApi.HttpException) {
                        // Only "select a user", auth and server trouble are worth another try; any other 4xx
                        // (e.g. "raw or cleaned text required") would fail the same way for seven days.
                        if (retryable(e)) left.add(item) else unsentRecords.remove(body)
                        Log.i(TAG, "upload $path failed http=${e.status}")
                    } catch (e: Exception) {
                        left.add(item)
                        Log.i(TAG, "upload $path deferred (${e.javaClass.simpleName})")
                    }
                }
                writeQueue(left)
            }
        }
    }

    // ---- Profile -------------------------------------------------------------------------------

    data class Replacement(val from: String, val to: String)

    data class Profile(
        val version: String,
        val whisperPrompt: String,
        val replacements: List<Replacement>,
        val cleanupStyle: String,
        val styleExamples: List<String>,
        /** Words the cleanup keeps adding that the user removes. */
        val cleanupAvoid: List<String> = emptyList(),
    )

    @Volatile private var currentApp: String = ""

    /** The keyboard opened on a field of [app]: refresh that app's profile if it is over an hour old. */
    fun onKeyboardStart(info: EditorInfo?) {
        val app = info?.packageName.orEmpty()
        currentApp = app
        if (app.isEmpty()) return
        appContext = appContext ?: dev.patrickgold.florisboard.florisApplicationOrNull()
        // Once: learning data used to sit in the backed-up settings; it now lives in files/vk_learning.
        scope.launch {
            if (prefs.dictate.vkLearningQueue.get() != "[]") prefs.dictate.vkLearningQueue.set("[]")
            if (prefs.dictate.vkProfileCache.get() != "{}") prefs.dictate.vkProfileCache.set("{}")
        }
        flush()
        appContext?.let { VkPersonSync.syncIfDue(it) }
        // With learning off nothing is fetched: the request would tell the server which apps are used.
        if (!enabled()) return
        scope.launch {
            val key = profileKey(app) ?: return@launch // nobody selected: no profile
            val cache = readProfiles()
            val cached = cache[key]?.jsonObject
            val fetchedAt = cached?.get("fetched_at")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - fetchedAt < PROFILE_MAX_AGE_MS) return@launch
            val api = VkLearningApi.forActiveServer(prefs) ?: return@launch
            runCatching {
                val body = api.get(
                    "vk/profile?app=" + java.net.URLEncoder.encode(app, "UTF-8") + "&category=" + categoryOf(app),
                )
                val profile = json.parseToJsonElement(body).jsonObject
                val updated = JsonObject(cache + (key to JsonObject(profile + ("fetched_at" to JsonPrimitive(System.currentTimeMillis())))))
                storeFile("profiles.json")?.writeText(updated.toString())
            }.onFailure { Log.i(TAG, "profile fetch failed (${it.javaClass.simpleName})") }
        }
    }

    /** Profiles are per person and app. */
    private fun profileKey(app: String): String? = VkIdentity.currentUser(prefs)?.let { "$it|$app" }

    /** Drop cached profiles (after a user switch the next keyboard start fetches the new person's). */
    suspend fun clearProfiles() {
        storeFile("profiles.json")?.delete()
    }

    private fun readProfiles(): JsonObject = runCatching {
        val f = storeFile("profiles.json")?.takeIf { it.exists() } ?: return JsonObject(emptyMap())
        json.parseToJsonElement(f.readText()).jsonObject
    }.getOrDefault(JsonObject(emptyMap()))

    /** The profile for the app the keyboard is on, from the cache; null when there is none. */
    fun profile(): Profile? {
        if (!enabled()) return null // learning off: nothing learned is applied
        if (VkLearningApi.forActiveServer(prefs) == null) return null
        val key = profileKey(currentApp) ?: return null
        val obj = readProfiles()[key]?.jsonObject ?: return null
        fun str(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val reps = (obj["replacements"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val from = (o["from"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val to = (o["to"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val count = (o["count"] as? JsonPrimitive)?.intOrNull ?: 2
            if (from.isBlank() || count < 2) null else Replacement(from, to)
        }
        val avoid = (obj["cleanup_avoid"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotBlank() } }
        val examples = (obj["style_examples"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
        return Profile(str("version"), str("whisper_prompt"), reps, str("cleanup_style"), examples.take(3), avoid)
    }

    /** Learned fix rules, applied to a transcription before cleanup: whole words, case kept. */
    fun applyReplacements(text: String): String {
        val reps = profile()?.replacements.orEmpty()
        return applyReplacements(text, reps)
    }

    internal fun applyReplacements(text: String, reps: List<Replacement>): String {
        var out = text
        for (r in reps) {
            val pattern = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(r.from) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            out = pattern.replace(out) { m -> matchCase(m.value, r.to) }
        }
        return out
    }

    private fun matchCase(found: String, to: String): String = when {
        to.any { it.isUpperCase() } -> to // the rule's own casing (names, brands) wins
        found.length > 1 && found.all { !it.isLetter() || it.isUpperCase() } -> to.uppercase()
        found.firstOrNull()?.isUpperCase() == true -> to.replaceFirstChar { it.uppercase() }
        else -> to
    }

    /** What the learned profile adds to the cleanup instruction (style, examples, and what not to change). */
    fun cleanupAddendum(): String {
        val p = profile()
        return buildString {
            append("Fix only filler words, grammar and punctuation. Do not change the wording or the tone, and do not add words (for example \"please\").")
            if (p != null && p.cleanupStyle.isNotBlank()) {
                append("\nStyle notes (tone only - never add, remove or change words because of these): ")
                append(p.cleanupStyle.trim())
            }
            if (p != null && p.cleanupAvoid.isNotEmpty()) {
                append("\nDo not add these words unless spoken: ").append(p.cleanupAvoid.joinToString(", "))
            }
            // No style_examples here (server measurement 2026-11-14): reading them on every dictation made
            // the cleanup 2-3 times slower; the short cleanup_style list carries the style.
        }
    }

    // ---- Account -------------------------------------------------------------------------------

    /**
     * `GET vk/me` as the selected person; stores and returns the display name ("Chris"). Null when nobody is
     * selected or the server wants a selection (then the picker is shown), or the server cannot be reached.
     */
    suspend fun refreshSignedIn(prefsModel: FlorisPreferenceModel = prefs): String? {
        val api = VkLearningApi.forActiveServer(prefsModel) ?: return null
        if (VkIdentity.currentUser(prefsModel) == null) return null
        return runCatching {
            val obj = json.parseToJsonElement(api.get("vk/me")).jsonObject
            val user = (obj["user"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: displayName(user)
            val device = (obj["device"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            prefsModel.dictate.vkSignedInUser.set(name)
            prefsModel.dictate.vkSignedInDevice.set(device)
            // The person's own speech-to-text model, once the server has trained one (absent or "" = none).
            val sttModel = (obj["stt_model"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            prefsModel.dictate.vkPersonalSttModel.set(if (sttModel.isEmpty() || user.isEmpty()) "" else "$user|$sttModel")
            name.takeIf { it.isNotBlank() }
        }.onFailure { e ->
            if (VkIdentity.needsSelection(e)) VkIdentity.select(prefsModel, null)
        }.getOrNull()
    }

    fun displayName(user: String): String = user.replaceFirstChar { it.uppercase() }

    /** After "Forget everything": drop the local caches too. */
    suspend fun clearLocal() {
        storeFile("profiles.json")?.delete()
        storeFile("queue.json")?.delete()
        prefs.dictate.vkProfileCache.set("{}")
        prefs.dictate.vkLearningQueue.set("[]")
        tracked = null
        deleteLocalClips()
    }

    /**
     * The person's own speech-to-text model to use instead of [chosen], when the server reported one for
     * the person selected now and the switch is on; [chosen] otherwise.
     */
    fun personalSttModel(chosen: String): String {
        if (!prefs.dictate.vkUsePersonalModel.get()) return chosen
        val who = VkIdentity.currentUser(prefs) ?: return chosen
        val stored = prefs.dictate.vkPersonalSttModel.get()
        val model = stored.substringAfter('|', "")
        return if (stored.substringBefore('|') == who && model.isNotBlank()) model else chosen
    }

    /** Voice clips waiting on this phone for upload. */
    fun deleteLocalClips() {
        pendingClip = null
        appContext?.let { java.io.File(it.filesDir, "vk_clips").deleteRecursively() }
    }
}
