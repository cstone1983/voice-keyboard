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

import android.content.Context
import android.util.Log
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.dictionary.LearnedWordsStore
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryEntry
import dev.patrickgold.florisboard.ime.nlp.latin.AutoCorrectStrength
import dev.patrickgold.florisboard.ime.nlp.latin.DictFold
import dev.patrickgold.florisboard.ime.nlp.latin.PersonalWords
import dev.patrickgold.florisboard.ime.nlp.latin.TouchHabits
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Voice Keyboard: the selected person's keyboard profile on their own server, so it follows them to
 * any phone or tablet: word counts, learned words and word pairs, personal dictionary, touch habits and
 * typing settings. `GET vk/personal` / `POST vk/personal` (see docs/server-personalization.md).
 *
 * The device merges what the server has into what it holds and uploads the result. Merging never adds
 * counts together (the same use may be on both sides); it keeps the larger. Settings: the first sync of
 * a person on a device takes the server's; after that this device's are uploaded.
 *
 * Runs only with a server of the user's own and a person selected, at most every [MIN_INTERVAL_MS]
 * (and right after the person changes). A server without the endpoint (404) is remembered for a day.
 */
object VkPersonSync {
    private const val TAG = "VKSync"
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val NO_ENDPOINT_RETRY_MS = 24 * 60 * 60 * 1000L
    private const val META = "vk_person_sync"
    private const val MAX_WORDS_UP = 4000
    private const val MAX_PAIRS_UP = 5000
    const val FORMAT_VERSION = 1

    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    enum class Result { OK, NO_SERVER, NO_PERSON, NO_ENDPOINT, FAILED, OFF }

    /** The settings that are a person's own (typing feel), by stable name. */
    private class Setting(val read: (FlorisPreferenceModel) -> JsonPrimitive, val write: suspend (FlorisPreferenceModel, JsonPrimitive) -> Unit)

    private fun bool(get: (FlorisPreferenceModel) -> dev.patrickgold.jetpref.datastore.model.PreferenceData<Boolean>) = Setting(
        read = { JsonPrimitive(get(it).get()) },
        write = { p, v -> v.booleanOrNull?.let { get(p).set(it) } },
    )

    private fun int(get: (FlorisPreferenceModel) -> dev.patrickgold.jetpref.datastore.model.PreferenceData<Int>, range: IntRange) = Setting(
        read = { JsonPrimitive(get(it).get()) },
        write = { p, v -> v.intOrNull?.takeIf { it in range }?.let { get(p).set(it) } },
    )

    private val SETTINGS: Map<String, Setting> = mapOf(
        "autocorrect" to bool { it.suggestion.autoCorrect },
        "autocorrect_strength" to Setting(
            read = { JsonPrimitive(it.correction.autoCorrectStrength.get().name) },
            write = { p, v ->
                v.contentOrNull?.let { n -> AutoCorrectStrength.entries.firstOrNull { it.name == n } }
                    ?.let { p.correction.autoCorrectStrength.set(it) }
            },
        ),
        "split_touch_correction" to bool { it.keyboard.vkSplitTouchCorrection },
        "split_touch_spread_percent" to int({ it.keyboard.vkSplitTouchSpreadPercent }, 0..40),
        "split_touch_lift_right_percent" to int({ it.keyboard.vkSplitTouchLiftRightPercent }, 0..60),
        "glide_when_split" to bool { it.keyboard.vkGlideWhenSplit },
        "touch_habits" to bool { it.keyboard.vkTouchHabits },
        "personal_word_ranking" to bool { it.dictate.vkPersonalWordRanking },
        "vocabulary_hint" to bool { it.dictate.vkVocabularyHint },
        "use_personal_model" to bool { it.dictate.vkUsePersonalModel },
        "hold_text_until_done" to bool { it.dictate.vkHoldTextUntilDone },
    )

    fun enabled(): Boolean = prefs.dictate.vkProfileSync.get()

