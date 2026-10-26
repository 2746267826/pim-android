package com.pim.app.notes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object NoteEditorPolicy {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val PAGE = "$ORIGIN/assets/note-editor/index.html"
    val assets = mapOf("index.html" to "text/html", "editor.js" to "text/javascript", "editor.css" to "text/css")
    fun allowed(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.rawAuthority == "appassets.androidplatform.net" &&
            uri.rawQuery == null && uri.rawFragment == null && assets.keys.any { uri.rawPath == "/assets/note-editor/$it" }
    }.getOrDefault(false)
}

/** A local editor surface only. It cannot fetch a WebUI, navigate, or read private files. */
@SuppressLint("SetJavaScriptEnabled")
internal class OfflineMarkdownView(context: Context) : WebView(context) {
    private val session = newNoteId()
    private var epoch = 0L
    private var sequence = 0L
    private var source: String? = null
    private var ready = false
    private var failed = false
    private var released = false
    private var frozen = false
    private var enabled = true
    private var theme = emptyMap<String, String>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var readCurrentText: () -> String? = { null }
    var onText: (String) -> Unit = {}
    var onInsertion: (NoteInsertion, Boolean, Set<String>) -> Unit = { _, _, _ -> }
    var onCaret: (Float, Float) -> Unit = { _, _ -> }
    var onHeight: (Int) -> Unit = {}
    var onFailure: (String) -> Unit = {}
    var onNotice: (String) -> Unit = {}
    var onPending: (Boolean) -> Unit = {}
    var blockId: String = ""

    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        isVerticalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            // CSS pixels already include the native font-scale token.
            textZoom = 100
        }
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/note-editor/") { path ->
            NoteEditorPolicy.assets[path]?.let { mime -> WebResourceResponse(mime, "UTF-8", context.assets.open("note-editor/$path")) }
        }.build()
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                if (request.method == "GET" && NoteEditorPolicy.allowed(request.url.toString()))
                    loader.shouldInterceptRequest(request.url) ?: denied()
                else denied()
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail("正文编辑器已停止，最近收到的原文仍保留；请核对内容并使用源码编辑。")
                return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) fail("离线正文无法加载，已保留原文并改用 Markdown 源码编辑。")
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this, "PimNoteBridge", setOf(NoteEditorPolicy.ORIGIN)) { _, message, origin, mainFrame, _ ->
                if (mainFrame && origin.toString().trimEnd('/') == NoteEditorPolicy.ORIGIN && url == NoteEditorPolicy.PAGE) {
                    runCatching { receive(message.data.orEmpty()) }.onFailure { fail("编辑消息无法验证，已停止富文本输入；最近原文保留，请改用源码并核对内容。") }
                }
            }
        } else fail("当前系统 WebView 不支持安全编辑通信，使用 Markdown 源码编辑。")
    }

    fun bind(text: String, editable: Boolean, colors: Map<String, String>) {
        if (released || failed) return
        enabled = editable; theme = colors
        // Compose may still hold an older echo. Read the synchronous native repository instead.
        val current = readCurrentText() ?: text
        if (source == null) source = current
        else if (source != current) { epoch++; sequence = 0; source = current }
        if (url == null) {
            loadUrl(NoteEditorPolicy.PAGE)
            postDelayed({ if (!ready && !released) fail("离线正文未能启动，已保留原文并改用 Markdown 源码编辑。") }, 10_000)
        }
        configure()
    }

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? =
        super.onCreateInputConnection(outAttrs).also {
            outAttrs.imeOptions = outAttrs.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }

    // The Compose viewport owns caret scrolling; native whole-WebView requests fight its inset-aware range.
    override fun requestRectangleOnScreen(rectangle: android.graphics.Rect): Boolean = false
    override fun requestRectangleOnScreen(rectangle: android.graphics.Rect, immediate: Boolean): Boolean = false

    private fun configure() {
        if (!ready || failed || released) return
        val config = buildJsonObject {
            put("session", session); put("epoch", epoch); put("sequence", sequence)
            put("text", source.orEmpty()); put("enabled", enabled); put("frozen", frozen); put("nativeToolbar", true)
            put("theme", buildJsonObject { theme.forEach { (key, value) -> put(key, value) } })
        }
        evaluateJavascript("window.PimEditor.configure($config)", null)
    }

    private fun receive(raw: String) {
        if (released || failed) return
        require(raw.length <= 2_100_000)
        val data = NoteJson.parseToJsonElement(raw).jsonObject
        require(data.getValue("protocol").jsonPrimitive.int == 1)
        if (data["type"]?.jsonPrimitive?.content == "ready" && !ready) { ready = true; configure(); return }
        require(data["session"]?.jsonPrimitive?.content == session)
        if (data.getValue("epoch").jsonPrimitive.long != epoch) return
        when (data["type"]?.jsonPrimitive?.content) {
            "snapshot" -> acceptSnapshot(data)
            "composing" -> onPending(true)
            "height" -> onHeight(data.getValue("height").jsonPrimitive.int.coerceIn(1, 8192))
            "sourceOnly" -> fail(data.getValue("reason").jsonPrimitive.content.take(400))
            "notice" -> onNotice(data.getValue("reason").jsonPrimitive.content.take(400))
            "error" -> fail("正文编辑器遇到异常，最近收到的原文保留；请使用源码并核对内容。")
        }
    }

    private fun acceptSnapshot(data: JsonObject) {
        val next = data.getValue("sequence").jsonPrimitive.long
        if (next < sequence) return
        val text = data.getValue("text").jsonPrimitive.content
        if (next == sequence && source != text) return
        val anchor = NoteInsertion(blockId, 0, text, data.getValue("before").jsonPrimitive.content, data.getValue("after").jsonPrimitive.content)
        anchor.validate()
        val changed = source != text
        sequence = next; source = text
        if (changed) onText(text)
        onPending(false)
        val marks = data["active"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        onInsertion(anchor, data.getValue("focused").jsonPrimitive.boolean, marks)
        data["caret"]?.jsonObject?.let { caret ->
            val top = caret.getValue("top").jsonPrimitive.float
            val bottom = caret.getValue("bottom").jsonPrimitive.float
            require(top.isFinite() && bottom.isFinite() && bottom >= top && top >= -1_000_000 && bottom <= 1_000_000)
            onCaret(top, bottom)
        }
    }

    suspend fun commitAndFreeze() {
        check(!failed && !released) { "Editor unavailable" }
        frozen = true
        clearFocus()
        withTimeout(3_000) {
            while (!ready) { check(!failed); delay(25) }
            while (true) {
                val result = snapshot()
                if (result["composing"]?.jsonPrimitive?.booleanOrNull != true) {
                    receive(result.toString())
                    break
                }
                delay(25)
            }
        }
    }

    private suspend fun snapshot(): JsonObject = suspendCancellableCoroutine { continuation ->
        evaluateJavascript("window.PimEditor.snapshot(true, true)") { raw ->
            if (continuation.isActive) runCatching {
                NoteJson.parseToJsonElement(NoteJson.parseToJsonElement(raw).jsonPrimitive.content).jsonObject
            }.fold(continuation::resume, continuation::resumeWithException)
        }
    }

    fun resumeEditing() { frozen = false; configure() }
    fun command(name: String) {
        if (ready && !failed && !released && !frozen && enabled) evaluateJavascript("window.PimEditor.command(${JsonPrimitive(name)})", null)
    }

    fun releaseEditor() {
        if (released) return
        clearFocus()
        // Capture a final IME commit even when LazyColumn virtualizes this block off screen.
        fun capture(attempt: Int) {
            if (released) return
            if (failed) { dispose(); return }
            runCatching { evaluateJavascript("window.PimEditor?.snapshot(true, true)") { raw ->
                val data = runCatching { NoteJson.parseToJsonElement(NoteJson.parseToJsonElement(raw).jsonPrimitive.content).jsonObject }.getOrNull()
                if (data?.get("composing")?.jsonPrimitive?.booleanOrNull == true && attempt < 30) handler.postDelayed({ capture(attempt + 1) }, 50)
                else {
                    if (data?.get("type")?.jsonPrimitive?.content == "snapshot") runCatching { receive(data.toString()) }
                    else onNotice("离屏正文未能确认输入完成，请返回核对；最近收到的原文保留。")
                    dispose()
                }
            } }.onFailure { dispose() }
        }
        capture(0)
        handler.postDelayed({ dispose() }, 3_000)
    }

    private fun dispose() { if (!released) { released = true; stopLoading(); destroy() } }
    private fun fail(reason: String) { if (!failed) { failed = true; handler.post { onFailure(reason) } } }
    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
