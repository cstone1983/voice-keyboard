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

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.dictateProxyConfig
import dev.patrickgold.florisboard.dictate.provider.ModelInfo
import dev.patrickgold.florisboard.dictate.provider.OpenAiCompatibleClient
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/** Which server the own-server screen edits next (set right before navigating there; read once). */
internal object OwnServerEditHandoff {
    @Volatile var editId: String? = null
}

/** What the own-server form hands back when the user saves it. */
internal data class OwnServerChoice(
    /** The account being edited, or null for a new server. */
    val editId: String? = null,
    val baseUrl: String,
    val apiKey: String,
    val transcriptionModel: String,
    /** Empty when the user wants no AI cleanup. */
    val chatModel: String,
    val models: List<String>,
    val transcriptionModels: List<String>,
    /** Who's using this keyboard (household key), or null to keep the current choice. */
    val person: dev.patrickgold.florisboard.vk.learning.VkIdentity.Person? = null,
)

/**
 * The server's setup code (shown as a QR code): one line of JSON,
 * `{"v":1,"base_url":"https://…/v1/","api_key":"<key>"}`. Unknown fields are ignored so the server can add
 * more later; optional model fields are read if present.
 */
internal data class OwnServerSetupCode(
    val baseUrl: String,
    val apiKey: String,
    val transcriptionModel: String? = null,
    val chatModel: String? = null,
    /** Display only: who the server says this key belongs to, and the device name. */
    /** Display only ("Chris", "household"); never used as the id. */
    val user: String? = null,
    /** The person id for X-VK-As ("chris"); "*" for a household key, where the app asks who is using it. */
    val userId: String? = null,
    val device: String? = null,
)

internal fun parseOwnServerSetupCode(text: String): OwnServerSetupCode? {
    val obj: JsonObject = try {
        Json.parseToJsonElement(text.trim()).jsonObject
    } catch (_: Exception) {
        return null
    }
    fun str(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
    val version = (obj["v"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
    if (version > 1 && str("base_url") == null) return null
    val baseUrl = str("base_url", "url") ?: return null
    if (!isAllowedServerUrl(baseUrl)) return null
    return OwnServerSetupCode(
        baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/",
        apiKey = str("api_key", "key").orEmpty(),
        transcriptionModel = str("stt_model", "transcription_model"),
        chatModel = str("chat_model", "cleanup_model"),
        user = str("user"),
        userId = str("user_id"),
        device = str("device"),
    )
}

/**
 * https always; http only for a server on the home network (private address, `.lan`/`.local`/`.home.arpa`/
 * `.internal` name, or a bare host name), so the key is never sent unencrypted over the internet.
 */
internal fun isAllowedServerUrl(url: String): Boolean {
    if (url.startsWith("https://", ignoreCase = true)) return true
    if (!url.startsWith("http://", ignoreCase = true)) return false
    val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()?.trim('[', ']') ?: return false
    if (host == "localhost" || !host.contains('.') && !host.contains(':')) return true
    if (listOf(".lan", ".local", ".home.arpa", ".internal").any { host.endsWith(it) }) return true
    val p = host.split('.').mapNotNull { it.toIntOrNull() }
    if (p.size == 4) {
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) ||
            (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254) || (p[0] == 100 && p[1] in 64..127)
    }
    return host.startsWith("fd") || host.startsWith("fe80") // IPv6 unique-local / link-local
}

/** Reads a QR code from a photo or screenshot. Null if none was found. */
private fun decodeQr(context: Context, uri: Uri): String? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    var sample = 1
    while (longest / (sample * 2) >= 1600) sample *= 2
    val bitmap: Bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    bitmap.recycle()
    val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )
    for (binarizer in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))) {
        try {
            return QRCodeReader().decode(BinaryBitmap(binarizer), hints).text
        } catch (_: Exception) {
            // try the next binarizer
        }
    }
    return null
}

/** `GET <base_url>../health`; true when the server answers 2xx. No key is sent. */
private fun checkHealth(baseUrl: String): Boolean {
    val url = URI(baseUrl).resolve("../health").toString()
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    return client.newCall(Request.Builder().url(url).get().build()).execute().use { it.isSuccessful }
}

