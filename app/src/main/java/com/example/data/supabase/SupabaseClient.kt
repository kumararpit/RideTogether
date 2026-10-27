package com.example.data.supabase

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal Supabase client using okhttp (already a project dependency).
 *
 * Calls the Supabase REST API directly:
 *   - https://<project>.supabase.co/rest/v1/<table>
 *   - Realtime via WebSocket: wss://<project>.supabase.co/realtime/v1
 *
 * Initialize once from [RideTogetherApplication.onCreate] using
 * SUPABASE_URL and SUPABASE_ANON_KEY from [.env].
 *
 * Free tier: 500MB database, 2GB bandwidth, realtime included. No billing required.
 */
object SupabaseClient {

    private const val MEDIA_TYPE_JSON = "application/json; charset=utf-8"

    private var client: OkHttpClient? = null
    private var baseUrl: String? = null
    private var anonKey: String? = null

    /** Returns the SupabaseClient instance (this object) for passing to repositories. */
    val instance: SupabaseClient
        get() = this

    /** Returns true if Supabase is configured and ready. */
    val isInitialized: Boolean
        get() = client != null && baseUrl != null && anonKey != null

    fun init(context: Context) {
        if (isInitialized) return

        val url = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_ANON_KEY

        if (url.isBlank() || key.isBlank() || url.contains("YOUR_") || key.contains("YOUR_")) {
            Log.w("SupabaseClient", "Supabase URL/ANON_KEY not configured — Supabase writes disabled.")
            return
        }

        client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        baseUrl = url.trimEnd { it == '/' }
        anonKey = key

        Log.i("SupabaseClient", "Supabase REST client initialized: $baseUrl")
    }

    /** The Supabase REST base URL, e.g. https://xyz.supabase.co/rest/v1 */
    val restUrl: String
        get() = "$baseUrl/rest/v1" ?: throw IllegalStateException("Supabase not initialized")

    /** The anon key for service-role-free client access. */
    val key: String
        get() = anonKey ?: throw IllegalStateException("Supabase not initialized")

    /** The underlying okhttp client. */
    val http: OkHttpClient
        get() = client ?: throw IllegalStateException("Supabase not initialized")

    /** The Supabase Realtime WebSocket base URL, e.g. wss://xyz.supabase.co/realtime/v1 */
    val realtimeUrl: String
        get() = (baseUrl ?: "").replace("https://", "wss://").replace("http://", "ws://") + "/realtime/v1" ?: throw IllegalStateException("Supabase not initialized")

    /** Supabase Realtime client (lazily initialized on first use). */
    val realtime: SupabaseRealtime by lazy { SupabaseRealtime(this) }

    /**
     * Make a request to Supabase REST API.
     *
     * @param method HTTP method (GET, POST, PATCH, DELETE, etc.)
     * @param path   Table or RPC path, e.g. "rides?id=eq.xxx"
     * @param body   Optional JSON body
     * @param extraHeaders Optional extra headers
     * @return Response body string
     */
    suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        idToken: String? = null
    ): String = withContext(Dispatchers.IO) {
        val url = "$restUrl/$path"
        val requestBody: RequestBody? = body?.let { b ->
            object : RequestBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength(): Long = b.toByteArray(UTF_8).size.toLong()
                override fun writeTo(sink: BufferedSink) {
                    sink.writeUtf8(b)
                }
            }
        }
        val requestBuilder = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .header("apikey", key)
            .header("Authorization", "Bearer ${idToken ?: key}")
            .header("Content-Type", MEDIA_TYPE_JSON)
            .header("Prefer", "return=representation")

        for ((k, v) in extraHeaders) {
            requestBuilder.header(k, v)
        }

        val request = requestBuilder.build()
        val response: Response = http.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            Log.w("SupabaseClient", "$method $path -> ${response.code} $responseBody")
        }
        responseBody
    }

    fun shutdown() {
        client?.dispatcher?.executorService?.shutdownNow()
        client = null
        baseUrl = null
        anonKey = null
    }
}

/**
 * Supabase Realtime client using okhttp WebSocket.
 *
 * Connects to the Supabase Realtime endpoint (Phoenix protocol over WebSocket)
 * and manages channel subscriptions for postgres_changes events on specific tables.
 *
 * Usage:
 *   val unsub = realtime.subscribe("rides", "id=eq.rideId") { record ->
 *     // handle INSERT/UPDATE/DELETE on rides table
 *   }
 *   unsub() // leave channel and clean up
 */
class SupabaseRealtime(private val client: SupabaseClient) {

    private val TAG = "SupabaseRealtime"

