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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.setup.OwnServerSetup
import dev.patrickgold.florisboard.app.setup.saveOwnServer
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import kotlinx.coroutines.launch

/** Voice Keyboard: connect the user's own server (QR code or address + key, then models). */
@Composable
fun DictateOwnServerScreen() = FlorisScreen {
    title = "Your own server"
    previewFieldVisible = false

    val prefs by FlorisPreferenceStore

    content {
        val navController = LocalNavController.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OwnServerSetup(
                onSave = { choice ->
                    scope.launch {
                        prefs.saveOwnServer(choice)
                        Toast.makeText(context, "Server saved and selected", Toast.LENGTH_SHORT).show()
                        navController.popBackStack()
                    }
                },
                onBack = null,
            )
        }
    }
}
