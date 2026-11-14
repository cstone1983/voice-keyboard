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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Voice Keyboard: who is using this keyboard, for the learning API. The ONE place that decides it.
 *
 * Today the server key is a household key and the person is picked from `GET vk/users`; the choice is
 * sent as `X-VK-As` on every `vk/` request (speech-to-text and cleanup do not need it). A real sign-in
 * later replaces [currentUser] / [select] / [users] and nothing else.
 */
object VkIdentity {
    const val HEADER = "X-VK-As"

    data class Person(val user: String, val name: String)

    /** The selected person's id ("chris"), or null when nobody is selected yet. */
    fun currentUser(prefs: FlorisPreferenceModel): String? =
        prefs.dictate.vkActingUser.get().takeIf { it.isNotBlank() }

    suspend fun select(prefs: FlorisPreferenceModel, person: Person?) {
        prefs.dictate.vkActingUser.set(person?.user.orEmpty())
        prefs.dictate.vkSignedInUser.set(person?.name.orEmpty())
    }

    /** `GET vk/users` (blocking): the people this key may act as. */
    fun users(api: VkLearningApi): List<Person> {
        val obj = Json.parseToJsonElement(api.get("vk/users", actAs = null)).jsonObject
        return (obj["users"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val user = (o["user"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: user
            Person(user, name)
        }
    }

    /** True for the server's "select a user" answer: the picker has to be shown. */
    fun needsSelection(e: Throwable): Boolean = e is VkLearningApi.HttpException && e.status == 400
}