    private var ws: WebSocket? = null
    private val subscriptions = ConcurrentHashMap<String, RealtimeSubscription>()
    private val refGen = AtomicInteger(0)
    private var connected = false
    private var heartbeatScheduled = false
    private val heartbeatScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "supabase-realtime-heartbeat").apply { isDaemon = true }
    }

    data class RealtimeSubscription(
        val table: String,
        val filter: String,
        val onChange: (Map<String, Any>) -> Unit
    )

    /**
     * Subscribe to postgres_changes events on [table] matching [filter].
     *
     * @param table   Supabase table name (e.g. "rides", "riders", "messages", "sos_events")
     * @param filter  Realtime filter string (e.g. "id=eq.abc123", "ride_id=eq.abc123")
     * @param onChange  Callback invoked with the changed record (the `new` or `old` row)
     * @return An unsubscribe function that leaves the channel and removes the subscription.
     */
    fun subscribe(
        table: String,
        filter: String,
        onChange: (Map<String, Any>) -> Unit
    ): () -> Unit {
        val topic = "realtime:$table"
        val key = "$topic|$filter"

        // Return existing subscription's unsubscribe if already subscribed
        subscriptions[key]?.let { return { unsubscribe(key) } }

        val sub = RealtimeSubscription(table, filter, onChange)
        subscriptions[key] = sub

        connectAndJoin(topic, filter)

        Log.d(TAG, "Subscribed to $topic filter=$filter")
        return { unsubscribe(key) }
    }

    private fun unsubscribe(key: String) {
        val sub = subscriptions.remove(key) ?: return
        val topic = "realtime:${sub.table}"
        leaveChannel(topic, sub.filter)
        Log.d(TAG, "Unsubscribed from $topic filter=${sub.filter}")

        // If no more subscriptions, we can let the WebSocket idle (it will be
        // re-created on next subscribe). We don't close it here to allow
        // quick re-subscription without reconnection overhead.
    }

    /** Ensure WebSocket is connected, then join the channel. */
    private fun connectAndJoin(topic: String, filter: String) {
        if (ws != null && connected) {
            joinChannel(topic, filter)
            return
        }
        if (ws != null) {
            // Already connecting; onOpen will re-join all channels.
            return
        }
        connect()
        // Join will happen in onOpen (which re-joins all pending channels).
    }

    private fun connect() {
        val url = "${client.realtimeUrl}?apikey=${client.key}"
        val request = Request.Builder()
            .url(url)
            .header("apikey", client.key)
            .header("Authorization", "Bearer ${client.key}")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                ws = webSocket
                Log.d(TAG, "WebSocket connected")
                // Re-join all existing channels
                subscriptions.keys.forEach { key ->
                    val parts = key.split("|")
                    if (parts.size == 2) {
                        joinChannel(parts[0], parts[1])
                    }
                }
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                stopHeartbeat()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                ws = null
                stopHeartbeat()
                Log.w(TAG, "WebSocket closed: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                ws = null
                stopHeartbeat()
                Log.w(TAG, "WebSocket failure: ${t.message}")
            }
        }

        ws = client.http.newWebSocket(request, listener)
    }

    private fun joinChannel(topic: String, filter: String) {
        val ref = refGen.incrementAndGet().toString()
        sendPhoenix(topic, "phx_join", buildMap {
            put("schema", "public")
            put("table", topic.removePrefix("realtime:"))
            put("filter", filter)
        }, ref)
    }

    private fun leaveChannel(topic: String, filter: String) {
        if (ws == null || !connected) return
        val ref = refGen.incrementAndGet().toString()
        sendPhoenix(topic, "phx_leave", buildMap {
            put("schema", "public")
            put("table", topic.removePrefix("realtime:"))
            put("filter", filter)
        }, ref)
    }

    private fun sendPhoenix(topic: String, event: String, payload: Map<String, Any>, ref: String) {
        val msg = buildMap<String, Any> {
            put("topic", topic)
            put("event", event)
            put("payload", payload)
            put("ref", ref)
        }
        val json = moshiAdapter.toJson(msg)
        ws?.send(json)
    }

    private fun handleMessage(text: String) {
        val msg: Map<String, Any>? = try {
            moshiAdapter.fromJson(text) as? Map<String, Any>
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse realtime message: $text")
            return
        } ?: return

        val event = msg?.get("event") as? String ?: return
        val payload = msg?.get("payload") as? Map<*, *> ?: return

        when (event) {
            "postgres_changes" -> {
                val table = payload["table"] as? String ?: return
                val eventType = payload["event_type"] as? String ?: return
                val key = "realtime:$table"
                // Find matching subscriptions for this table
                subscriptions.entries.forEach { entry ->
                    val subKey = entry.key
                    if (subKey.startsWith("$key|")) {
                        val record: Map<*, *>? = when {
                            eventType == "INSERT" || eventType == "UPDATE" -> payload["new"] as? Map<*, *>
                            eventType == "DELETE" -> payload["old"] as? Map<*, *>
                            else -> null
                        }
                        record?.let { rec ->
                            entry.value.onChange(rec as Map<String, Any>)
                        }
                    }
                }
            }
            "phx_reply" -> {
                // Join/leave acknowledgement — ignore (fire and forget)
            }
            "heartbeat" -> {
                // Server heartbeat — ignore
            }
            else -> {
                Log.d(TAG, "Unhandled realtime event: $event")
            }
        }
    }

    private fun startHeartbeat() {
        if (heartbeatScheduled) return
        heartbeatScheduled = true
        heartbeatScheduler.scheduleAtFixedRate({
            if (connected && ws != null) {
                val ref = refGen.incrementAndGet().toString()
                sendPhoenix("realtime:connection", "heartbeat", buildMap { }, ref)
            } else {
                stopHeartbeat()
            }
        }, 30, 30, TimeUnit.SECONDS)
    }

    private fun stopHeartbeat() {
        heartbeatScheduled = false
    }

    /** Moshi adapter for serializing Phoenix protocol messages. */
    private val moshiAdapter by lazy {
        com.squareup.moshi.Moshi.Builder().build().adapter(Map::class.java)
    }

    fun cleanup() {
        stopHeartbeat()
        heartbeatScheduler.shutdown()
        ws?.close(1000, "cleanup")
        ws = null
        connected = false
        subscriptions.clear()
    }
}

/**
 * BuildConfig fields are generated by the Secrets Gradle Plugin from .env.
 * Add to app/build.gradle.kts defaultConfig:
 *   buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
 *   buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
 */
