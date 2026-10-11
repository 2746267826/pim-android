package com.pim.app.ui.shell

import com.pim.core.auth.AuthRefreshOperation
import com.pim.core.auth.AuthRefreshResult
import com.pim.core.auth.AuthSessionSnapshot
import com.pim.core.auth.AuthSessionStore
import com.pim.core.auth.AuthTokens
import org.junit.Assert.assertTrue
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/*
 * PW-07a RED 测试设施（非产品代码）：会话/刷新记录桩、字节断言、日志断言。
 * 只用真实 `AuthSessionStore` 契约，不复制被测实现。
 */

/** 只会被读写的会话桩：记录 save/clear 次数，暴露逻辑快照。 */
class RecordingAuthSessionStore : AuthSessionStore {
    @Volatile
    private var current: AuthSessionSnapshot = AuthSessionSnapshot(null)

    val saveCount = AtomicInteger()
    val clearCount = AtomicInteger()

    override fun snapshot(): AuthSessionSnapshot = current

    override fun save(
        accessToken: String,
        refreshToken: String,
        expiresAtUtcMillis: Long,
        serverIdentity: String
    ): Boolean {
        current = AuthSessionSnapshot(
            tokens = AuthTokens(accessToken, refreshToken, expiresAtUtcMillis),
            serverIdentity = serverIdentity
        )
        saveCount.incrementAndGet()
        return true
    }

    override fun clear(): Boolean {
        current = AuthSessionSnapshot(null)
        clearCount.incrementAndGet()
        return true
    }
}

/** 记录型刷新操作：`calls` 是真实被调用的次数（RED 用它证明代发链路确实走到了刷新）。 */
class RecordingRefreshOperation(
    var behavior: suspend (refreshToken: String, serverIdentity: String) -> AuthRefreshResult
) {
    val calls = CopyOnWriteArrayList<String>()

    val operation = AuthRefreshOperation { refreshToken, serverIdentity ->
        calls.add(refreshToken)
        behavior(refreshToken, serverIdentity)
    }
}

fun readAll(stream: InputStream, readSize: Int = 8192): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(readSize)
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        if (n > 0) out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/**
 * 累计式读取：即使中途抛 `IOException`，也保留**已经交给调用方的字节**。
 *
 * 规格 §4.1 允许「扫描命中且已经交出不含令牌的前缀 → 流抛 `IOException`」。旧写法
 * `runCatching { readAll(stream) }.getOrNull() ?: ByteArray(0)` 会在抛异常时把已交出的字节
 * 抹成空数组，于是「不得把前 65536 字节原样交出」这条断言空洞通过（被禁的两段式前缀扫描
 * 正好是这种形态：先交出 65536 字节，再对剩余流扫描）。
 *
 * 返回 `(已交出的字节, 失败原因)`；`null` 表示正常 EOF。
 */
fun readAllKeepingBytesOnFailure(
    stream: InputStream,
    readSize: Int = 8192
): Pair<ByteArray, Throwable?> {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(readSize)
    val failure = runCatching {
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            if (n > 0) out.write(buf, 0, n)
        }
    }.exceptionOrNull()
    return out.toByteArray() to failure
}

fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
    if (needle.isEmpty() || haystack.size < needle.size) return false
    outer@ for (i in 0..(haystack.size - needle.size)) {
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) continue@outer
        }
        return true
    }
    return false
}

/** 规格 §4：日志只能有固定原因码 + origin/path，不得含令牌字节。 */
fun assertNoCanaryInLogs(canary: String, context: String) {
    val hits = ShadowLog.getLogs().filter { item ->
        item.msg?.contains(canary) == true ||
            item.throwable?.stackTraceToString()?.contains(canary) == true
    }
    assertTrue(
        "$context：logcat 不得含令牌字节，实际命中 ${hits.size} 条：" +
            hits.take(3).joinToString(" | ") { it.msg ?: "" },
        hits.isEmpty()
    )
}
