package com.v2ray.ang.util

import android.content.Context
import com.google.gson.JsonObject
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.handler.SettingsManager
import libv2ray.CoreCallbackHandler
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * End-to-end latency through an isolated core instance.
 *
 * The main VPN core is never stopped or reconfigured. Android excludes the
 * VPN owner's UID from its own tunnel, so the test core reaches the selected
 * server through the device's physical Wi-Fi/mobile network.
 */
object RealLatencyManager {
    data class Result(
        val latencyMs: Long,
        val exitIp: String?
    )

    private data class CacheEntry(val result: Result, val measuredAt: Long)
    private data class ExitEntry(val ip: String, val measuredAt: Long, val configSignature: Int)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val exitCache = ConcurrentHashMap<String, ExitEntry>()
    private val exitLookups = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(MAX_CONCURRENT_TESTS)
    private val exitGate = Semaphore(MAX_CONCURRENT_EXIT_LOOKUPS)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun measure(context: Context, guid: String): Result? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache[guid]?.takeIf { now - it.measuredAt < RESULT_TTL_MS }?.let {
            return@withContext withCachedExit(guid, it.result)
        }

        gate.withPermit {
            val lockedNow = System.currentTimeMillis()
            cache[guid]?.takeIf { lockedNow - it.measuredAt < RESULT_TTL_MS }?.let {
                return@withPermit withCachedExit(guid, it.result)
            }
            runTest(context.applicationContext, guid)?.also {
                cache[guid] = CacheEntry(it, System.currentTimeMillis())
            }
        }
    }

    private fun runTest(context: Context, guid: String): Result? {
        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status || configResult.content.isBlank()) return null

        CoreNativeManager.initCoreEnv(context)
        val latency = CoreNativeManager.measureOutboundDelay(
            configResult.content,
            SettingsManager.getDelayTestUrl()
        )
        if (latency < 0L) return null

        val configSignature = configResult.content.hashCode()
        val cachedExit = exitCache[guid]
            ?.takeIf {
                it.configSignature == configSignature &&
                    System.currentTimeMillis() - it.measuredAt < EXIT_TTL_MS
            }
            ?.ip
            ?: loadPersistedExit(context, guid, configSignature)
        if (cachedExit == null) {
            scheduleExitLookup(context, guid, configResult.content, configSignature)
        }
        return Result(latency, cachedExit)
    }

    private fun withCachedExit(guid: String, result: Result): Result {
        val exitIp = exitCache[guid]
            ?.takeIf { System.currentTimeMillis() - it.measuredAt < EXIT_TTL_MS }
            ?.ip
        return if (exitIp == null || exitIp == result.exitIp) result else result.copy(exitIp = exitIp)
    }

    private fun scheduleExitLookup(
        context: Context,
        guid: String,
        config: String,
        configSignature: Int
    ) {
        if (!exitLookups.add(guid)) return
        backgroundScope.launch {
            try {
                exitGate.withPermit {
                    fetchExitIpThroughCore(config)?.let {
                        val storedAt = System.currentTimeMillis()
                        exitCache[guid] = ExitEntry(it, storedAt, configSignature)
                        context.getSharedPreferences(EXIT_PREFS, Context.MODE_PRIVATE)
                            .edit().putString(guid, "$storedAt|$configSignature|$it").apply()
                    }
                }
            } finally {
                exitLookups.remove(guid)
            }
        }
    }

    private fun loadPersistedExit(context: Context, guid: String, configSignature: Int): String? {
        val raw = context.getSharedPreferences(EXIT_PREFS, Context.MODE_PRIVATE)
            .getString(guid, null) ?: return null
        val parts = raw.split('|', limit = 3)
        if (parts.size != 3) return null
        val storedAt = parts[0].toLongOrNull() ?: return null
        if (parts[1].toIntOrNull() != configSignature) return null
        val ip = parts[2].takeIf { it.isNotBlank() } ?: return null
        if (System.currentTimeMillis() - storedAt >= EXIT_TTL_MS) return null
        exitCache[guid] = ExitEntry(ip, storedAt, configSignature)
        return ip
    }

    private fun fetchExitIpThroughCore(rawConfig: String): String? {
        val port = findFreePort()
        val config = addSocksInbound(rawConfig, port) ?: return null
        val controller = CoreNativeManager.newCoreController(NoOpCallback)
        return try {
            controller.startLoop(config, -1)
            val client = proxyClient(port)
            fetchExitIp(client)
        } catch (_: Exception) {
            null
        } finally {
            try {
                if (controller.isRunning) controller.stopLoop()
            } catch (_: Exception) {
                // Core is short-lived; nothing else to clean up.
            }
        }
    }

    private fun addSocksInbound(raw: String, port: Int): String? = try {
        val root = JsonUtil.parseString(raw)?.asJsonObject ?: return null
        val inbound = JsonObject().apply {
            addProperty("tag", "live-latency-in")
            addProperty("listen", "127.0.0.1")
            addProperty("port", port)
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("auth", "noauth")
                addProperty("udp", false)
            })
        }
        root.getAsJsonArray("inbounds").add(inbound)
        JsonUtil.toJsonPretty(root)
    } catch (_: Exception) {
        null
    }

    private fun proxyClient(port: Int) = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        .connectTimeout(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private fun fetchExitIp(client: OkHttpClient): String? {
        val urls = arrayOf("https://api64.ipify.org", "https://icanhazip.com")
        urls.forEach { url ->
            try {
                val value = client.newCall(Request.Builder().url(url).build())
                    .execute().use { response ->
                        if (response.isSuccessful) response.body?.string()?.trim() else null
                    }
                if (!value.isNullOrBlank() && (value.contains('.') || value.contains(':'))) {
                    return value
                }
            } catch (_: Exception) {
                // Try the next small IP endpoint.
            }
        }
        return null
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private object NoOpCallback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, message: String?): Long = 0
    }

    // Matches v2rayNG's default Real Ping concurrency so a full list appears quickly.
    private const val MAX_CONCURRENT_TESTS = 16
    private const val MAX_CONCURRENT_EXIT_LOOKUPS = 2
    private const val RESULT_TTL_MS = 30_000L
    private const val EXIT_TTL_MS = 24 * 60 * 60 * 1000L
    private const val EXIT_PREFS = "live_ping_exit_ip_daily_cache"
    private const val TEST_TIMEOUT_SECONDS = 8L
}