/**
 * Stores the user's own server as a custom endpoint and makes it the active transcription provider, and
 * the rewording (cleanup) provider when a cleanup model was picked. An existing "My server" account with
 * the same address is updated rather than duplicated.
 */
internal suspend fun FlorisPreferenceModel.saveOwnServer(choice: OwnServerChoice) {
    val accounts = dictate.providerAccounts.get()
    val existing = choice.editId?.let { accounts[it] }
        ?: accounts.accounts.values.firstOrNull { it.isCustom && it.customBaseUrl == choice.baseUrl }
    val id = existing?.providerId ?: ProviderAccount.newCustomId()
    val base = existing ?: ProviderAccount(providerId = id, displayName = "My server")
    val account = base.copy(
        apiKey = choice.apiKey,
        customBaseUrl = choice.baseUrl,
        transcriptionModel = choice.transcriptionModel,
        chatModel = choice.chatModel,
        cachedModels = choice.models.ifEmpty { base.cachedModels },
        cachedTranscriptionModels = choice.transcriptionModels.ifEmpty { base.cachedTranscriptionModels },
        cachedModelsAt = if (choice.models.isEmpty()) base.cachedModelsAt else System.currentTimeMillis(),
    )
    dictate.providerAccounts.set(accounts.put(account))
    dictate.transcriptionProviderId.set(id)
    choice.person?.let { dev.patrickgold.florisboard.vk.learning.VkIdentity.select(this, it) }
    if (choice.chatModel.isNotBlank()) {
        dictate.rewordingProviderId.set(id)
    }
}

/**
 * Voice Keyboard: setting up a server of the user's own. Fill in by scanning the server's setup QR code
 * (or pasting it), or by hand; test the connection, load the server's models, pick the speech-to-text and
 * cleanup models, save.
 *
 * Texts are plain English on purpose (personal build, not translated).
 */
