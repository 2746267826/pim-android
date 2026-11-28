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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    private var bindingAllowed = true
    private var committing = false
    private var retiring = false
    private var reviewRequired = false
    private var formatFallback = false
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var commit: Deferred<Unit>? = null
    private var retirement: CompletableDeferred<Unit>? = null
    private var theme = emptyMap<String, String>()
    private var editorBlocks: List<JsonObject>? = null
    private var documentSignature: String? = null
    private var materialPreviews: Map<String, String> = emptyMap()
    private var pendingMaterialScroll: String? = null
    var readCurrentText: () -> String? = { null }
    var readCurrentBlocks: () -> List<JsonObject>? = { null }
    var onText: (String) -> Unit = {}
    var onBlocks: (List<JsonObject>) -> Unit = {}
    var onInsertion: (NoteInsertion, Boolean, Set<String>) -> Unit = { _, _, _ -> }
    var onMaterial: (String) -> Unit = {}
    var onCaret: (Float, Float) -> Unit = { _, _ -> }
    var onHeight: (Int) -> Unit = {}
    var onFailure: (String) -> Unit = {}
    var onReviewRequired: (String) -> Unit = {}
    var onSourceOnly: (String) -> Unit = {}
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
                fail(NoteEditorCoordinator.REVIEW_MESSAGE, true)
                return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) fail("离线正文无法加载，已保留原文并改用 Markdown 源码编辑。")
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this, "PimNoteBridge", setOf(NoteEditorPolicy.ORIGIN)) { _, message, origin, mainFrame, _ ->
                if (mainFrame && origin.toString().trimEnd('/') == NoteEditorPolicy.ORIGIN && url == NoteEditorPolicy.PAGE) {
                    runCatching { receive(message.data.orEmpty()) }.onFailure { fail("编辑消息无法验证，已停止富文本输入；最近原文保留，请改用源码并核对内容。", true) }
                }
            }
        } else fail("当前系统 WebView 不支持安全编辑通信，使用 Markdown 源码编辑。")
    }

    fun bind(text: String, editable: Boolean, colors: Map<String, String>) {
        if (released || failed || formatFallback) return
        enabled = editable; theme = colors
        // Do not disable the DOM or change its epoch while an IME acknowledgement is in flight.
        if (!bindingAllowed || committing || retiring) return
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

    fun bindDocument(blocks: List<JsonObject>, editable: Boolean, colors: Map<String, String>, previews: Map<String, String>) {
        if (released || failed || formatFallback) return
        enabled = editable; theme = colors; materialPreviews = previews
        if (!bindingAllowed || committing || retiring) return
        val current = readCurrentBlocks() ?: blocks
        val nextSignature = blockSignature(current)
        if (editorBlocks == null) {
            editorBlocks = current; documentSignature = nextSignature
        } else if (documentSignature != nextSignature) {
            epoch++; sequence = 0
            editorBlocks = current; documentSignature = nextSignature
        }
        if (url == null) {
            loadUrl(NoteEditorPolicy.PAGE)
            postDelayed({ if (!ready && !released) fail("离线正文未能启动，已保留原文并改用 Markdown 源码编辑。") }, 10_000)
        }
        configure()
        applyMaterialPreviews()
    }

    fun pauseBinding() { bindingAllowed = false }
    fun allowBinding() {
        bindingAllowed = true
        if (released || retiring) return
        val blocks = editorBlocks
        if (blocks != null) bindDocument(readCurrentBlocks() ?: blocks, enabled, theme, materialPreviews)
        else bind(readCurrentText().orEmpty(), enabled, theme)
    }
    fun requireSourceReview(reason: String) = fail(reason, true)

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
            put("enabled", enabled); put("frozen", frozen); put("nativeToolbar", true)
            put("theme", buildJsonObject { theme.forEach { (key, value) -> put(key, value) } })
            val blocks = editorBlocks
            if (blocks != null) {
                put("blocks", buildJsonArray {
                    var photos = 0; var inks = 0; var other = 0
                    blocks.forEach { block ->
                        val kind = block.string("kind")
                        addJsonObject {
                            put("id", block.string("id")); put("kind", kind)
                            if (kind == "markdown") put("text", block.string("text"))
                            else put("label", when (kind) {
                                "photo" -> "照片 ${++photos}"
                                "ink" -> "手写卡片 ${++inks}"
                                else -> "材料 ${++other}"
                            })
                        }
                    }
                })
            } else put("text", source.orEmpty())
        }
        evaluateJavascript("window.PimEditor.configure($config)", null)
        pendingMaterialScroll?.let { id ->
            pendingMaterialScroll = null
            evaluateJavascript("window.PimEditor.scrollToMaterial(${JsonPrimitive(id)})", null)
        }
    }

    private fun receive(raw: String): Boolean {
        if (released || failed) return false
        require(raw.length <= 2_100_000)
        val data = NoteJson.parseToJsonElement(raw).jsonObject
        require(data.getValue("protocol").jsonPrimitive.int == 1)
        if (data["type"]?.jsonPrimitive?.content == "ready" && !ready) {
            ready = true; configure(); applyMaterialPreviews(); return true
        }
        require(data["session"]?.jsonPrimitive?.content == session)
        if (data.getValue("epoch").jsonPrimitive.long != epoch) return false
        return when (data["type"]?.jsonPrimitive?.content) {
            "snapshot" -> if (data["blocks"] is JsonArray) acceptDocumentSnapshot(data) else acceptSnapshot(data)
            "composing" -> { onPending(true); false }
            "height" -> { onHeight(data.getValue("height").jsonPrimitive.int.coerceIn(1, 8192)); true }
            "sourceOnly" -> {
                formatFallback = true
                onSourceOnly(data.getValue("reason").jsonPrimitive.content.take(400))
                true
            }
            "material" -> { onMaterial(data.getValue("id").jsonPrimitive.content); true }
            "notice" -> { onNotice(data.getValue("reason").jsonPrimitive.content.take(400)); true }
            "error" -> { fail(NoteEditorCoordinator.REVIEW_MESSAGE, true); false }
            else -> false
        }
    }

    private fun acceptSnapshot(data: JsonObject): Boolean {
        val next = data.getValue("sequence").jsonPrimitive.long
        if (next < sequence) return false
        val text = data.getValue("text").jsonPrimitive.content
        if (next == sequence && source != text) return false
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
        return true
    }

    private fun acceptDocumentSnapshot(data: JsonObject): Boolean {
        val next = data.getValue("sequence").jsonPrimitive.long
        if (next < sequence) return false
        val blocks = data.getValue("blocks").jsonArray.map { it.jsonObject }
        val nextSignature = blockSignature(blocks)
        if (next == sequence && documentSignature != null && documentSignature != nextSignature) return false
        val changed = documentSignature != nextSignature
        sequence = next
        editorBlocks = blocks
        documentSignature = nextSignature
        if (changed) onBlocks(blocks)
        onPending(false)
        val focused = data.getValue("focused").jsonPrimitive.boolean
        val marks = data["active"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        data["target"]?.jsonObject?.let { target ->
            val anchor = NoteInsertion(
                target.getValue("blockId").jsonPrimitive.content,
                0,
                target.getValue("expectedText").jsonPrimitive.content,
                target.getValue("before").jsonPrimitive.content,
                target.getValue("after").jsonPrimitive.content,
            )
            anchor.validate()
            onInsertion(anchor, focused, marks)
        }
        data["caret"]?.jsonObject?.let { caret ->
            val top = caret.getValue("top").jsonPrimitive.float
            val bottom = caret.getValue("bottom").jsonPrimitive.float
            require(top.isFinite() && bottom.isFinite() && bottom >= top && top >= -1_000_000 && bottom <= 1_000_000)
            onCaret(top, bottom)
        }
        return true
    }

    private fun blockSignature(blocks: List<JsonObject>): String = buildString {
        blocks.forEach { block ->
            append(block.string("id")); append('\u0000')
            append(block.string("kind")); append('\u0000')
            if (block.string("kind") == "markdown") append(block.string("text"))
            append('\u0001')
        }
    }

    private fun applyMaterialPreviews() {
        if (!ready || failed || released || materialPreviews.isEmpty()) return
        val payload = buildJsonObject { materialPreviews.forEach { (id, data) -> put(id, data) } }
        evaluateJavascript("window.PimEditor.setMaterialPreviews($payload)", null)
    }

    fun updateMaterialPreviews(previews: Map<String, String>) {
        materialPreviews = previews
        applyMaterialPreviews()
    }

    fun scrollToMaterial(id: String) {
        if (ready && !failed && !released) {
            pendingMaterialScroll = null
            evaluateJavascript("window.PimEditor.scrollToMaterial(${JsonPrimitive(id)})", null)
        } else pendingMaterialScroll = id
    }

    suspend fun commitAndFreeze() {
        if (released) { checkNotNull(retirement).await(); return }
        if (failed) { check(!reviewRequired) { "Editor source requires review" }; return }
        if (formatFallback) return
        if (!bindingAllowed && source == null) return // This successor has never accepted input.
        val existing = commit
        if (existing != null && (!existing.isCompleted || frozen)) { existing.await(); return }
        val request = work.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            committing = true; frozen = true
            try {
                clearFocus()
                withTimeout(3_000) {
                    while (!ready) { check(!failed && !released); delay(25) }
                    while (true) {
                        val result = snapshot()
                        if (result["composing"]?.jsonPrimitive?.booleanOrNull != true) {
                            check(receive(result.toString())) { "Editor acknowledgement was superseded" }
                            break
                        }
                        onPending(true)
                        delay(25)
                    }
                }
            } catch (error: Exception) {
                fail(NoteEditorCoordinator.REVIEW_MESSAGE, true)
                throw error
            } finally { committing = false }
        }
        commit = request; request.start(); request.await()
    }

    private suspend fun snapshot(): JsonObject = suspendCancellableCoroutine { continuation ->
        evaluateJavascript("window.PimEditor.snapshot(true, true)") { raw ->
            if (continuation.isActive) runCatching {
                NoteJson.parseToJsonElement(NoteJson.parseToJsonElement(raw).jsonPrimitive.content).jsonObject
            }.fold(continuation::resume, continuation::resumeWithException)
        }
    }

    fun resumeEditing() {
        if (!retiring && !released && !failed && !formatFallback) {
            frozen = false
            val blocks = editorBlocks
            if (blocks != null) bindDocument(readCurrentBlocks() ?: blocks, enabled, theme, materialPreviews)
            else bind(readCurrentText() ?: source.orEmpty(), enabled, theme)
        }
    }
    fun command(name: String) {
        if (ready && !failed && !released && !frozen && enabled) evaluateJavascript("window.PimEditor.command(${JsonPrimitive(name)})", null)
    }

    fun releaseEditor(): Deferred<Unit> {
        retirement?.let { return it }
        val result = CompletableDeferred<Unit>()
        retirement = result; retiring = true
        // The view owns this final capture, so virtualizing its Compose item cannot cancel it.
        work.launch {
            try { commitAndFreeze(); result.complete(Unit) }
            catch (error: Exception) { result.completeExceptionally(error) }
            finally { dispose(); work.cancel() }
        }
        return result
    }

    private fun dispose() { if (!released) { released = true; stopLoading(); destroy() } }
    private fun fail(reason: String, needsReview: Boolean = false) {
        if (!failed) {
            failed = true; reviewRequired = needsReview
            if (needsReview) { onPending(true); onReviewRequired(reason) }
            handler.post { onFailure(reason) }
        }
    }
    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
