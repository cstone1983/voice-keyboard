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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** "Who's using this keyboard?": a drop-down of the people the server key may act as. */
@Composable
fun VkUserPicker(
    people: List<VkIdentity.Person>,
    selected: String?,
    onPick: (VkIdentity.Person) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val current = people.firstOrNull { it.user == selected }
    Text("Who's using this keyboard?", style = MaterialTheme.typography.labelLarge)
    Box(modifier = modifier) {
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { open = true }, enabled = people.isNotEmpty()) {
            Text(
                current?.name ?: if (people.isEmpty()) "No people listed by the server" else "Choose…",
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            people.forEach { p ->
                DropdownMenuItem(text = { Text(p.name) }, onClick = { onPick(p); open = false })
            }
        }
    }
}
