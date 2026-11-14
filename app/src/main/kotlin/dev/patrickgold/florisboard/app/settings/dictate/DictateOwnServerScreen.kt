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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.setup.OwnServerEditHandoff
import dev.patrickgold.florisboard.app.setup.OwnServerSetup
import dev.patrickgold.florisboard.app.setup.saveOwnServer
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.launch

/**
 * Voice Keyboard: add or edit the user's own server (QR code or address + key, then models). Editing keeps
 * the stored key unless it is changed; the old full editor stays reachable as "Advanced settings".
 */
@Composable
fun DictateOwnServerScreen() = FlorisScreen {
    val editId = remember { OwnServerEditHandoff.editId.also { OwnServerEditHandoff.editId = null } }
    title = if (editId == null) "Your own server" else "Edit your server"
    previewFieldVisible = false

    val prefs by FlorisPreferenceStore

    content {
        val navController = LocalNavController.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val accounts by prefs.dictate.providerAccounts.collectAsState()
        // Read once: the form keeps its own state while open.
        val existing = remember(editId) { editId?.let { prefs.dictate.providerAccounts.get()[it] } }
        var showAdvanced by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf(false) }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OwnServerSetup(
                existing = existing,
                onSave = { choice ->
                    scope.launch {
                        prefs.saveOwnServer(choice)
                        val user = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            dev.patrickgold.florisboard.vk.learning.VkLearning.refreshSignedIn()
                        }
                        val who = user?.let { " · Signed in as ${dev.patrickgold.florisboard.vk.learning.VkLearning.displayName(it)}" }.orEmpty()
                        Toast.makeText(context, "Server saved and selected$who", Toast.LENGTH_LONG).show()
                        navController.popBackStack()
                    }
                },
                onBack = null,
            )
            if (existing != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { showAdvanced = true }) { Text("Advanced settings") }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete server", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (showAdvanced && existing != null) {
            ProviderEditorDialog(
                preset = null,
                account = accounts[existing.providerId] ?: existing,
                onDismiss = { showAdvanced = false },
                onSave = { updated, _ ->
                    scope.launch { prefs.dictate.providerAccounts.set(prefs.dictate.providerAccounts.get().put(updated)) }
                    showAdvanced = false
                    navController.popBackStack()
                },
                onDelete = null,
            )
        }
        if (confirmDelete && existing != null) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete this server?") },
                text = { Text("Its address and API key are removed from this phone.") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            prefs.dictate.providerAccounts.set(prefs.dictate.providerAccounts.get().remove(existing.providerId))
                            navController.popBackStack()
                        }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
    }
}