@Composable
internal fun OwnServerSetup(
    onSave: (OwnServerChoice) -> Unit,
    onBack: (() -> Unit)?,
    /** When editing: the stored server, whose address, key and models fill the form. */
    existing: ProviderAccount? = null,
) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by rememberSaveable { mutableStateOf(existing?.customBaseUrl.orEmpty()) }
    var apiKey by rememberSaveable { mutableStateOf(existing?.apiKey.orEmpty()) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(if (existing != null && existing.apiKey.isNotEmpty()) "API key saved (it stays unless you change it)." else null)
    }
    var error by remember { mutableStateOf<String?>(null) }
    var models by remember {
        mutableStateOf(existing?.cachedModels.orEmpty().map { id ->
            ModelInfo(id, outputModalities = if (id in existing!!.cachedTranscriptionModels) listOf("transcription") else emptyList())
        })
    }
    var sttModel by rememberSaveable { mutableStateOf(existing?.transcriptionModel.orEmpty()) }
    // Household key: the people it may act as (GET vk/users), and who is picked.
    var people by remember { mutableStateOf<List<dev.patrickgold.florisboard.vk.learning.VkIdentity.Person>>(emptyList()) }
    var pickedUser by rememberSaveable { mutableStateOf(prefs.dictate.vkActingUser.get().takeIf { it.isNotBlank() }) }
    // The QR's display name, for the person picked from the QR when the server's list is not available.
    var qrDisplayName by rememberSaveable { mutableStateOf<String?>(null) }
    var chatModel by rememberSaveable { mutableStateOf(existing?.chatModel.orEmpty()) }

    fun normalizedUrl(): String = baseUrl.trim().let { if (it.isEmpty() || it.endsWith("/")) it else "$it/" }

    fun testAndLoad() {
        val url = normalizedUrl()
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            error = "The address must start with https:// (or http:// on your home network)."
            return
        }
        if (!isAllowedServerUrl(url)) {
            error = "Use https:// for a server outside your home network (the key would be sent unencrypted)."
            return
        }
        busy = true
        error = null
        status = null
        scope.launch {
            try {
                val healthy = withContext(Dispatchers.IO) { runCatching { checkHealth(url) }.getOrDefault(false) }
                status = if (healthy) "Server reachable." else "No answer from the server's health check; trying anyway."
                val list = OpenAiCompatibleClient
                    .from(
                        ProviderRegistry.custom(url),
                        apiKey.trim(),
                        baseUrlOverride = url,
                        proxy = prefs.dictate.dictateProxyConfig(),
                        timeoutSeconds = prefs.dictate.requestTimeout.get().toLong(),
                    )
                    .listModels()
                    .filter { it.id.isNotBlank() }
                    .sortedBy { it.id.lowercase() }
                models = list
                // The people this key may act as; a server without the learning API just has none.
                people = withContext(Dispatchers.IO) {
                    runCatching {
                        dev.patrickgold.florisboard.vk.learning.VkIdentity.users(
                            dev.patrickgold.florisboard.vk.learning.VkLearningApi(url, apiKey.trim()),
                        )
                    }.getOrDefault(emptyList())
                }
                // Only a list from the server can overrule the choice; without one a QR user_id stays.
                if (people.isNotEmpty() && pickedUser !in people.map { it.user }) {
                    pickedUser = if (people.size == 1) people[0].user else null
                }
                if (list.isEmpty()) {
                    status = (status.orEmpty() + " The server listed no models; type the model names below.").trim()
                } else {
                    status = (status.orEmpty() + " ${list.size} models found.").trim()
                    // Voice Keyboard: the fast English model first; never pre-pick a large model, which costs a
                    // CPU-only server many seconds per request. The cleanup model is not in this list (it
                    // comes from the speech server); it comes from the setup code or is typed.
                    if (sttModel.isBlank() || sttModel !in list.map { it.id }) {
                        sttModel = (list.firstOrNull { it.id.contains("small.en", ignoreCase = true) }
                            ?: list.firstOrNull { it.looksLikeSpeech() && !it.id.contains("large", ignoreCase = true) }
                            ?: list.firstOrNull { it.looksLikeSpeech() })?.id.orEmpty()
                    }
                }
            } catch (e: Exception) {
                // The client's messages never contain the key.
                error = "Could not load models: ${e.message ?: e.javaClass.simpleName}. You can still type the " +
                    "model names below."
            } finally {
                busy = false
            }
        }
    }

    fun applyCode(text: String?, source: String) {
        val code = text?.let { parseOwnServerSetupCode(it) }
        if (code == null) {
            error = "That $source does not contain a Voice Keyboard setup code."
            return
        }
        baseUrl = code.baseUrl
        apiKey = code.apiKey
        code.transcriptionModel?.let { sttModel = it }
        code.chatModel?.let { chatModel = it }
        models = emptyList()
        testAndLoad()
        // user_id is the identity; "*" (household key) or none means: ask who is using it (vk/users).
        pickedUser = code.userId?.takeIf { it != "*" }?.lowercase()
        qrDisplayName = code.user
        code.user?.let { u ->
            status = "Setup code for ${u.replaceFirstChar { it.uppercase() }}" + (code.device?.let { " ($it)" } ?: "") + ". " + status.orEmpty()
        }
    }

    fun handleImage(uri: Uri) {
        busy = true
        error = null
        scope.launch {
            val text = withContext(Dispatchers.Default) { runCatching { decodeQr(context, uri) }.getOrNull() }
            busy = false
            if (text == null) error = "No QR code found in that picture. Hold the phone steady and fill the frame with it."
            else applyCode(text, "QR code")
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) handleImage(uri)
    }

    // Live camera scanning: keeps looking until it sees the code (the "QR image" button stays for a
    // screenshot or a saved picture).
    var showScanner by remember { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showScanner = true
        else error = "The camera is needed to scan. You can choose a QR image instead."
    }

    fun startScan() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) showScanner = true else cameraPermission.launch(android.Manifest.permission.CAMERA)
    }

    // Setup from the home Wi-Fi: find the server, ask for access, wait for the owner's approval.
    var showPairing by remember { mutableStateOf(false) }
    val pendingPairing = remember(showPairing) { LanPairing.loadPending(context) }
    if (showPairing) {
        LanPairingDialog(
            onApproved = { setupCode, _ ->
                showPairing = false
                applyCode(setupCode, "Wi-Fi setup")
            },
            onDismiss = { showPairing = false },
        )
    }

    if (showScanner) {
        QrCameraScanner(
            onResult = { text ->
                showScanner = false
                applyCode(text, "QR code")
            },
            onDismiss = { showScanner = false },
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(if (existing == null) "Your own server" else "Edit your server", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Scan the setup QR code your server shows, or enter its address (ending in /v1/) and API key.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (pendingPairing != null) {
                // A Wi-Fi setup request is waiting for the owner's approval: nothing is set up yet.
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Waiting for approval", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${pendingPairing.person.ifBlank { "New person" }} on ${pendingPairing.device}" +
                                (if (pendingPairing.code.isNotBlank()) " · code ${pendingPairing.code}" else "") +
                                ". Approve it in Telegram; the keyboard is not used or synced until then.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { showPairing = true }) { Text("Check now") }
                    }
                }
            }
            Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { showPairing = true }) {
                Icon(Icons.Default.Wifi, contentDescription = null)
                Text("  Find my server on Wi-Fi")
            }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { startScan() }) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Text("  Scan QR code")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                ) {
                    Icon(Icons.Default.Image, contentDescription = null)
                    Text(" QR image")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    onClick = {
                        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        applyCode(text, "clipboard text")
                    },
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null)
                    Text(" Paste code")
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server address") },
                placeholder = { Text("https://example.com/v1/") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showKey) "Hide key" else "Show key",
                        )
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = baseUrl.isNotBlank() && !busy,
                onClick = { testAndLoad() },
            ) {
                Text(if (models.isEmpty()) "Test connection and load models" else "Reload models")
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            ModelField(
                label = "Speech-to-text model",
                value = sttModel,
                options = models.sortedByDescending { it.looksLikeSpeech() }.map { it.id },
                onChange = { sttModel = it },
            )
            Spacer(Modifier.height(8.dp))
            ModelField(
                label = "Cleanup model (AI, e.g. from the QR code), empty for none",
                value = chatModel,
                options = models.sortedBy { it.looksLikeSpeech() }.map { it.id },
                onChange = { chatModel = it },
            )
            Spacer(Modifier.height(12.dp))
            if (people.isNotEmpty()) {
                dev.patrickgold.florisboard.vk.learning.VkUserPicker(
                    people = people,
                    selected = pickedUser,
                    onPick = { pickedUser = it.user },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // No list from the server (yet): the user name can be typed. Optional; used for learning.
                OutlinedTextField(
                    value = pickedUser.orEmpty(),
                    onValueChange = { pickedUser = it.trim().lowercase().ifEmpty { null } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Who's using this keyboard? (your user name on the server)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && isAllowedServerUrl(normalizedUrl()) && sttModel.isNotBlank() &&
                    (people.isEmpty() || pickedUser != null),
                onClick = {
                    onSave(
                        OwnServerChoice(
                            editId = existing?.providerId,
                            baseUrl = normalizedUrl(),
                            apiKey = apiKey.trim(),
                            transcriptionModel = sttModel.trim(),
                            chatModel = chatModel.trim(),
                            models = models.map { it.id },
                            transcriptionModels = models.filter { it.isTranscriptionModel }.map { it.id },
                            person = people.firstOrNull { it.user == pickedUser }
                                ?: pickedUser?.let { u ->
                                    dev.patrickgold.florisboard.vk.learning.VkIdentity.Person(
                                        u, qrDisplayName ?: u.replaceFirstChar { it.uppercase() },
                                    )
                                },
                        ),
                    )
                },
            ) {
                Text("Save")
            }
        }
    }
    if (onBack != null) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = onBack) { Text("Back to other options") }
        }
    }
}

/** A model name: typed freely, or picked from what the server listed. */
@Composable
private fun ModelField(
    label: String,
    value: String,
    options: List<String>,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            trailingIcon = {
                if (options.isNotEmpty()) {
                    IconButton(onClick = { open = true }) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose a model")
                    }
                }
            },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            options.forEach { id ->
                DropdownMenuItem(text = { Text(id) }, onClick = { onChange(id); open = false })
            }
        }
    }
}

/** Speech models by catalog flag or by the usual names (Whisper, faster-whisper, Parakeet, ...). */
private fun ModelInfo.looksLikeSpeech(): Boolean =
    isTranscriptionModel || SPEECH_HINTS.any { id.contains(it, ignoreCase = true) }

private val SPEECH_HINTS = listOf("whisper", "transcrib", "parakeet", "stt", "speech", "canary", "moonshine")
