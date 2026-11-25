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

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Voice Keyboard: setting up from the home Wi-Fi, without a QR code (contract in
 * docs/server-pairing.md).
 *
 * 1. [discover]: the server announces `_voicekb._tcp` on the local network (mDNS); its pairing
 *    endpoints answer only from the home network and need no key.
 * 2. [info]: the server's name, its public address and the people it knows.
 * 3. [request]: "this person (or a new one) on this device" — the server sends the owner a Telegram
 *    alert to approve or deny, and answers with a request id and a short code shown on both sides.
 * 4. [status]: polled until approved, denied or expired. Only once approved does the app get its own
 *    key (one per device, tied to that person), in the same form as the setup QR code. Until then
 *    nothing is saved, used or synced: the app shows that it is waiting for approval.
 */
object LanPairing {
    const val SERVICE_TYPE = "_voicekb._tcp."

    data class Found(val name: String, val pairUrl: String)
    data class Person(val user: String, val name: String)
    data class Info(val name: String, val baseUrl: String, val people: List<Person>)
    data class Pending(val pairUrl: String, val requestId: String, val code: String, val person: String, val device: String)

    sealed class Status {
        data object Waiting : Status()
        data class Approved(val setupCode: String) : Status()
        data object Denied : Status()
        data object Expired : Status()
        data class Failed(val why: String) : Status()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /** This device's name as shown in the approval alert ("samsung SM-F966U1"). */
    fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }
        .joinToString(" ").ifBlank { "Android" }.take(40)

    /** Voice servers announcing themselves on the local network, found within [timeoutMs]. */
    suspend fun discover(context: Context, timeoutMs: Long = 6000): List<Found> {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        val results = java.util.Collections.synchronizedList(mutableListOf<Found>())
        withTimeoutOrNull(timeoutMs) {
            callbackFlow {
                val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
                val queue = ArrayDeque<NsdServiceInfo>()
                fun next() {
                    if (!resolving.compareAndSet(false, true)) return
                    val info = synchronized(queue) { queue.removeFirstOrNull() }
                    if (info == null) { resolving.set(false); return }
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            resolving.set(false); next()
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val host = serviceInfo.host?.hostAddress
                            if (host != null) {
                                val txtPath = serviceInfo.attributes["path"]?.let { String(it) }?.takeIf { it.startsWith("/") } ?: "/v1/"
                                val h = if (host.contains(':')) "[$host]" else host
                                trySend(Found(serviceInfo.serviceName, "http://$h:${serviceInfo.port}${txtPath.trimEnd('/')}/"))
                            }
                            resolving.set(false); next()
                        }
                    })
                }
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        synchronized(queue) { queue.addLast(serviceInfo) }
                        next()
                    }
                }
                runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { close() }
                awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
            }.collect { results.add(it) }
        }
        // The timeout ends the search; whatever was found by then is in [results].
        return synchronized(results) { results.toList() }.distinctBy { it.pairUrl }
    }

    private fun get(url: String): JsonObject = client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
        val body = r.body.string()
        if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
        Json.parseToJsonElement(body).jsonObject
    }

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    /** `GET <pair>vk/pair/info`. */
    suspend fun info(pairUrl: String): Info = withContext(Dispatchers.IO) {
        val o = get(pairUrl + "vk/pair/info")
        val people = (o["people"] as? JsonArray).orEmpty().mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            val user = str(p, "user")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Person(user, str(p, "name")?.takeIf { it.isNotBlank() } ?: user)
        }
        Info(str(o, "name") ?: "Voice server", str(o, "base_url").orEmpty(), people)
    }

    /**
     * `POST <pair>vk/pair` for [person] (an id from [info]) or, when null, a new person called [newName].
     * The server sends the approval alert.
     */
    suspend fun request(pairUrl: String, person: String?, newName: String?, device: String): Pending = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            person?.let { put("person", it) }
            newName?.let { put("new_name", it) }
            put("device", device)
        }.toString()
        val req = Request.Builder().url(pairUrl + "vk/pair").post(body.toRequestBody(JSON_TYPE)).build()
        client.newCall(req).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) {
                val msg = runCatching { str(Json.parseToJsonElement(text).jsonObject, "error") }.getOrNull()
                throw java.io.IOException(msg ?: "HTTP ${r.code}")
            }
            val o = Json.parseToJsonElement(text).jsonObject
            Pending(
                pairUrl = pairUrl,
                requestId = str(o, "request_id") ?: throw java.io.IOException("no request id"),
                code = str(o, "code").orEmpty(),
                person = person ?: newName.orEmpty(),
                device = device,
            )
        }
    }

    /** `GET <pair>vk/pair/<id>`: waiting, or the setup code once approved (handed out once). */
    suspend fun status(p: Pending): Status = withContext(Dispatchers.IO) {
        try {
            val o = get(p.pairUrl + "vk/pair/" + java.net.URLEncoder.encode(p.requestId, "UTF-8"))
            when (str(o, "status")) {
                "approved" -> (o["setup"] as? JsonObject)?.let { Status.Approved(it.toString()) }
                    ?: Status.Failed("approved, but the server sent no setup")
                "denied" -> Status.Denied
                "expired" -> Status.Expired
                else -> Status.Waiting
            }
        } catch (e: java.io.IOException) {
            if (e.message == "HTTP 404") Status.Expired else Status.Failed("could not reach the server")
        }
    }

    // ---- The waiting request survives leaving the screen (no key in it: there is none yet) ----

    private const val PREFS = "vk_pairing"

    fun savePending(context: Context, p: Pending?) {
        val e = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (p == null) e.clear() else e.putString("pair_url", p.pairUrl).putString("id", p.requestId)
            .putString("code", p.code).putString("person", p.person).putString("device", p.device)
            .putLong("at", System.currentTimeMillis())
        e.apply()
    }

    fun loadPending(context: Context): Pending? {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = sp.getString("id", null) ?: return null
        // A request older than a day is gone on the server too.
        if (System.currentTimeMillis() - sp.getLong("at", 0) > 24 * 3600 * 1000L) { savePending(context, null); return null }
        return Pending(sp.getString("pair_url", "").orEmpty(), id, sp.getString("code", "").orEmpty(),
            sp.getString("person", "").orEmpty(), sp.getString("device", "").orEmpty())
    }
}
