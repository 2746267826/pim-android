package com.pim.app.ui.shell

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class AndroidWebMessageBridge(
    private val serverSettingsStore: ServerSettingsStore,
    private val scope: CoroutineScope,
    private val nativeStateProvider: (() -> Map<String, Any?>)? = null,
    private val pageReportSink: ((Map<String, String?>) -> Unit)? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handleMessage(
        rawJson: String,
        sourceOrigin: String,
        isMainFrame: Boolean
    ): String {
        if (!isMainFrame) {
            return errorJson(null, "invalid_request")
        }

        val root = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (_: Exception) {
            return errorJson(null, "invalid_request")
        }

        val version = root["version"]?.asIntOrNull()
        val id = root["id"]?.asStringOrNull()
        val type = root["type"]?.asStringOrNull()

        if (version != PROTOCOL_VERSION || id.isNullOrBlank() || type.isNullOrBlank()) {
            return errorJson(id, "invalid_request")
        }

        val trustedOrigin = currentTrustedOrigin()
        if (trustedOrigin == null || sourceOrigin != trustedOrigin) {
            return errorJson(id, "server_mismatch")
        }

        return try {
            when (type) {
                /*
                 * PW-07a §7 令牌退役：类型保留，但在协议版本、id、type、主框架、origin
                 * 都通过之后固定回 auth_unavailable。不读令牌、不刷新、不 clear()，
                 * 回复与日志都不含 access/refresh 的字节。页面数据改由原生代发（§4）。
                 */
                "token.request", "token.refresh" -> errorJson(id, AUTH_UNAVAILABLE)
                "native.state.request" -> handleNativeStateRequest(id)
                "page.report" -> handlePageReport(id, root)
                else -> errorJson(id, "invalid_request")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            errorJson(id, when (type) {
                "token.request", "token.refresh" -> AUTH_UNAVAILABLE
                "native.state.request" -> "native_state_unavailable"
                "page.report" -> "page_report_failed"
                else -> "invalid_request"
            })
        }
    }

    private fun handleNativeStateRequest(id: String): String {
        val state = try {
            nativeStateProvider?.invoke() ?: emptyMap()
        } catch (_: Exception) {
            return errorJson(id, "native_state_unavailable")
        }
        return encode(
            buildJsonObject {
                put("version", 1)
                put("id", id)
                put("ok", true)
                putJsonObject("nativeState") {
                    state.forEach { (key, value) ->
                        if (key !in NATIVE_STATE_WHITELIST) return@forEach
                        when (value) {
                            null -> put(key, JsonNull)
                            is Boolean -> put(key, value)
                            is Int -> put(key, value)
                            is Long -> put(key, value)
                            is Double -> put(key, value)
                            is Float -> put(key, value)
                            is Number -> put(key, value.toInt())
                            is String -> put(key, value)
                            else -> Unit
                        }
                    }
                }
            }
        )
    }

    private fun handlePageReport(id: String, root: JsonObject): String {
        val reportObject = root["report"]?.let { element ->
            runCatching { element.jsonObject }.getOrNull()
        }
        val report = linkedMapOf<String, String?>()
        for (key in PAGE_REPORT_WHITELIST) {
            val element = reportObject?.get(key) ?: continue
            report[key] = when (element) {
                is JsonNull -> null
                is JsonPrimitive -> {
                    if (element.isString) {
                        element.content
                    } else {
                        element.contentOrNull ?: element.toString()
                    }
                }
                else -> continue
            }
        }
        pageReportSink?.invoke(report)
        return encode(
            buildJsonObject {
                put("version", 1)
                put("id", id)
                put("ok", true)
            }
        )
    }

    fun install(
        webView: WebView,
        isFeatureSupported: () -> Boolean = {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        },
        addListener: (WebView, String, Set<String>, WebViewCompat.WebMessageListener) -> Unit =
            { view, objectName, allowedOrigins, listener ->
                WebViewCompat.addWebMessageListener(view, objectName, allowedOrigins, listener)
            }
    ): Boolean {
        if (!isFeatureSupported()) {
            return false
        }

        val origin = currentTrustedOrigin() ?: return false
        val listener = WebViewCompat.WebMessageListener { _, message, sourceOrigin, isMainFrame, replyProxy ->
            val data = message.data ?: return@WebMessageListener
            scope.launch {
                val result = handleMessage(data, sourceOrigin.toString(), isMainFrame)
                // WebView may be destroyed before the async result is ready
                try {
                    replyProxy.postMessage(result)
                } catch (_: Exception) {
                    // The WebView may be destroyed while the async request is handled.
                }
            }
        }

        return try {
            addListener(webView, JS_OBJECT_NAME, setOf(origin), listener)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun remove(webView: WebView) {
        try {
            WebViewCompat.removeWebMessageListener(webView, JS_OBJECT_NAME)
        } catch (_: Throwable) {
            // Best-effort cleanup on destroy paths.
        }
    }

    private fun currentTrustedOrigin(): String? {
        return runCatching {
            PimServerEndpoints.from(serverSettingsStore.getBaseUrl()).trustedOrigin
        }.getOrNull()
    }

    private fun errorJson(id: String?, errorCode: String): String {
        return encode(
            buildJsonObject {
                put("version", 1)
                if (id != null) {
                    put("id", id)
                }
                put("ok", false)
                put("errorCode", errorCode)
            }
        )
    }

    private fun encode(obj: JsonObject): String {
        return json.encodeToString(JsonElement.serializer(), obj)
    }

    private fun JsonElement.asStringOrNull(): String? {
        return runCatching { jsonPrimitive.contentOrNull }.getOrNull()
    }

    private fun JsonElement.asIntOrNull(): Int? {
        return runCatching { jsonPrimitive.intOrNull }.getOrNull()
    }

    companion object {
        private const val PROTOCOL_VERSION = 1
        const val JS_OBJECT_NAME = "pimAndroid"

        /** PW-07a §7：token.request / token.refresh 的固定失败码。 */
        const val AUTH_UNAVAILABLE = "auth_unavailable"

        private val PAGE_REPORT_WHITELIST = listOf("hasServerData", "generatedAt", "error")
        private val NATIVE_STATE_WHITELIST = setOf(
            "collectionMode",
            "batteryOptimized",
            "triggerReason",
            "nextLocationAt",
            "pending",
            "uploading",
            "confirmed",
            "rejected",
            "lastSuccessAt",
            "nextAttemptAt"
        )
    }
}
