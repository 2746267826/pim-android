package com.pim.app.ui.shell

import okhttp3.ResponseBody
import java.io.IOException
import java.io.InputStream

/*
 * PW-07a 实施（唯一规格 remediation/native-embed/reviewed-spec.md §5.2、§5.3）。
 *
 * 只用一个扫描器，从正文第一字节开始。禁止先把固定前缀原样交给调用方、再对剩余流单独扫描。
 * 本类**不知道**会话，只通过 `needleSource` 在「execute 之前 / execute 返回后 / 每次准备
 * 向外拷贝之前」取快照；针集只增不减，直到这条流关闭。没有最短长度：1..7 字节的非空白
 * 令牌也必须命中（会话入口只要求非空白）。
 *
 * `PREFIX_SCAN_BYTES = 65536` 只决定失败形态，不是安全边界：
 * - `prefill()` 在尚未交出任何正文时扫完前缀；命中则调用方返回空 502；
 * - 之后再命中则停止拷贝并抛 `IOException`，不把这次流结束伪装成正常 EOF。
 *
 * `read` 不可重入：重入抛异常且不向调用方缓冲写入任何字节。
 */
internal class EmbedResponseScanner(
    upstream: ResponseBody,
    private val needleSource: () -> List<ByteArray>,
    initialNeedles: List<ByteArray>,
    private val onClosed: (EmbedResponseScanner) -> Unit
) : InputStream() {

    private val upstreamSource = upstream.byteStream()
    private val upstreamBody = upstream
    private val lock = Any()
    private val needles = ArrayList<ByteArray>()

    private var buffer = ByteArray(INITIAL_BUFFER_BYTES)
    private var size = 0
    private var upstreamEof = false
    private var reading = false

    @Volatile
    private var closed = false

    /** `hold = max(0, 最长针长度 - 1)`，只增不减。 */
    private var hold = 0

    /** 已经交给调用方的字节数（只用于日志/失败形态判定）。 */
    private var handedOut = 0L

    /** 连续「上游 0 字节」次数（防自旋，正常流不会出现）。 */
    private var zeroProgress = 0

    init {
        observe(initialNeedles)
    }

    /** 规格 §5.3：返回 true 表示「尚未交出任何正文就命中针」→ 调用方必须返回空 502。 */
    fun prefill(): Boolean {
        synchronized(lock) {
            while (size < PREFIX_SCAN_BYTES && !upstreamEof) {
                fill()
            }
            val observed = observe(needleSource())
            return containsAnyNeedle(observed)
        }
    }

    override fun read(): Int {
        val single = ByteArray(1)
        val read = read(single, 0, 1)
        return if (read < 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
        synchronized(lock) {
            if (closed) throw IOException("embed scanner closed")
            if (reading) throw IOException("embed scanner read must not be reentrant")
            reading = true
            try {
                while (true) {
                    val observed = observe(needleSource())
                    if (containsAnyNeedle(observed)) {
                        throw IOException("embed scanner matched a session token")
                    }
                    val copyable = size - hold
                    if (copyable > 0) {
                        val n = minOf(len, copyable)
                        System.arraycopy(buffer, 0, b, off, n)
                        dropPrefix(n)
                        handedOut += n
                        return n
                    }
                    if (upstreamEof) {
                        // 剩余字节已在上面扫过，无针；EOF 前先扫完剩余缓冲。
                        if (size == 0) return -1
                        val n = minOf(len, size)
                        System.arraycopy(buffer, 0, b, off, n)
                        dropPrefix(n)
                        handedOut += n
                        return n
                    }
                    fill()
                }
            } finally {
                reading = false
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        onClosed(this)
        // 不持锁：读线程可能正阻塞在上游读取上，关闭上游才能把它解开。
        runCatching { upstreamSource.close() }
        runCatching { upstreamBody.close() }
        synchronized(lock) {
            buffer = EMPTY
            size = 0
        }
    }

    /** 交给调用方的字节数（配合日志用，不含令牌内容）。 */
    fun handedOutBytes(): Long = handedOut

    private fun fill() {
        val chunk = ByteArray(READ_CHUNK_BYTES)
        val read = try {
            upstreamSource.read(chunk)
        } catch (failure: IOException) {
            upstreamEof = true
            throw failure
        }
        if (read < 0) {
            upstreamEof = true
            return
        }
        if (read == 0) {
            // Okio 的流对非零长度不该返回 0。真发生了也不能自旋：连续无进展按 EOF 收尾。
            zeroProgress += 1
            if (zeroProgress >= MAX_ZERO_PROGRESS_READS) upstreamEof = true
            return
        }
        zeroProgress = 0
        append(chunk, 0, read)
    }

    private fun observe(candidates: List<ByteArray>): List<ByteArray> {
        for (candidate in candidates) {
            if (candidate.isEmpty()) continue
            var known = false
            for (existing in needles) {
                if (existing.contentEquals(candidate)) {
                    known = true
                    break
                }
            }
            if (!known) needles.add(candidate)
            if (candidate.size - 1 > hold) hold = candidate.size - 1
        }
        return needles
    }

    private fun containsAnyNeedle(candidates: List<ByteArray>): Boolean {
        for (needle in candidates) {
            if (containsBytes(buffer, size, needle)) return true
        }
        return false
    }

    private fun containsBytes(haystack: ByteArray, haystackSize: Int, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystackSize < needle.size) return false
        outer@ for (i in 0..(haystackSize - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun append(bytes: ByteArray, offset: Int, count: Int) {
        ensureCapacity(size + count)
        System.arraycopy(bytes, offset, buffer, size, count)
        size += count
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        var capacity = buffer.size
        while (capacity < needed) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    private fun dropPrefix(count: Int) {
        if (count <= 0) return
        if (count >= size) {
            size = 0
            return
        }
        System.arraycopy(buffer, count, buffer, 0, size - count)
        size -= count
    }

    companion object {
        /** 规格 §5.3：只决定失败形态，不是安全边界，也不是体积上限。 */
        const val PREFIX_SCAN_BYTES: Int = 65_536

        private const val INITIAL_BUFFER_BYTES = 8_192
        private const val READ_CHUNK_BYTES = 8_192
        private const val MAX_ZERO_PROGRESS_READS = 4
        private val EMPTY = ByteArray(0)
    }
}
