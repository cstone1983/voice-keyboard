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

    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    // ---- Capture ---------------------------------------------------------------------------------

    private class Tracked(
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
    fun onDictationInserted(inserted: String, sttModel: String) {
        val text = inserted.trim()
        if (!enabled() || text.isEmpty()) return
        val info = FlorisImeService.currentEditorInfo()
        if (isPrivateField(info)) return
        val ic = FlorisImeService.currentInputConnection() ?: return
        finish(Reason.REPLACED) // a previous dictation in progress ends here
        val before = ic.getTextBeforeCursor(READ_CHARS, 0)?.toString() ?: return
        val after = ic.getTextAfterCursor(READ_CHARS, 0)?.toString().orEmpty()
        val trimmedBefore = before.trimEnd()
        if (!trimmedBefore.endsWith(text)) return // not where we think it is; do not guess
        val prefix = trimmedBefore.dropLast(text.length).takeLast(ANCHOR_CHARS)
        tracked = Tracked(
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
        if (prefs.dictate.vkLearnCorrections.get()) {
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

    private fun enqueue(path: String, body: String) {
        scope.launch {
            queueMutex.withLock {
                val items = readQueue().toMutableList()
                items.add(buildJsonObject { put("path", path); put("body", body); put("ts", System.currentTimeMillis()) })
                prefs.dictate.vkLearningQueue.set(JsonArray(items).toString())
            }
            flush()
        }
    }

    private fun readQueue(): List<JsonObject> = runCatching {
        json.parseToJsonElement(prefs.dictate.vkLearningQueue.get()).jsonArray.map { it.jsonObject }
    }.getOrDefault(emptyList())

    /** Sends what is waiting; keeps what fails for later, drops what is older than seven days. */
    fun flush() {
        scope.launch {
            queueMutex.withLock {
                val api = VkLearningApi.forActiveServer(prefs) ?: return@withLock
                val now = System.currentTimeMillis()
                val left = mutableListOf<JsonObject>()
                for (item in readQueue()) {
                    val ts = item["ts"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: now
                    if (now - ts > QUEUE_MAX_AGE_MS) continue
                    val path = item["path"]?.jsonPrimitive?.contentOrNull ?: continue
                    val body = item["body"]?.jsonPrimitive?.contentOrNull ?: continue
                    try {
                        api.post(path, body)
                    } catch (e: VkLearningApi.HttpException) {
                        // A request the server refuses (4xx other than auth) will never succeed: drop it.
                        if (e.status == 401 || e.status == 403 || e.status >= 500) left.add(item)
                        Log.i(TAG, "upload $path failed http=${e.status}")
                    } catch (e: Exception) {
                        left.add(item)
                        Log.i(TAG, "upload $path deferred (${e.javaClass.simpleName})")
                    }
                }
                prefs.dictate.vkLearningQueue.set(JsonArray(left).toString())
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
    )

    @Volatile private var currentApp: String = ""

    /** The keyboard opened on a field of [app]: refresh that app's profile if it is over an hour old. */
    fun onKeyboardStart(info: EditorInfo?) {
        val app = info?.packageName.orEmpty()
        currentApp = app
        if (app.isEmpty()) return
        flush()
        scope.launch {
            val cache = readProfiles()
            val cached = cache[app]?.jsonObject
            val fetchedAt = cached?.get("fetched_at")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - fetchedAt < PROFILE_MAX_AGE_MS) return@launch
            val api = VkLearningApi.forActiveServer(prefs) ?: return@launch
            runCatching {
                val body = api.get("vk/profile?app=" + java.net.URLEncoder.encode(app, "UTF-8"))
                val profile = json.parseToJsonElement(body).jsonObject
                val updated = JsonObject(cache + (app to JsonObject(profile + ("fetched_at" to JsonPrimitive(System.currentTimeMillis())))))
                prefs.dictate.vkProfileCache.set(updated.toString())
            }.onFailure { Log.i(TAG, "profile fetch failed (${it.javaClass.simpleName})") }
        }
    }

    private fun readProfiles(): JsonObject = runCatching {
        json.parseToJsonElement(prefs.dictate.vkProfileCache.get()).jsonObject
    }.getOrDefault(JsonObject(emptyMap()))

    /** The profile for the app the keyboard is on, from the cache; null when there is none. */
    fun profile(): Profile? {
        if (VkLearningApi.forActiveServer(prefs) == null) return null
        val obj = readProfiles()[currentApp]?.jsonObject ?: return null
        fun str(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val reps = (obj["replacements"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val from = (o["from"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val to = (o["to"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val count = (o["count"] as? JsonPrimitive)?.intOrNull ?: 2
            if (from.isBlank() || count < 2) null else Replacement(from, to)
        }
        val examples = (obj["style_examples"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
        return Profile(str("version"), str("whisper_prompt"), reps, str("cleanup_style"), examples.take(3))
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
            if (p != null && p.cleanupStyle.isNotBlank()) append("\nThe writer's style: ").append(p.cleanupStyle.trim())
            if (p != null && p.styleExamples.isNotEmpty()) {
                append("\nExamples of how the writer writes:")
                p.styleExamples.forEach { append("\n- ").append(it.trim()) }
            }
        }
    }

    // ---- Account -------------------------------------------------------------------------------

    /** `GET vk/me`; stores and returns the display name ("Chris"), or null when the server does not say. */
    suspend fun refreshSignedIn(prefsModel: FlorisPreferenceModel = prefs): String? {
        val api = VkLearningApi.forActiveServer(prefsModel) ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(api.get("vk/me")).jsonObject
            val user = (obj["user"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val device = (obj["device"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            prefsModel.dictate.vkSignedInUser.set(user)
            prefsModel.dictate.vkSignedInDevice.set(device)
            user.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun displayName(user: String): String = user.replaceFirstChar { it.uppercase() }

    /** After "Forget everything": drop the local caches too. */
    suspend fun clearLocal() {
        prefs.dictate.vkProfileCache.set("{}")
        prefs.dictate.vkLearningQueue.set("[]")
        tracked = null
    }
}
