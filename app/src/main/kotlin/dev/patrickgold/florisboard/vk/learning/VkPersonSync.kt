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
    private const val MIN_INTERVAL_MS = 15 * 60 * 1000L
    private const val NO_ENDPOINT_RETRY_MS = 24 * 60 * 60 * 1000L
    private const val META = "vk_person_sync"
    private const val MAX_WORDS_UP = 6000
    private const val MAX_PAIRS_UP = 20000
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
        "reshape_keys" to bool { it.keyboard.vkReshapeKeys },
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

    /** Sync now in the background (no throttle), e.g. after settings were changed. */
    fun syncSoon(context: Context) {
        if (!enabled() || VkPersonFiles.personKey() == null) return
        scope.launch { syncNow(context) }
    }

    private val _status = kotlinx.coroutines.flow.MutableStateFlow("")
    /** What the last sync did, for the settings page ("" before the first). */
    val status: kotlinx.coroutines.flow.StateFlow<String> = _status

    /**
     * The selected person changed: download their whole profile now, settings included (a person's
     * settings are theirs, whatever the phone had), then their spelling hint.
     */
    fun onPersonChanged(context: Context) {
        scope.launch {
            if (enabled()) {
                _status.value = "Downloading profile…"
                syncNow(context, takeServerSettings = true)
            }
            runCatching { VkVocabularyHint.refresh(context) }
        }
    }

    /**
     * Merges the server's copy in and uploads the result. [takeServerSettings]: use the person's settings
     * from the server (on a person switch); otherwise this device's go up.
     */
    suspend fun syncNow(context: Context, takeServerSettings: Boolean = false): Result = mutex.withLock {
        val r = syncLocked(context, takeServerSettings)
        _status.value = when (r) {
            Result.OK -> "Synced " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
            Result.NO_SERVER -> "Connect your own server first"
            Result.NO_PERSON -> "Choose who is using the keyboard first"
            Result.NO_ENDPOINT -> "Your server does not support this yet"
            Result.OFF -> "Profile sync is off"
            Result.FAILED -> "Could not reach the server"
        }
        r
    }

    private suspend fun syncLocked(context: Context, takeServerSettings: Boolean): Result {
        if (!enabled()) return Result.OFF
        val api = VkLearningApi.forActiveServer(prefs) ?: return Result.NO_SERVER
        val who = VkPersonFiles.personKey() ?: return Result.NO_PERSON
        val meta = meta(context)
        try {
            // Twice at most: a 409 means another device saved in between; merge again with its copy.
            for (attempt in 0..1) {
                val remote: JsonObject = try {
                    json.parseToJsonElement(api.get("vk/personal")).jsonObject
                } catch (e: VkLearningApi.HttpException) {
                    // No profile yet is an empty 200 ({}); 404 means the server has no such endpoint.
                    if (e.status == 404) {
                        meta.edit().putLong("no_endpoint_at", System.currentTimeMillis()).apply()
                        return Result.NO_ENDPOINT
                    }
                    throw e
                }
                val r = Parsed.of(remote)
                val local = readLocal(context)
                val base = readBase(context, who)
                val now = System.currentTimeMillis() / 1000L
                val merged = Parsed(
                    words = ProfileMerge.words(r.words, local.words!!, base?.words, now),
                    learned = ProfileMerge.counted(r.learned, local.learned!!, base?.learned),
                    pairs = ProfileMerge.counted(r.pairs, local.pairs!!, base?.pairs),
                    dictionary = ProfileMerge.dictionary(r.dictionary, local.dictionary!!, base?.dictionary),
                    settings = ProfileMerge.settings(
                        r.settings, local.settings!!,
                        // A person switch, or the first sync of this person here: the server's settings.
                        if (takeServerSettings) null else base?.settings,
                    ),
                    touch = mergedTouch(local.touch, r.touch),
                    fixes = ProfileMerge.counted(r.fixes, local.fixes!!, base?.fixes),
                    blocked = ProfileMerge.dictionary(r.blocked, local.blocked!!, base?.blocked),
                    confusion = ProfileMerge.counted(r.confusion, local.confusion!!, base?.confusion),
                )
                val body = merged.toJson(now).let { obj ->
                    // The server refuses (409) if the profile changed since this copy was read.
                    JsonObject(obj + ("if_updated_at" to JsonPrimitive(r.updatedAt)))
                }
                try {
                    api.post("vk/personal", body.toString())
                } catch (e: VkLearningApi.HttpException) {
                    if (e.status == 409 && attempt == 0) continue
                    throw e
                }
                applyLocally(context, merged, local)
                writeBase(context, who, merged, now)
                meta.edit()
                    .putLong("last_$who", System.currentTimeMillis())
                    .putLong("no_endpoint_at", 0L)
                    .apply()
                Log.i(TAG, "profile synced")
                // The personal speech-to-text model may have become ready.
                runCatching { VkLearning.refreshSignedIn(prefs) }
                return Result.OK
            }
            return Result.FAILED
        } catch (e: Exception) {
            Log.i(TAG, "profile sync failed (${e.javaClass.simpleName})")
            return Result.FAILED
        }
    }

    /** One profile in map form. A null section = absent from that copy. */
    private class Parsed(
        val words: Map<String, Pair<Double, Long>>? = null,
        val learned: Map<String, ProfileMerge.Counted>? = null,
        val pairs: Map<String, ProfileMerge.Counted>? = null,
        val dictionary: Map<String, ProfileMerge.DictWord>? = null,
        val settings: Map<String, String>? = null,
        val touch: String? = null,
        val updatedAt: Long = 0L,
        /** Hand-made fixes, by "typo\u0001fix" (word = the fix as written). */
        val fixes: Map<String, ProfileMerge.Counted>? = null,
        /** Auto-corrections taken back, by "typed\u0001wrong". */
        val blocked: Map<String, ProfileMerge.DictWord>? = null,
        /** Key mix-ups, by "b>v" (count = how often). */
        val confusion: Map<String, ProfileMerge.Counted>? = null,
    ) {
        fun toJson(nowSec: Long): JsonObject = buildJsonObject {
            put("version", FORMAT_VERSION)
            put("updated_at", nowSec)
            words?.let { w ->
                put("words", buildJsonObject {
                    w.entries.sortedByDescending { it.value.first }.take(MAX_WORDS_UP).forEach { (k, v) ->
                        put(k, buildJsonArray { add(JsonPrimitive(Math.round(v.first * 1000) / 1000.0)); add(JsonPrimitive(v.second)) })
                    }
                })
            }
            learned?.let { m ->
                put("learned", buildJsonArray {
                    for ((k, e) in m) add(buildJsonArray {
                        add(JsonPrimitive(e.word)); add(JsonPrimitive(k.substringBefore('|'))); add(JsonPrimitive(e.count)); add(JsonPrimitive(e.lastUsed))
                    })
                })
            }
            pairs?.let { m ->
                put("pairs", buildJsonArray {
                    for ((k, e) in m.entries.sortedByDescending { it.value.count }.take(MAX_PAIRS_UP)) {
                        val parts = k.split('|', limit = 3)
                        if (parts.size < 3) continue
                        add(buildJsonArray {
                            add(JsonPrimitive(parts[1])); add(JsonPrimitive(e.word)); add(JsonPrimitive(parts[0]))
                            add(JsonPrimitive(e.count)); add(JsonPrimitive(e.lastUsed))
                        })
                    }
                })
            }
            dictionary?.let { m ->
                put("dictionary", buildJsonArray {
                    for (e in m.values) add(buildJsonArray {
                        add(JsonPrimitive(e.word)); add(JsonPrimitive(e.locale)); add(JsonPrimitive(e.shortcut))
                    })
                })
            }
            touch?.let { runCatching { put("touch", Json.parseToJsonElement(it).jsonObject) } }
            fixes?.let { m ->
                put("fixes", buildJsonArray {
                    for ((k, e) in m) add(buildJsonArray {
                        add(JsonPrimitive(k.substringBefore('\u0001'))); add(JsonPrimitive(e.word))
                        add(JsonPrimitive(e.count)); add(JsonPrimitive(e.lastUsed))
                    })
                })
            }
            blocked?.let { m ->
                put("blocked", buildJsonArray {
                    for (k in m.keys) add(buildJsonArray {
                        add(JsonPrimitive(k.substringBefore('\u0001'))); add(JsonPrimitive(k.substringAfter('\u0001'))); add(JsonPrimitive(0))
                    })
                })
            }
            confusion?.let { m ->
                put("confusion", buildJsonObject { for ((k, e) in m) put(k, e.count) })
            }
            settings?.let { m ->
                put("settings", buildJsonObject {
                    for ((k, v) in m) runCatching { put(k, Json.parseToJsonElement(v)) }
                })
            }
        }

        companion object {
            fun of(obj: JsonObject): Parsed {
                val words = (obj["words"] as? JsonObject)?.let { o ->
                    val m = HashMap<String, Pair<Double, Long>>()
                    for ((w, v) in o) {
                        val a = v as? JsonArray ?: continue
                        val score = a.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: continue
                        val last = a.getOrNull(1)?.jsonPrimitive?.longOrNull ?: continue
                        m[w.lowercase()] = score to last
                    }
                    m
                }
                val learned = (obj["learned"] as? JsonArray)?.let { arr ->
                    val m = HashMap<String, ProfileMerge.Counted>()
                    for (el in arr) {
                        val a = el as? JsonArray ?: continue
                        val word = a.getOrNull(0)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                        val lang = a.getOrNull(1)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                        val count = a.getOrNull(2)?.jsonPrimitive?.intOrNull ?: continue
                        val last = a.getOrNull(3)?.jsonPrimitive?.longOrNull ?: continue
                        m[lang + "|" + DictFold.foldKey(lang, word)] = ProfileMerge.Counted(word, count, last)
                    }
                    m
                }
                val pairs = (obj["pairs"] as? JsonArray)?.let { arr ->
                    val m = HashMap<String, ProfileMerge.Counted>()
                    for (el in arr) {
                        val a = el as? JsonArray ?: continue
                        val prev = a.getOrNull(0)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                        val word = a.getOrNull(1)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                        val lang = a.getOrNull(2)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                        val count = a.getOrNull(3)?.jsonPrimitive?.intOrNull ?: continue
                        val last = a.getOrNull(4)?.jsonPrimitive?.longOrNull ?: continue
                        m["$lang|$prev|$word"] = ProfileMerge.Counted(word, count, last)
                    }
                    m
                }
                val dictionary = (obj["dictionary"] as? JsonArray)?.let { arr ->
                    val m = HashMap<String, ProfileMerge.DictWord>()
                    for (el in arr) {
                        val a = el as? JsonArray ?: continue
                        val word = a.getOrNull(0)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                        val locale = a.getOrNull(1)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        val shortcut = a.getOrNull(2)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        m[dictKey(word, locale)] = ProfileMerge.DictWord(word, locale, shortcut)
                    }
                    m
                }
                val settings = (obj["settings"] as? JsonObject)?.let { o ->
                    o.filter { it.key in SETTINGS && it.value is JsonPrimitive }.mapValues { it.value.toString() }
                }
                return Parsed(
                    words, learned, pairs, dictionary, settings,
                    touch = (obj["touch"] as? JsonObject)?.toString(),
                    updatedAt = (obj["updated_at"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    fixes = dev.patrickgold.florisboard.ime.nlp.latin.PersonalFixCodec.readFixes(obj["fixes"])?.mapValues { (_, f) -> ProfileMerge.Counted(f.fix, f.count, f.lastUsed) },
                    blocked = dev.patrickgold.florisboard.ime.nlp.latin.PersonalFixCodec.readBlocked(obj["blocked"])?.mapValues { (k, _) -> ProfileMerge.DictWord(k, null, null) },
                    confusion = dev.patrickgold.florisboard.ime.nlp.latin.PersonalFixCodec.readConfusion(obj["confusion"])?.mapValues { (k, n) -> ProfileMerge.Counted(k, n, 0L) },
                )
            }

            fun dictKey(word: String, locale: String?) = word.lowercase() + "|" + locale.orEmpty()
        }
    }

    private suspend fun readLocal(context: Context): Parsed {
        val dm = DictionaryManager.default()
        dm.loadUserDictionariesIfNecessary()
        return Parsed(
            words = PersonalWords.current(context, prefs).export(),
            learned = LearnedWordsStore.exportWords(context)
                .associate { it.lang + "|" + it.key to ProfileMerge.Counted(it.word, it.count, it.lastUsed) },
            pairs = LearnedWordsStore.exportBigrams(context)
                .associate { "${it.lang}|${it.prev}|${it.word}" to ProfileMerge.Counted(it.word, it.count, it.lastUsed) },
            dictionary = dm.florisUserDictionaryDao()?.queryAll().orEmpty()
                .associate { Parsed.dictKey(it.word, it.locale) to ProfileMerge.DictWord(it.word, it.locale, it.shortcut) },
            settings = SETTINGS.mapNotNull { (name, s) -> runCatching { name to s.read(prefs).toString() }.getOrNull() }.toMap(),
            touch = TouchHabits.current().toJson(),
            fixes = dev.patrickgold.florisboard.ime.nlp.latin.PersonalFixes.current().exportFixes().mapValues { (_, f) -> ProfileMerge.Counted(f.fix, f.count, f.lastUsed) },
            blocked = dev.patrickgold.florisboard.ime.nlp.latin.PersonalFixes.current().exportBlocked().mapValues { (k, _) -> ProfileMerge.DictWord(k, null, null) },
            confusion = dev.patrickgold.florisboard.ime.nlp.latin.PersonalFixes.current().exportConfusion().mapValues { (k, n) -> ProfileMerge.Counted(k, n, 0L) },
        )
    }

    private fun mergedTouch(local: String?, remote: String?): String {
        val m = TouchHabitModelCopy.of(local)
        if (!remote.isNullOrBlank()) m.merge(remote)
        return m.toJson()
    }

    private object TouchHabitModelCopy {
        fun of(json: String?) = dev.patrickgold.florisboard.ime.nlp.latin.TouchHabitModel().apply { if (!json.isNullOrBlank()) loadJson(json) }
    }

    private suspend fun applyLocally(context: Context, merged: Parsed, atMerge: Parsed) {
        PersonalWords.current(context, prefs).applyMerged(merged.words.orEmpty(), atMerge.words.orEmpty())
        LearnedWordsStore.applyMergedWords(context, merged.learned.orEmpty(), atMerge.learned.orEmpty())
        LearnedWordsStore.applyMergedBigrams(context, merged.pairs.orEmpty(), atMerge.pairs.orEmpty())
        // Personal dictionary: add what is new, remove what the merge dropped (if it is still as it was).
        val dm = DictionaryManager.default()
        dm.loadUserDictionariesIfNecessary()
        dm.florisUserDictionaryDao()?.let { dao ->
            val nowRows = dao.queryAll()
            val have = nowRows.associateBy { Parsed.dictKey(it.word, it.locale) }
            val target = merged.dictionary.orEmpty()
            for ((k, w) in target) {
                val cur = have[k]
                if (cur == null) {
                    runCatching { dao.insert(UserDictionaryEntry(0, w.word, 255, w.locale, w.shortcut)) }
                } else if (cur.shortcut != w.shortcut || cur.word != w.word) {
                    runCatching { dao.update(cur.copy(word = w.word, shortcut = w.shortcut)) }
                }
            }
            for ((k, cur) in have) {
                if (k !in target && atMerge.dictionary?.containsKey(k) == true) runCatching { dao.delete(cur) }
            }
        }
        // Fixes, blocked corrections and key mix-ups, keeping what was learned while the sync ran.
        run {
            val model = dev.patrickgold.florisboard.ime.nlp.latin.PersonalFixes.current()
            val nowFixes = model.exportFixes()
            val nowBlocked = model.exportBlocked()
            val nowConf = model.exportConfusion()
            val mf = merged.fixes.orEmpty()
            val af = atMerge.fixes.orEmpty()
            val fixes = HashMap<String, dev.patrickgold.florisboard.ime.nlp.latin.PersonalFixModel.Fix>()
            for ((k, m) in mf) {
                val count = ProfileMerge.keepWhileSyncing(m.count, af[k]?.count, nowFixes[k]?.count)
                fixes[k] = dev.patrickgold.florisboard.ime.nlp.latin.PersonalFixModel.Fix(k.substringBefore('\u0001'), m.word, count, maxOf(m.lastUsed, nowFixes[k]?.lastUsed ?: 0L))
            }
            for ((k, f) in nowFixes) if (k !in mf && k !in af) fixes[k] = f
            val blocked = HashMap<String, Long>()
            for (k in merged.blocked.orEmpty().keys) blocked[k] = nowBlocked[k] ?: 0L
            for ((k, t) in nowBlocked) if (k !in atMerge.blocked.orEmpty()) blocked[k] = t
            val conf = HashMap<String, Int>()
            val mc = merged.confusion.orEmpty()
            val ac = atMerge.confusion.orEmpty()
            for ((k, m) in mc) conf[k] = ProfileMerge.keepWhileSyncing(m.count, ac[k]?.count, nowConf[k])
            for ((k, n) in nowConf) if (k !in mc && k !in ac) conf[k] = n
            model.replaceAll(fixes, blocked, conf)
            dev.patrickgold.florisboard.ime.nlp.latin.PersonalFixes.save()
        }
        // Touch habits: the merged model.
        merged.touch?.let { TouchHabits.current().merge(it); TouchHabits.save() }
        // Settings: only those that differ, so nothing is rewritten for nothing.
        val current = SETTINGS.mapNotNull { (name, s) -> runCatching { name to s.read(prefs).toString() }.getOrNull() }.toMap()
        for ((name, v) in merged.settings.orEmpty()) {
            if (current[name] == v) continue
            val p = runCatching { Json.parseToJsonElement(v) as? JsonPrimitive }.getOrNull() ?: continue
            runCatching { SETTINGS[name]?.write?.invoke(prefs, p) }
        }
    }

    // ---- The base: this device's copy of the profile after its last sync, per person ---------------

    private fun baseFile(context: Context, who: String) =
        java.io.File(java.io.File(context.applicationContext.filesDir, "vk_learning").apply { mkdirs() }, "sync_base_$who.json")

    private fun readBase(context: Context, who: String): Parsed? = runCatching {
        val f = baseFile(context, who).takeIf { it.isFile } ?: return null
        Parsed.of(json.parseToJsonElement(f.readText()).jsonObject)
    }.getOrNull()

    private fun writeBase(context: Context, who: String, merged: Parsed, nowSec: Long) {
        runCatching {
            val f = baseFile(context, who)
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText(merged.toJson(nowSec).toString())
            tmp.renameTo(f)
        }
    }

    // ---- The phone's earlier learning ----------------------------------------------------------------

    /**
     * Whether this phone holds learning from while nobody was selected (or from before people could be
     * chosen) that has not been added to anyone. Shown as an offer; never added on its own.
     */
    fun hasUnassignedPhoneData(context: Context): Boolean {
        if (meta(context).getBoolean("phone_data_adopted", false)) return false
        val dbs = listOf(
            dev.patrickgold.florisboard.ime.dictionary.LearnedWordsDatabase.DB_FILE_NAME,
            dev.patrickgold.florisboard.ime.dictionary.FlorisUserDictionaryDatabase.DB_FILE_NAME,
        )
        return dbs.any { context.getDatabasePath(it).exists() } ||
            java.io.File(context.filesDir, "vk_learning/words_" + dev.patrickgold.florisboard.ime.nlp.latin.PersonalWordCounts.keyFor(null) + ".json").exists()
    }

    /**
     * Adds the phone's unassigned learning to the selected person (only what they do not have yet), then
     * syncs it to their server profile. Only when the user asks for it.
     */
    suspend fun adoptPhoneData(context: Context): Result {
        if (VkPersonFiles.personKey() == null) return Result.NO_PERSON
        val app = context.applicationContext
        runCatching {
            val legacy = dev.patrickgold.florisboard.ime.dictionary.LearnedWordsDatabase.new(app)
            try {
                val dao = legacy.dao()
                for (e in dao.allWords()) LearnedWordsStore.importWord(app, e.word, e.key, e.lang, e.count, e.lastUsed)
                for (e in dao.allBigrams()) LearnedWordsStore.importBigram(app, e.prev, e.word, e.lang, e.count, e.lastUsed)
            } finally {
                legacy.close()
            }
        }.onFailure { Log.i(TAG, "adopt learned words failed (${it.javaClass.simpleName})") }
        runCatching {
            val legacy = androidx.room.Room.databaseBuilder(
                app,
                dev.patrickgold.florisboard.ime.dictionary.FlorisUserDictionaryDatabase::class.java,
                dev.patrickgold.florisboard.ime.dictionary.FlorisUserDictionaryDatabase.DB_FILE_NAME,
            ).allowMainThreadQueries().build()
            try {
                val dm = DictionaryManager.default()
                dm.loadUserDictionariesIfNecessary()
                val dao = dm.florisUserDictionaryDao()
                if (dao != null) {
                    val have = dao.queryAll().map { it.word.lowercase() + "|" + it.locale.orEmpty() }.toHashSet()
                    for (e in legacy.userDictionaryDao().queryAll()) {
                        if (have.add(e.word.lowercase() + "|" + e.locale.orEmpty())) {
                            dao.insert(UserDictionaryEntry(0, e.word, e.freq, e.locale, e.shortcut))
                        }
                    }
                }
            } finally {
                legacy.close()
            }
        }.onFailure { Log.i(TAG, "adopt dictionary failed (${it.javaClass.simpleName})") }
        runCatching {
            val unassigned = dev.patrickgold.florisboard.ime.nlp.latin.PersonalWordCounts(java.io.File(app.filesDir, "vk_learning"))
                .apply { useUser(null) }
            PersonalWords.current(app, prefs).merge(unassigned.export())
        }
        runCatching {
            TouchHabits.unassignedJson().takeIf { it.isNotBlank() }?.let { TouchHabits.current().merge(it); TouchHabits.save() }
        }
        meta(app).edit().putBoolean("phone_data_adopted", true).apply()
        return if (enabled()) syncNow(app) else Result.OK
    }

    private fun meta(context: Context) = context.applicationContext.getSharedPreferences(META, Context.MODE_PRIVATE)

    /** "Forget everything": the next sync starts as the first one again. */
    fun forgetLocalState(context: Context) {
        meta(context).edit().clear().apply()
        java.io.File(context.applicationContext.filesDir, "vk_learning").listFiles { f -> f.name.startsWith("sync_base_") }
            ?.forEach { it.delete() }
    }
}