    /** The keyboard opened: sync when due. */
    fun syncIfDue(context: Context) {
        if (!enabled()) return
        val meta = meta(context)
        val who = VkPersonFiles.personKey() ?: return
        val last = meta.getLong("last_$who", 0L)
        val noEndpointAt = meta.getLong("no_endpoint_at", 0L)
        val now = System.currentTimeMillis()
        if (now - noEndpointAt < NO_ENDPOINT_RETRY_MS) return
        if (now - last < MIN_INTERVAL_MS) return
        scope.launch { syncNow(context) }
    }

    /** The selected person changed: bring their profile down now. */
    fun onPersonChanged(context: Context) {
        if (!enabled()) return
        scope.launch { syncNow(context) }
        scope.launch { runCatching { VkVocabularyHint.refresh(context) } }
    }

    suspend fun syncNow(context: Context): Result = mutex.withLock {
        if (!enabled()) return Result.OFF
        val api = VkLearningApi.forActiveServer(prefs) ?: return Result.NO_SERVER
        val who = VkPersonFiles.personKey() ?: return Result.NO_PERSON
        val meta = meta(context)
        try {
            val remote: JsonObject? = try {
                json.parseToJsonElement(api.get("vk/personal")).jsonObject
            } catch (e: VkLearningApi.HttpException) {
                // No profile yet is an empty 200 ({}); 404 means the server has no such endpoint.
                if (e.status == 404) {
                    meta.edit().putLong("no_endpoint_at", System.currentTimeMillis()).apply()
                    return Result.NO_ENDPOINT
                }
                throw e
            }
            val firstOnThisDevice = !meta.getBoolean("synced_$who", false)
            if (remote != null && remote.isNotEmpty()) mergeIn(context, remote, applySettings = firstOnThisDevice)
            api.post("vk/personal", buildUpload(context).toString())
            meta.edit()
                .putLong("last_$who", System.currentTimeMillis())
                .putBoolean("synced_$who", true)
                .putLong("no_endpoint_at", 0L)
                .apply()
            Log.i(TAG, "profile synced")
            // The personal speech-to-text model may have become ready.
            runCatching { VkLearning.refreshSignedIn(prefs) }
            return Result.OK
        } catch (e: Exception) {
            Log.i(TAG, "profile sync failed (${e.javaClass.simpleName})")
            return Result.FAILED
        }
    }

    private suspend fun mergeIn(context: Context, remote: JsonObject, applySettings: Boolean) {
        // Word counts.
        (remote["words"] as? JsonObject)?.let { obj ->
            val map = HashMap<String, Pair<Double, Long>>()
            for ((w, v) in obj) {
                val a = (v as? JsonArray) ?: continue
                val score = a.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: continue
                val last = a.getOrNull(1)?.jsonPrimitive?.longOrNull ?: continue
                map[w] = score to last
            }
            PersonalWords.current(context, prefs).merge(map)
        }
        // Touch habits.
        (remote["touch"] as? JsonObject)?.let { obj ->
            TouchHabits.current().merge(obj.toString())
            TouchHabits.save()
        }
        // Learned words: [word, lang, count, last_used].
        (remote["learned"] as? JsonArray)?.forEach { el ->
            val a = el as? JsonArray ?: return@forEach
            val word = a.getOrNull(0)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@forEach
            val lang = a.getOrNull(1)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@forEach
            val count = a.getOrNull(2)?.jsonPrimitive?.intOrNull ?: return@forEach
            val last = a.getOrNull(3)?.jsonPrimitive?.longOrNull ?: return@forEach
            LearnedWordsStore.importWord(context, word, DictFold.foldKey(lang, word), lang, count, last)
        }
        // Word pairs: [prev, word, lang, count, last_used].
        (remote["pairs"] as? JsonArray)?.forEach { el ->
            val a = el as? JsonArray ?: return@forEach
            val prev = a.getOrNull(0)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@forEach
            val word = a.getOrNull(1)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@forEach
            val lang = a.getOrNull(2)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@forEach
            val count = a.getOrNull(3)?.jsonPrimitive?.intOrNull ?: return@forEach
            val last = a.getOrNull(4)?.jsonPrimitive?.longOrNull ?: return@forEach
            LearnedWordsStore.importBigram(context, prev, word, lang, count, last)
        }
        // Personal dictionary: [word, locale|null, shortcut|null]. Only words this device lacks.
        (remote["dictionary"] as? JsonArray)?.let { arr ->
            val dm = DictionaryManager.default()
            dm.loadUserDictionariesIfNecessary()
            val dao = dm.florisUserDictionaryDao()
            if (dao != null) {
                val have = dao.queryAll().map { it.word.lowercase() + "|" + it.locale.orEmpty() }.toHashSet()
                for (el in arr) {
                    val a = el as? JsonArray ?: continue
                    val word = a.getOrNull(0)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                    val locale = a.getOrNull(1)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    val shortcut = a.getOrNull(2)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    if (!have.add(word.lowercase() + "|" + locale.orEmpty())) continue
                    runCatching { dao.insert(UserDictionaryEntry(0, word, 255, locale, shortcut)) }
                }
            }
        }
        if (applySettings) {
            (remote["settings"] as? JsonObject)?.let { obj ->
                for ((name, v) in obj) {
                    val setting = SETTINGS[name] ?: continue
                    val p = v as? JsonPrimitive ?: continue
                    runCatching { setting.write(prefs, p) }
                }
            }
        }
    }

