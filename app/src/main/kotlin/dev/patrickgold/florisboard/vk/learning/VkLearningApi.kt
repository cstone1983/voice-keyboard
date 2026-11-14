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

import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Voice Keyboard: the learning endpoints of the user's own server (`<base_url>vk/...`), with the same
 * Bearer key as transcription. The server decides the person from the key; nothing else identifies them.
 * Blocking calls; run them off the main thread. Never logs bodies or the key.
 */
class VkLearningApi(private val baseUrl: String, private val apiKey: String) {

    class HttpException(val status: Int, message: String) : IOException(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun request(path: String): Request.Builder = Request.Builder()
        .url(baseUrl + path)
        .header("Authorization", "Bearer $apiKey")

    private fun run(req: Request): String = client.newCall(req).execute().use { resp ->
        val body = resp.body.string()
        if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code}")
        body
    }

    fun get(path: String): String = run(request(path).get().build())

    fun post(path: String, json: String): String =
        run(request(path).post(json.toRequestBody(JSON)).build())

    fun delete(path: String): String = run(request(path).delete().build())

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * The client for the active transcription account, when it is a server of the user's own (a custom
         * endpoint with an address and a key); null otherwise — learning only ever talks to that server.
         */
        fun forActiveServer(prefs: FlorisPreferenceModel): VkLearningApi? {
            val id = prefs.dictate.transcriptionProviderId.get()
            val account = prefs.dictate.providerAccounts.get()[id] ?: return null
            if (!account.isCustom || account.customBaseUrl.isBlank() || account.apiKey.isBlank()) return null
            if (ProviderRegistry.byId(id) != null) return null
            val base = account.customBaseUrl.let { if (it.endsWith("/")) it else "$it/" }
            return VkLearningApi(base, account.apiKey)
        }
    }
}
