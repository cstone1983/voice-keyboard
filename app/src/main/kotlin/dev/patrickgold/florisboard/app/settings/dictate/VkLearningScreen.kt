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
            Text(
                "Everything is stored only on your own server. Password fields and excluded apps are never read.",
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

        if (confirmForget) {
            AlertDialog(
                onDismissRequest = { confirmForget = false },
                title = { Text("Forget everything?") },
                text = { Text("Deletes all learned words, rules, corrections and stored messages on your server. This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmForget = false
                        scope.launch {
                            val a = api() ?: return@launch
                            val ok = runCatching { withContext(Dispatchers.IO) { a.delete("vk/data") } }.isSuccess
                            if (ok) {
                                VkLearning.clearLocal()
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
    return out
}