    private suspend fun buildUpload(context: Context): JsonObject {
        val now = System.currentTimeMillis() / 1000L
        val counts = PersonalWords.current(context, prefs).export()
            .entries
            .sortedByDescending { dev.patrickgold.florisboard.ime.nlp.latin.PersonalWordCounts.decayed(it.value.first, it.value.second, now) }
            .take(MAX_WORDS_UP)
        val learned = LearnedWordsStore.exportWords(context)
        val pairs = LearnedWordsStore.exportBigrams(context).sortedByDescending { it.count }.take(MAX_PAIRS_UP)
        val dm = DictionaryManager.default()
        dm.loadUserDictionariesIfNecessary()
        val dictionary = dm.florisUserDictionaryDao()?.queryAll().orEmpty()
        val touch = json.parseToJsonElement(TouchHabits.current().toJson()).jsonObject
        return buildJsonObject {
            put("version", FORMAT_VERSION)
            put("updated_at", now)
            put("words", buildJsonObject {
                for ((w, v) in counts) put(w, buildJsonArray { add(JsonPrimitive(Math.round(v.first * 1000) / 1000.0)); add(JsonPrimitive(v.second)) })
            })
            put("learned", buildJsonArray {
                for (e in learned) add(buildJsonArray {
                    add(JsonPrimitive(e.word)); add(JsonPrimitive(e.lang)); add(JsonPrimitive(e.count)); add(JsonPrimitive(e.lastUsed))
                })
            })
            put("pairs", buildJsonArray {
                for (e in pairs) add(buildJsonArray {
                    add(JsonPrimitive(e.prev)); add(JsonPrimitive(e.word)); add(JsonPrimitive(e.lang))
                    add(JsonPrimitive(e.count)); add(JsonPrimitive(e.lastUsed))
                })
            })
            put("dictionary", buildJsonArray {
                for (e in dictionary) add(buildJsonArray {
                    add(JsonPrimitive(e.word)); add(JsonPrimitive(e.locale)); add(JsonPrimitive(e.shortcut))
                })
            })
            put("touch", touch)
            put("settings", buildJsonObject {
                for ((name, s) in SETTINGS) runCatching { put(name, s.read(prefs)) }
            })
        }
    }

    private fun meta(context: Context) = context.applicationContext.getSharedPreferences(META, Context.MODE_PRIVATE)

    /** "Forget everything": the next sync starts as the first one again. */
    fun forgetLocalState(context: Context) {
        meta(context).edit().clear().apply()
    }
}
