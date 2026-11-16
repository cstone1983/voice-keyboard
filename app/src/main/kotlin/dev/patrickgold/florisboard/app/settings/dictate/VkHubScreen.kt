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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.enumDisplayEntriesOf
import dev.patrickgold.florisboard.app.setup.OwnServerEditHandoff
import dev.patrickgold.florisboard.ime.nlp.latin.AutoCorrectStrength
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.vk.learning.VkLearning
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.DialogSliderPreference
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference

/**
 * Voice Keyboard: one page for what this build adds — the user's own AI server, learning, autocorrect
 * and split typing, and dictation speed — instead of spreading it over several menus.
 */
@Composable
fun VkHubScreen() = FlorisScreen {
    title = "Voice Keyboard"
    previewFieldVisible = true
    iconSpaceReserved = true

    val prefs by FlorisPreferenceStore

    content {
        val navController = LocalNavController.current
        val providerId by prefs.dictate.transcriptionProviderId.collectAsState()
        val accounts by prefs.dictate.providerAccounts.collectAsState()
        val signedIn by prefs.dictate.vkSignedInUser.collectAsState()
        val personalModel by prefs.dictate.vkPersonalSttModel.collectAsState()
        val context = LocalContext.current
        val syncScope = rememberCoroutineScope()
        var syncing by remember { mutableStateOf(false) }
        val lastSync by dev.patrickgold.florisboard.vk.learning.VkPersonSync.status.collectAsState()
        val active = accounts[providerId]?.takeIf { it.isCustom }

        PreferenceGroup(title = "Your AI server") {
            Preference(
                icon = Icons.Default.Dns,
                title = if (active != null) "Server" else "Connect your own server",
                summary = if (active != null) {
                    listOfNotNull(
                        signedIn.takeIf { it.isNotBlank() }?.let { "Signed in as ${VkLearning.displayName(it)}" },
                        active.customBaseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/'),
                        active.transcriptionModel.takeIf { it.isNotBlank() }?.substringAfterLast('/'),
                        active.chatModel.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                } else {
                    "Scan the server's QR code or enter its address and key"
                },
                onClick = {
                    OwnServerEditHandoff.editId = active?.providerId
                    navController.navigate(Routes.Settings.DictateOwnServer)
                },
            )
            Preference(
                icon = Icons.Default.Dns,
                title = "All AI providers",
                summary = "Other servers and cloud providers",
                onClick = { navController.navigate(Routes.Settings.DictateProviders) },
            )
        }

        PreferenceGroup(title = "Learning") {
            Preference(
                icon = Icons.Default.RecordVoiceOver,
                title = "Voice training",
                summary = "Read sentences aloud so your server learns your voice (counts toward 120 minutes)",
                onClick = { navController.navigate(Routes.Settings.VkTraining) },
            )
            SwitchPreference(
                prefs.dictate.vkLearnCorrections,
                icon = Icons.Default.School,
                title = "Learn from my corrections",
                summary = "Words you fix after dictating are learned on your server",
            )
            SwitchPreference(
                prefs.dictate.vkLearnStyle,
                title = "Learn my writing style",
                summary = "Sent messages are kept on your server so cleanup matches how you write",
                iconSpaceReserved = true,
            )
            SwitchPreference(
                prefs.dictate.vkLearnVoice,
                title = "Help it learn my voice",
                summary = "Dictation audio is kept on your server (up to a year) to train recognition on your voice",
                iconSpaceReserved = true,
            )
            SwitchPreference(
                prefs.dictate.vkProfileSync,
                title = "Keep my keyboard profile on my server",
                summary = "Your words, touch habits and typing settings follow you to any phone or tablet",
                iconSpaceReserved = true,
            )
            Preference(
                title = "Sync my profile now",
                summary = when {
                    syncing -> "Syncing…"
                    lastSync.isNotBlank() -> lastSync
                    else -> "Brings your profile down and sends this phone's changes up"
                },
                iconSpaceReserved = true,
                enabledIf = { prefs.dictate.vkProfileSync isEqualTo true },
                onClick = {
                    syncing = true
                    syncScope.launch {
                        dev.patrickgold.florisboard.vk.learning.VkPersonSync.syncNow(context.applicationContext)
                        syncing = false
                    }
                },
            )
            SwitchPreference(
                prefs.dictate.vkUsePersonalModel,
                title = "Use my own voice model",
                summary = personalModel.substringAfter('|', "").takeIf { it.isNotBlank() }
                    ?.let { "Ready: $it" } ?: "Used once your server has trained one for you",
                iconSpaceReserved = true,
            )
            Preference(
                title = "Progress, learned words, privacy",
                summary = "Accuracy over time, voice minutes, who is using the keyboard, forget everything",
                iconSpaceReserved = true,
                onClick = { navController.navigate(Routes.Settings.VkLearning) },
            )
        }

        PreferenceGroup(title = "Typing & autocorrect") {
            SwitchPreference(
                prefs.suggestion.autoCorrect,
                icon = Icons.Default.Spellcheck,
                title = "Autocorrect",
                summary = "Fix mistyped words as you type",
            )
            SwitchPreference(
                prefs.dictate.vkPersonalWordRanking,
                title = "Suggest my most-used words first",
                summary = "Counts the words you type and dictate, on this phone, for each person",
                iconSpaceReserved = true,
            )
            ListPreference(
                prefs.correction.autoCorrectStrength,
                title = "Autocorrect strength",
                entries = enumDisplayEntriesOf(AutoCorrectStrength::class),
                iconSpaceReserved = true,
                enabledIf = { prefs.suggestion.autoCorrect isEqualTo true },
            )
            SwitchPreference(
                prefs.keyboard.vkSplitTouchCorrection,
                icon = Icons.Default.ViewColumn,
                title = "Split typing correction",
                summary = "On the split keyboard, count a touch where your thumb was aiming",
            )
            DialogSliderPreference(
                prefs.keyboard.vkSplitTouchSpreadPercent,
                title = "Reach correction (outward)",
                valueLabel = { "$it %" },
                min = 0,
                max = 40,
                stepIncrement = 5,
                iconSpaceReserved = true,
                enabledIf = { prefs.keyboard.vkSplitTouchCorrection isEqualTo true },
            )
            DialogSliderPreference(
                prefs.keyboard.vkSplitTouchLiftRightPercent,
                title = "Right thumb lift (of a row)",
                valueLabel = { "$it %" },
                min = 0,
                max = 60,
                stepIncrement = 5,
                iconSpaceReserved = true,
                enabledIf = { prefs.keyboard.vkSplitTouchCorrection isEqualTo true },
            )
            SwitchPreference(
                prefs.keyboard.vkTouchHabits,
                title = "Learn my touch habits",
                summary = "Learns where your thumbs land on each key and reads touches the way you aim",
                iconSpaceReserved = true,
            )
            SwitchPreference(
                prefs.keyboard.vkGlideWhenSplit,
                title = "Swipe typing on the split keyboard",
                summary = "Off by default: quick taps were read as swipes",
                iconSpaceReserved = true,
            )
            Preference(
                title = "All typing settings",
                iconSpaceReserved = true,
                onClick = { navController.navigate(Routes.Settings.Typing) },
            )
        }

        PreferenceGroup(title = "Dictation") {
            SwitchPreference(
                prefs.dictate.vkHoldTextUntilDone,
                icon = Icons.Default.Mic,
                title = "Insert only the final text",
                summary = "Keep dictated text out of the field until transcription and cleanup are done",
            )
            SwitchPreference(
                prefs.dictate.vkVocabularyHint,
                title = "Spell my names and words",
                summary = "Sends your most-used names and terms with each dictation so they are spelled your way",
                iconSpaceReserved = true,
            )
            Preference(
                icon = Icons.Default.Timer,
                title = "Dictation timing",
                summary = "Per-chunk transcription time and time from stop to final text",
                onClick = { navController.navigate(Routes.Settings.DictateTiming) },
            )
            Preference(
                title = "All dictation settings",
                summary = "Language, chunked dictation, prompts, history",
                iconSpaceReserved = true,
                onClick = { navController.navigate(Routes.Settings.Dictate) },
            )
        }
    }
}
