/*
 * Copyright (C) 2026 Voice Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed class Step {
    data object Searching : Step()
    data class Pick(val found: List<LanPairing.Found>) : Step()
    data class Loading(val server: LanPairing.Found) : Step()
    data class Choose(val server: LanPairing.Found, val info: LanPairing.Info) : Step()
    data object Requesting : Step()
    data class Waiting(val pending: LanPairing.Pending) : Step()
    data class Message(val text: String, val retry: Boolean) : Step()
}

/**
 * Voice Keyboard: "Find my server on Wi-Fi" (see [LanPairing]). Ends in [onApproved] with the setup
 * code (same form as the QR code) and the person's id once the owner approved the request; until then
 * it only shows that it is waiting — nothing is saved or used. A request still waiting when this is
 * closed is remembered, and reopening continues to wait for it.
 */
@Composable
fun LanPairingDialog(onApproved: (setupCode: String, person: String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(LanPairing.loadPending(context)?.let { Step.Waiting(it) } ?: Step.Searching) }
    var chosen by remember { mutableStateOf<String?>(null) }   // person id, or null = new person
    var newName by remember { mutableStateOf("") }
    var device by remember { mutableStateOf(LanPairing.deviceName()) }

    fun search() {
        step = Step.Searching
        scope.launch {
            val found = LanPairing.discover(context)
            step = when (found.size) {
                0 -> Step.Message(
                    "No voice server found on this Wi-Fi. Make sure the phone is on the home network " +
                        "(not mobile data or a guest network), or scan the server's QR code instead.",
                    retry = true,
                )
                1 -> Step.Loading(found[0])
                else -> Step.Pick(found)
            }
        }
    }

    // What each step does on its own.
    LaunchedEffect(step) {
        when (val s = step) {
            is Step.Searching -> if (LanPairing.loadPending(context) == null) search()
            is Step.Loading -> {
                step = runCatching { LanPairing.info(s.server.pairUrl) }.fold(
                    onSuccess = { info ->
                        chosen = info.people.firstOrNull()?.user
                        Step.Choose(s.server, info)
                    },
                    onFailure = { Step.Message("The server was found but did not answer the setup request.", retry = true) },
                )
            }
            is Step.Waiting -> {
                // Asks every few seconds; nothing is set up before the answer is "approved".
                while (true) {
                    when (val st = LanPairing.status(s.pending)) {
                        is LanPairing.Status.Approved -> {
                            LanPairing.savePending(context, null)
                            onApproved(st.setupCode, s.pending.person.takeIf { it.isNotBlank() })
                            return@LaunchedEffect
                        }
                        LanPairing.Status.Denied -> {
                            LanPairing.savePending(context, null)
                            step = Step.Message("The request was denied.", retry = false)
                            return@LaunchedEffect
                        }
                        LanPairing.Status.Expired -> {
                            LanPairing.savePending(context, null)
                            step = Step.Message("The request expired before it was approved. Try again.", retry = true)
                            return@LaunchedEffect
                        }
                        else -> delay(3000) // waiting, or the server briefly unreachable: keep asking
                    }
                }
            }
            else -> Unit
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (step is Step.Waiting) "Waiting for approval" else "Find my server on Wi-Fi") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (val s = step) {
                    Step.Searching, is Step.Loading, Step.Requesting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text(
                            when (s) {
                                is Step.Loading -> "Connecting to ${s.server.name}…"
                                Step.Requesting -> "Asking for access…"
                                else -> "Looking for your voice server on this Wi-Fi…"
                            },
                        )
                    }
                    is Step.Pick -> {
                        Text("Several servers were found:")
                        s.found.forEach { f -> TextButton(onClick = { step = Step.Loading(f) }) { Text(f.name) } }
                    }
                    is Step.Choose -> {
                        Text("${s.info.name}: who is using this phone?", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        s.info.people.forEach { p ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().selectable(selected = chosen == p.user, onClick = { chosen = p.user }),
                            ) {
                                RadioButton(selected = chosen == p.user, onClick = { chosen = p.user })
                                Text(p.name)
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().selectable(selected = chosen == null, onClick = { chosen = null }),
                        ) {
                            RadioButton(selected = chosen == null, onClick = { chosen = null })
                            Text("Someone new")
                        }
                        if (chosen == null) {
                            OutlinedTextField(
                                value = newName, onValueChange = { newName = it.take(32) },
                                label = { Text("Their name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = device, onValueChange = { device = it.take(40) },
                            label = { Text("This device") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The server owner gets a Telegram message to approve this. Nothing is set up until then.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is Step.Waiting -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                            Text("Approve it in Telegram: ${s.pending.person.ifBlank { "new person" }} on ${s.pending.device}.")
                        }
                        if (s.pending.code.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text("Code", style = MaterialTheme.typography.labelMedium)
                            Text(s.pending.code, style = MaterialTheme.typography.headlineMedium)
                            Text("Check that the Telegram message shows the same code.", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "The keyboard is not set up, used or synced until this is approved. You can close this; " +
                                "it keeps waiting and finishes when you come back.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is Step.Message -> Text(s.text)
                }
            }
        },
        confirmButton = {
            when (val s = step) {
                is Step.Choose -> TextButton(
                    enabled = chosen != null || newName.isNotBlank(),
                    onClick = {
                        val person = chosen
                        val name = newName.trim().takeIf { person == null && it.isNotEmpty() }
                        step = Step.Requesting
                        scope.launch {
                            step = runCatching { LanPairing.request(s.server.pairUrl, person, name, device.trim().ifBlank { LanPairing.deviceName() }) }
                                .fold(
                                    onSuccess = { p -> LanPairing.savePending(context, p); Step.Waiting(p) },
                                    onFailure = { Step.Message("The server refused the request: ${it.message}", retry = true) },
                                )
                        }
                    },
                ) { Text("Ask for access") }
                is Step.Message -> if (s.retry) TextButton(onClick = { search() }) { Text("Try again") } else TextButton(onClick = onDismiss) { Text("OK") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (step is Step.Waiting) {
                TextButton(onClick = {
                    LanPairing.savePending(context, null)
                    onDismiss()
                }) { Text("Cancel request") }
            } else if (step !is Step.Message) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
