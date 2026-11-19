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

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.vk.learning.VkLearning
import dev.patrickgold.florisboard.vk.learning.VkLearningApi
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private data class LearnedItem(val id: String, val label: String)

/** Voice Keyboard: what the keyboard learns, and the controls to see, export and wipe it. */
@Composable
fun VkLearningScreen() = FlorisScreen {
    title = "Learning"
    previewFieldVisible = true

    val prefs by FlorisPreferenceStore

    content {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val learnCorrections by prefs.dictate.vkLearnCorrections.collectAsState()
        val learnStyle by prefs.dictate.vkLearnStyle.collectAsState()
        val excluded by prefs.dictate.vkLearningExcludedApps.collectAsState()
        val user by prefs.dictate.vkSignedInUser.collectAsState()
        var items by remember { mutableStateOf<List<LearnedItem>?>(null) }
        var styles by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var stats by remember { mutableStateOf<VkStats?>(null) }
        var confirmDeleteVoice by remember { mutableStateOf(false) }
        val learnVoice by prefs.dictate.vkLearnVoice.collectAsState()
        val voiceWifiOnly by prefs.dictate.vkVoiceWifiOnly.collectAsState()
        var listError by remember { mutableStateOf<String?>(null) }
        var confirmForget by remember { mutableStateOf(false) }
        var excludedText by remember(excluded) { mutableStateOf(excluded) }
        val actingUser by prefs.dictate.vkActingUser.collectAsState()
        var people by remember { mutableStateOf<List<dev.patrickgold.florisboard.vk.learning.VkIdentity.Person>>(emptyList()) }
        var showPicker by remember { mutableStateOf(false) }

        fun api(): VkLearningApi? = VkLearningApi.forActiveServer(prefs)

        fun loadLearned() {
            scope.launch {
                val a = api()
                if (a == null) { listError = "Connect your own server first."; return@launch }
                runCatching { withContext(Dispatchers.IO) { a.get("vk/learned") } }
                    .onSuccess { body ->
                        listError = null
                        items = parseLearned(body)
                        styles = runCatching { parseStyles(body) }.getOrDefault(emptyList())
                    }
                    .onFailure {
                        if (dev.patrickgold.florisboard.vk.learning.VkIdentity.needsSelection(it)) {
                            showPicker = true
                            listError = "Choose who's using this keyboard."
                        } else {
                            listError = "Could not load (${it.message ?: it.javaClass.simpleName})."
                        }
                    }
            }
        }

        LaunchedEffect(Unit) {
            api()?.let { a ->
                people = withContext(Dispatchers.IO) {
                    runCatching { dev.patrickgold.florisboard.vk.learning.VkIdentity.users(a) }.getOrDefault(emptyList())
                }
            }
            val name = withContext(Dispatchers.IO) { VkLearning.refreshSignedIn() }
            if (name == null && people.isNotEmpty()) showPicker = true
            loadLearned()
        }

        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val a = api() ?: return@launch
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        val body = a.get("vk/export")
                        context.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                    }
                }.isSuccess
                Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show()
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        actingUser.isNotBlank() && user.isNotBlank() -> "Signed in as ${VkLearning.displayName(user)}"
                        api() == null -> "Not signed in (connect your own server)"
                        else -> "Nobody selected"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            // Who's using this keyboard: always visible. A list from the server when it has one, otherwise
            // the user name typed in (the household key may act as any person the server knows).
            fun choose(person: dev.patrickgold.florisboard.vk.learning.VkIdentity.Person) {
                scope.launch {
                    dev.patrickgold.florisboard.vk.learning.VkIdentity.select(prefs, person)
                    VkLearning.clearProfiles()
                    val name = withContext(Dispatchers.IO) { VkLearning.refreshSignedIn() }
                    Toast.makeText(
                        context,
                        if (name != null) "Signed in as $name" else "The server did not accept that user name",
                        Toast.LENGTH_SHORT,
                    ).show()
                    loadLearned()
                }
            }
            Spacer(Modifier.height(8.dp))
            if (people.isNotEmpty()) {
                dev.patrickgold.florisboard.vk.learning.VkUserPicker(
                    people = people,
                    selected = actingUser.takeIf { it.isNotBlank() },
                    onPick = { choose(it) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                var typed by remember(actingUser) { mutableStateOf(actingUser) }
                Text("Who's using this keyboard?", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Your user name on the server") },
                        singleLine = true,
                    )
                    TextButton(
                        enabled = typed.isNotBlank(),
                        onClick = {
                            val id = typed.trim().lowercase()
                            choose(dev.patrickgold.florisboard.vk.learning.VkIdentity.Person(id, VkLearning.displayName(id)))
                        },
                    ) { Text("Use") }
                }
            }
            // The selected person's profile is downloaded when they are chosen; say how that went.
            val syncStatus by dev.patrickgold.florisboard.vk.learning.VkPersonSync.status.collectAsState()
            if (actingUser.isNotBlank() && syncStatus.isNotBlank()) {
                Text("Profile: $syncStatus", style = MaterialTheme.typography.bodySmall)
            }
            // Learning from before people could be chosen belongs to nobody until the user says whose it is.
            var offerAdopt by remember(actingUser) {
                mutableStateOf(actingUser.isNotBlank() && dev.patrickgold.florisboard.vk.learning.VkPersonSync.hasUnassignedPhoneData(context))
            }
            if (offerAdopt) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "This phone has learning from before anyone was selected (learned words, personal dictionary). " +
                        "It is not part of anyone's profile.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = {
                    offerAdopt = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            dev.patrickgold.florisboard.vk.learning.VkPersonSync.adoptPhoneData(context.applicationContext)
                        }
                        Toast.makeText(context, "Added to ${VkLearning.displayName(actingUser)}'s profile", Toast.LENGTH_SHORT).show()
                        loadLearned()
                    }
                }) { Text("Add it to ${VkLearning.displayName(actingUser)}'s profile") }
            }
            Text(
                "Your profile is kept on your own server and on this phone for the person selected. " +
                    "Password fields and excluded apps are never read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            SwitchRow(
                title = "Learn from my corrections",
                summary = "After a dictation, the words you fix are sent to your server so the same mishear comes out right next time",
                checked = learnCorrections,
            ) { scope.launch { prefs.dictate.vkLearnCorrections.set(it) } }
            SwitchRow(
                title = "Learn my writing style",
                summary = "Sent dictated messages are stored on your server (1 year) so cleanup can match how you write",
                checked = learnStyle,
            ) { scope.launch { prefs.dictate.vkLearnStyle.set(it) } }
            SwitchRow(
                title = "Help it learn my voice",
                summary = "Stores the audio of your dictations on your server for up to a year, to train speech " +
                    "recognition on your voice. Off by default.",
                checked = learnVoice,
            ) { scope.launch { prefs.dictate.vkLearnVoice.set(it) } }
            if (learnVoice) {
                SwitchRow(
                    title = "Upload voice clips on Wi-Fi only",
                    summary = "Clips wait on the phone until you are on Wi-Fi (or an unmetered network)",
                    checked = voiceWifiOnly,
                ) { scope.launch { prefs.dictate.vkVoiceWifiOnly.set(it) } }
            }
            TextButton(onClick = { confirmDeleteVoice = true }) { Text("Delete my voice clips") }

            // Progress: accuracy over time and voice minutes (GET vk/stats).
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Progress", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        val a = api() ?: return@launch
                        stats = runCatching { withContext(Dispatchers.IO) { parseStats(a.get("vk/stats")) } }.getOrNull()
                    }
                }) { Text("Refresh") }
            }
            LaunchedEffect(actingUser) {
                val a = api() ?: return@LaunchedEffect
                stats = runCatching { withContext(Dispatchers.IO) { parseStats(a.get("vk/stats")) } }.getOrNull()
            }
            VkStatsView(stats)

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = excludedText,
                onValueChange = { excludedText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Excluded apps (package names, one per line)") },
                minLines = 2,
            )
            TextButton(onClick = { scope.launch { prefs.dictate.vkLearningExcludedApps.set(excludedText.trim()) } }) {
                Text("Save excluded apps")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Learned words", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { loadLearned() }) { Text("Refresh") }
            }
            listError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val list = items
            if (list != null && list.isEmpty()) {
                Text("Nothing learned yet.", style = MaterialTheme.typography.bodyMedium)
            }
            list?.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(item.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = {
                        scope.launch {
                            val a = api() ?: return@launch
                            val ok = runCatching {
                                withContext(Dispatchers.IO) { a.delete("vk/learned/" + java.net.URLEncoder.encode(item.id, "UTF-8")) }
                            }.isSuccess
                            if (ok) items = items?.filterNot { it.id == item.id } else listError = "Could not delete."
                        }
                    }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                }
            }

            if (styles.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Your writing style (learned)", style = MaterialTheme.typography.titleSmall)
                styles.forEach { (category, style) ->
                    Text(category.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge)
                    Text(style, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Voice Keyboard: what the person's own typing fixes taught the keyboard (on this phone and synced).
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            var fixesVersion by remember { mutableStateOf(0) }
            val fixModel = remember(actingUser, fixesVersion) { dev.patrickgold.florisboard.ime.nlp.latin.PersonalFixes.current() }
            val fixList = remember(actingUser, fixesVersion) { fixModel.allFixes().take(40) }
            val blockedList = remember(actingUser, fixesVersion) { fixModel.allBlocked().take(20) }
            val mixUps = remember(actingUser, fixesVersion) { fixModel.topConfusions(6) }
            fun changedFixes() {
                fixesVersion++
                scope.launch(Dispatchers.IO) { dev.patrickgold.florisboard.ime.nlp.latin.PersonalFixes.save() }
                dev.patrickgold.florisboard.vk.learning.VkPersonSync.syncSoon(context.applicationContext)
            }
            Text("Fixes learned from your typing", style = MaterialTheme.typography.titleMedium)
            Text(
                "Words you changed by hand. Offered from the first time, corrected automatically from the second.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (fixList.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodyMedium)
            fixList.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "${f.typo} → ${f.fix}" + if (f.count >= dev.patrickgold.florisboard.ime.nlp.latin.PersonalFixModel.AUTO_AFTER) "  (automatic, ${f.count}×)" else "  (suggested)",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = { fixModel.removeFix(f.typo, f.fix); changedFixes() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            }
            if (blockedList.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Corrections you took back (never made again)", style = MaterialTheme.typography.titleSmall)
                blockedList.forEach { (typed, wrong) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("$typed ↛ $wrong", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { fixModel.unblock(typed, wrong); changedFixes() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
            if (mixUps.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Keys you mix up most", style = MaterialTheme.typography.titleSmall)
                Text(
                    mixUps.joinToString("   ") { (k, n) ->
                        val typed = k[0].let { if (it == ' ') "space" else it.toString() }
                        val meant = k[2].let { if (it == ' ') "space" else it.toString() }
                        "$typed for $meant ($n)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { exportLauncher.launch("voice-keyboard-learning.json") }, modifier = Modifier.weight(1f)) {
                    Text("Export")
                }
                Button(onClick = { confirmForget = true }, modifier = Modifier.weight(1f)) {
                    Text("Forget everything")
                }
            }
        }

        if (confirmDeleteVoice) {
            AlertDialog(
                onDismissRequest = { confirmDeleteVoice = false },
                title = { Text("Delete my voice clips?") },
                text = { Text("Deletes all voice clips on your server and the ones still waiting on this phone.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDeleteVoice = false
                        scope.launch {
                            VkLearning.deleteLocalClips()
                            val a = api()
                            val ok = a != null && runCatching { withContext(Dispatchers.IO) { a.delete("vk/audio") } }.isSuccess
                            Toast.makeText(context, if (ok) "Voice clips deleted" else "Deleted on the phone; the server could not be reached", Toast.LENGTH_SHORT).show()
                            if (a != null) stats = runCatching { withContext(Dispatchers.IO) { parseStats(a.get("vk/stats")) } }.getOrNull()
                        }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirmDeleteVoice = false }) { Text("Cancel") } },
            )
        }

        if (confirmForget) {
            AlertDialog(
                onDismissRequest = { confirmForget = false },
                title = { Text("Forget everything?") },
                text = { Text("Deletes all learned words, rules, corrections, dictations, styles, stored messages and voice clips on your server, and this person's word counts, touch habits and learned fixes on this phone. This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmForget = false
                        scope.launch {
                            val a = api() ?: return@launch
                            val ok = runCatching { withContext(Dispatchers.IO) { a.delete("vk/data") } }.isSuccess
                            if (ok) {
                                VkLearning.clearLocal()
                                withContext(Dispatchers.IO) {
                                    dev.patrickgold.florisboard.ime.nlp.latin.PersonalWords.current(context, prefs).clear()
                                    dev.patrickgold.florisboard.ime.nlp.latin.TouchHabits.clear()
                                    dev.patrickgold.florisboard.ime.nlp.latin.PersonalFixes.clear()
                                    dev.patrickgold.florisboard.vk.learning.VkPersonSync.forgetLocalState(context)
                                }
                                items = emptyList()
                            }
                            Toast.makeText(context, if (ok) "Everything forgotten" else "Could not reach the server", Toast.LENGTH_SHORT).show()
                            if (ok) loadLearned()
                        }
                    }) { Text("Forget everything") }
                },
                dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** `{"vocabulary":[...],"replacements":[...]}`; each item has an "id" and either a word or from/to. */
private fun parseLearned(body: String): List<LearnedItem> {
    val obj = Json.parseToJsonElement(body).jsonObject
    fun s(o: JsonObject, vararg keys: String) =
        keys.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull?.takeIf { v -> v.isNotBlank() } }
    val out = mutableListOf<LearnedItem>()
    (obj["vocabulary"] as? JsonArray)?.forEach { el ->
        when (el) {
            is JsonObject -> {
                val id = s(el, "id") ?: return@forEach
                out.add(LearnedItem(id, s(el, "word", "term", "text", "value") ?: id))
            }
            else -> Unit
        }
    }
    (obj["replacements"] as? JsonArray)?.forEach { el ->
        val o = el as? JsonObject ?: return@forEach
        val id = s(o, "id") ?: return@forEach
        val count = s(o, "count")?.let { " (×$it)" }.orEmpty()
        out.add(LearnedItem(id, "${s(o, "from").orEmpty()} → ${s(o, "to").orEmpty()}$count"))
    }
    (obj["avoid"] as? JsonArray)?.forEach { el ->
        val o = el as? JsonObject ?: return@forEach
        val id = s(o, "id") ?: return@forEach
        out.add(LearnedItem(id, "Cleanup must not add: \"${s(o, "word", "text", "value").orEmpty()}\""))
    }
    return out
}

/** `styles: [{category, style, updated}]`, shown read-only. */
private fun parseStyles(body: String): List<Pair<String, String>> {
    val obj = Json.parseToJsonElement(body).jsonObject
    return (obj["styles"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val style = (o["style"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ((o["category"] as? JsonPrimitive)?.contentOrNull ?: "other") to style
    }
}

private data class VkDay(val day: String, val dictations: Int, val accuracy: Double?)
private data class VkStats(
    val days: List<VkDay>,
    val totalAccuracy: Double?,
    val totalDictations: Int,
    val clips: Int,
    val minutes: Double,
    val fineTuneReady: Boolean,
)

private fun parseStats(body: String): VkStats {
    val obj = Json.parseToJsonElement(body).jsonObject
    fun num(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
    val days = (obj["days"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        VkDay(
            day = (o["day"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            dictations = num(o, "dictations")?.toInt() ?: 0,
            accuracy = num(o, "accuracy_pct"),
        )
    }
    val totals = obj["totals"] as? JsonObject
    val audio = obj["audio"] as? JsonObject
    return VkStats(
        days = days,
        totalAccuracy = num(totals, "accuracy_pct"),
        totalDictations = num(totals, "dictations")?.toInt() ?: days.sumOf { it.dictations },
        clips = num(audio, "clips")?.toInt() ?: 0,
        minutes = num(audio, "minutes") ?: 0.0,
        fineTuneReady = (audio?.get("fine_tune_ready") as? JsonPrimitive)?.contentOrNull == "true",
    )
}

/** Accuracy per day as bars, totals, and voice minutes toward the 120 needed for training. */
@Composable
private fun VkStatsView(stats: VkStats?) {
    if (stats == null) {
        Text("No progress yet (or the server could not be reached).", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(
        "Accuracy: ${stats.totalAccuracy?.let { "%.1f %%".format(it) } ?: "–"} over ${stats.totalDictations} dictations",
        style = MaterialTheme.typography.bodyMedium,
    )
    stats.days.takeLast(14).forEach { d ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(d.day.takeLast(5), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(48.dp))
            val frac = ((d.accuracy ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
            Box(modifier = Modifier.weight(1f).height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(modifier = Modifier.fillMaxWidth(frac).height(12.dp).background(MaterialTheme.colorScheme.primary))
            }
            Text(
                " ${d.accuracy?.let { "%.0f%%".format(it) } ?: "–"} · ${d.dictations}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Voice clips: %.1f min (%d clips)".format(stats.minutes, stats.clips),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        if (stats.fineTuneReady) "Voice training: ready" else "Voice training: %.0f of 120 minutes collected".format(stats.minutes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
