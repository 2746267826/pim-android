package com.pim.app.mobile.diagnostics

import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.room.withTransaction
import com.pim.app.data.AppDatabase
import com.pim.app.location.PolicyTransitionWriteFailure
import com.pim.app.location.PolicyTransitionWriteFailureSource
import com.pim.app.location.service.ForegroundLocationRuntimeState
import com.pim.app.mobile.logs.StructuredLogFileRef
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.permissions.PermissionStatusRepository
import com.pim.app.schedule.ScheduleCacheFreshness
import com.pim.app.schedule.ScheduleCacheSnapshot
import com.pim.app.schedule.ScheduleRefreshErrorKind
import com.pim.app.schedule.ScheduleWindowRepository
import com.pim.app.settings.TrackingSettingsStore
import com.pim.app.status.ConnectionProbeStore
import com.pim.app.status.PermissionStatusSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

interface DiagnosticOperations {
    suspend fun export(includeRecentLocations: Boolean): DiagnosticExportResult
    suspend fun clearDiagnostics()
}

data class DiagnosticExportResult(
    val file: File,
    val coordinateCount: Int,
    val isPartial: Boolean = false,
    val summary: String = ""
)

class DiagnosticExportException(
    val code: String,
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)

private data class LogFileExport(
    val name: String,
    val originalBytes: Long,
    val includedBytes: Long,
    val exportedBytes: Long,
    val oversizedLineCount: Int,
    val reasons: Set<String>
) {
    val suppressedBytes: Long get() = (originalBytes - includedBytes).coerceAtLeast(0L)
}

private data class OmittedLogFile(
    val name: String,
    val originalBytes: Long,
    val reason: String
)

private data class LogFileWriteResult(
    val readBytes: Long,
    val file: LogFileExport
)

private data class LogExportSummary(
    val files: MutableList<LogFileExport> = mutableListOf(),
    val omittedFiles: MutableList<OmittedLogFile> = mutableListOf()
) {
    fun add(file: LogFileExport) {
        files += file
    }

    fun omitFile(ref: StructuredLogFileRef, reason: String) {
        omittedFiles += OmittedLogFile("logs/${ref.fileName}", ref.lengthBytes, reason)
    }

    fun toSummary(maxLineBytes: Int, maxFileBytes: Long, maxTotalBytes: Long) = LogExportView(
        limits = LogExportLimits(maxLineBytes, maxFileBytes, maxTotalBytes),
        files = files.toList(),
        omittedFiles = omittedFiles.toList()
    )
}

private data class LogExportLimits(
    val maxLineBytes: Int,
    val maxFileBytes: Long,
    val maxTotalBytes: Long
)

private data class LogExportView(
    val limits: LogExportLimits,
    val files: List<LogFileExport>,
    val omittedFiles: List<OmittedLogFile>
) {
    val includedBytes: Long = files.sumOf { it.includedBytes }
    val suppressedBytes: Long = files.sumOf { it.suppressedBytes } +
        omittedFiles.sumOf { it.originalBytes }
    val oversizedLineCount: Int = files.sumOf { it.oversizedLineCount }
    val reasons: Set<String> = buildSet {
        files.forEach { addAll(it.reasons) }
        omittedFiles.forEach { add(it.reason) }
    }
    val isPartial: Boolean get() = suppressedBytes > 0L || omittedFiles.isNotEmpty()
    val userSummary: String
        get() = if (isPartial) {
            "日志导出不完整：省略 $suppressedBytes 字节、${omittedFiles.size} 个文件、" +
                "$oversizedLineCount 条超长行；详见 manifest.json"
        } else {
            "日志完整导出"
        }
}

@Singleton
class DiagnosticExportRepository internal constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val structuredLogRepository: StructuredLogRepository,
    private val trackingSettingsStore: TrackingSettingsStore,
    private val redactor: DiagnosticRedactor,
    private val connectionProbeStore: ConnectionProbeStore,
    private val permissionSnapshot: () -> PermissionStatusSnapshot,
    private val serviceRunning: () -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clearProbe: () -> Boolean = { connectionProbeStore.clear() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxLogLineBytes: Int = DEFAULT_MAX_LOG_LINE_BYTES,
    private val maxLogFileBytes: Long = DEFAULT_MAX_LOG_FILE_BYTES,
    private val maxLogTotalBytes: Long = DEFAULT_MAX_LOG_TOTAL_BYTES,
    private val availableBytes: () -> Long = {
        File(context.filesDir, "diagnostics/exports").mkdirs()
        StatFs(File(context.filesDir, "diagnostics/exports").path).availableBytes
    },
    private val publish: (tmp: File, final: File) -> Unit = { tmp, final ->
        try {
            Files.move(tmp.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), final.toPath())
        } catch (e: UnsupportedOperationException) {
            Files.move(tmp.toPath(), final.toPath())
        }
    },
    private val deleteExportFile: (File) -> Boolean = { it.delete() },
    private val mutex: Mutex = Mutex(),
    private val scheduleSnapshot: () -> ScheduleCacheSnapshot = {
        ScheduleCacheSnapshot(
            serverIdentity = "",
            windows = emptyList(),
            freshness = ScheduleCacheFreshness.Missing,
            lastAttemptAtMillis = null,
            lastSuccessAtMillis = null,
            lastError = null,
            errorKind = null
        )
    },
    private val runtimeSnapshot: () -> ForegroundLocationRuntimeState = {
        ForegroundLocationRuntimeState()
    },
    /**
     * WO-ANDROID-POLICY-TRANSITION-20260928 REQ-3：策略切换写入失败状态
     * （连续失败次数 + 最近失败时间），随诊断导出一起给出（AC-3.1 ③）。
     */
    private val policyTransitionWriteFailure: () -> PolicyTransitionWriteFailure = {
        PolicyTransitionWriteFailure.None
    }
) : DiagnosticOperations {
    @Inject constructor(
        @ApplicationContext context: Context,
        db: AppDatabase,
        structuredLogRepository: StructuredLogRepository,
        trackingSettingsStore: TrackingSettingsStore,
        redactor: DiagnosticRedactor,
        connectionProbeStore: ConnectionProbeStore,
        permissionStatusRepository: PermissionStatusRepository,
        scheduleWindowRepository: ScheduleWindowRepository,
        policyTransitionWriteFailureSource: PolicyTransitionWriteFailureSource
    ) : this(
        context = context,
        db = db,
        structuredLogRepository = structuredLogRepository,
        trackingSettingsStore = trackingSettingsStore,
        redactor = redactor,
        connectionProbeStore = connectionProbeStore,
        permissionSnapshot = { permissionStatusRepository.snapshot() },
        serviceRunning = { com.pim.app.location.service.ForegroundLocationService.isRunning() },
        scheduleSnapshot = { scheduleWindowRepository.snapshot.value },
        runtimeSnapshot = { com.pim.app.location.service.ForegroundLocationService.runtimeState.value },
        policyTransitionWriteFailure = { policyTransitionWriteFailureSource.state.value }
    )

    override suspend fun export(includeRecentLocations: Boolean): DiagnosticExportResult = withContext(dispatcher) {
        mutex.withLock {
            val dao = db.mobileDataDao()
            val exportNow = nowMillis()
            val exportDir = File(context.filesDir, "diagnostics/exports")
            exportDir.mkdirs()
            val tmpFile = File(exportDir, "pim-diagnostics-$exportNow.zip.tmp")
            val finalFile = File(exportDir, "pim-diagnostics-$exportNow.zip")

            try {
                val coreEntries = linkedMapOf<String, String>()
                coreEntries["status.json"] = buildStatus(exportNow, dao).toString()
                coreEntries["settings.json"] = buildSettings().toString()
                coreEntries["database-counts.json"] = buildDatabaseCounts(dao).toString()
                coreEntries["sync-history.json"] = buildSyncHistory(dao).toString()
                coreEntries["dropped-locations.jsonl"] = buildDroppedLocationDetails()
                coreEntries["forensics.jsonl"] = buildForensicEvents(exportNow)

                var coordinateCount = 0
                if (includeRecentLocations) {
                    val locations = dao.diagnosticLocations(
                        exportNow - 24L * 3600_000L,
                        exportNow
                    )
                    coordinateCount = locations.size
                    coreEntries["locations.jsonl"] = locations.joinToString("\n") { loc ->
                        locationToJson(loc).toString()
                    }
                }

                val logRefs = structuredLogRepository.snapshotFileRefs()
                val selectedLogBytes = selectedLogInputBytes(logRefs)
                val coreBytes = coreEntries.entries.sumOf { (name, content) ->
                    name.toByteArray(Charsets.UTF_8).size.toLong() +
                        content.toByteArray(Charsets.UTF_8).size.toLong()
                }
                if (coreBytes + selectedLogBytes + 1_048_576L > availableBytes()) {
                    throw DiagnosticExportException(
                        "INSUFFICIENT_STORAGE",
                        "Not enough storage space for export"
                    )
                }

                val logSummary = LogExportSummary()
                val writtenNames = linkedSetOf<String>()
                BufferedOutputStream(tmpFile.outputStream()).use { bos ->
                    ZipOutputStream(bos).use { zos ->
                        for ((name, content) in coreEntries) {
                            writeRedactedEntry(zos, name, content)
                            writtenNames.add(name)
                        }

                        var totalReadBytes = 0L
                        for (ref in logRefs) {
                            val entryName = "logs/${ref.fileName}"
                            require(isValidLogName(entryName)) { "Invalid log entry name: $entryName" }
                            require(!redactor.isUnsafeEntryName(entryName)) { "Unsafe entry name: $entryName" }

                            if (ref.lengthBytes <= 0L) continue
                            val remainingTotal = maxLogTotalBytes - totalReadBytes
                            val readBudget = minOf(
                                ref.lengthBytes,
                                maxLogFileBytes,
                                remainingTotal
                            )
                            if (readBudget <= 0L) {
                                logSummary.omitFile(ref, "total-limit")
                                continue
                            }

                            val logFile = File(context.filesDir, "logs/${ref.fileName}")
                            val result = try {
                                writeLogEntry(
                                    zos = zos,
                                    file = logFile,
                                    entryName = entryName,
                                    originalBytes = ref.lengthBytes,
                                    readBudget = readBudget,
                                    totalBudget = remainingTotal
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: FileNotFoundException) {
                                logSummary.omitFile(
                                    ref,
                                    if (logFile.exists()) "unreadable-file" else "missing-file"
                                )
                                continue
                            }
                            totalReadBytes += result.readBytes
                            logSummary.add(result.file)
                            writtenNames.add(entryName)
                        }

                        val summary = logSummary.toSummary(
                            maxLineBytes = maxLogLineBytes,
                            maxFileBytes = maxLogFileBytes,
                            maxTotalBytes = maxLogTotalBytes
                        )
                        val manifest = buildManifest(
                            includeRecentLocations = includeRecentLocations,
                            exportNow = exportNow,
                            coordinateCount = coordinateCount,
                            entries = writtenNames + "manifest.json",
                            logExport = summary
                        ).toString()
                        writeRedactedEntry(zos, "manifest.json", manifest)
                        writtenNames.add("manifest.json")
                    }
                }

                val expectedNames = writtenNames.toSet()
                ZipFile(tmpFile).use { zip ->
                    val actualNames = mutableSetOf<String>()
                    for (entry in zip.entries().asSequence()) {
                        val name = entry.name
                        require(actualNames.add(name)) { "Duplicate entry: $name" }
                        require(!redactor.isUnsafeEntryName(name)) { "Unsafe entry name after write: $name" }
                        require(name in expectedNames) { "Unexpected entry after write: $name" }
                    }
                    require(actualNames == expectedNames) {
                        "ZIP entry mismatch: expected $expectedNames, got $actualNames"
                    }
                }

                currentCoroutineContext().ensureActive()
                publish(tmpFile, finalFile)
                val summary = logSummary.toSummary(
                    maxLineBytes = maxLogLineBytes,
                    maxFileBytes = maxLogFileBytes,
                    maxTotalBytes = maxLogTotalBytes
                )
                DiagnosticExportResult(
                    file = finalFile,
                    coordinateCount = coordinateCount,
                    isPartial = summary.isPartial,
                    summary = summary.userSummary
                )
            } catch (e: CancellationException) {
                tmpFile.delete()
                throw e
            } catch (e: DiagnosticExportException) {
                tmpFile.delete()
                throw e
            } catch (e: Exception) {
                tmpFile.delete()
                throw DiagnosticExportException(
                    "EXPORT_FAILED",
                    "Diagnostic export failed",
                    e
                )
            }
        }
    }
    override suspend fun clearDiagnostics() = withContext(dispatcher) {
        mutex.withLock {
            var probeFailed = false
            try {
                if (!clearProbe()) {
                    probeFailed = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                probeFailed = true
            }

            var logClearFailed = false
            try {
                logClearFailed = !structuredLogRepository.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logClearFailed = true
            }

            var roomClearFailed = false
            try {
                val dao = db.mobileDataDao()
                db.withTransaction {
                    dao.deleteAllMobileLogs()
                    dao.deleteAllMobileLocationDroppedDiagnostics()
                    dao.deleteAllMobileLocationPolicyTransitions()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                roomClearFailed = true
            }

            val exportDir = File(context.filesDir, "diagnostics/exports")
            var anyDeleteFailed = false
            if (exportDir.isDirectory) {
                exportDir.listFiles()?.forEach { file ->
                    if (file.isFile && EXPORT_FILE_PATTERN.matches(file.name)) {
                        try {
                            if (!deleteExportFile(file)) {
                                anyDeleteFailed = true
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            anyDeleteFailed = true
                        }
                    }
                }
            }
            if (probeFailed || logClearFailed || roomClearFailed || anyDeleteFailed) {
                throw DiagnosticExportException(
                    "CLEAR_FAILED",
                    "Failed to clear diagnostics"
                )
            }
        }
    }

    private fun selectedLogInputBytes(refs: List<StructuredLogFileRef>): Long {
        var selected = 0L
        for (ref in refs) {
            val remaining = maxLogTotalBytes - selected
            if (remaining <= 0L) break
            selected += minOf(ref.lengthBytes, maxLogFileBytes, remaining)
        }
        return selected
    }

    private fun writeRedactedEntry(zos: ZipOutputStream, name: String, content: String) {
        require(!redactor.isUnsafeEntryName(name)) { "Unsafe entry name: $name" }
        val redacted = content.lines()
            .filter { it.isNotBlank() }
            .joinToString("\n") { line -> redactor.redactJsonLine(line) }
        require(redactor.findCredentialLeaks(redacted).isEmpty()) { "Credential leak in $name" }
        zos.putNextEntry(ZipEntry(name))
        zos.write(redacted.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private suspend fun writeLogEntry(
        zos: ZipOutputStream,
        file: File,
        entryName: String,
        originalBytes: Long,
        readBudget: Long,
        totalBudget: Long
    ): LogFileWriteResult {
        val reasons = linkedSetOf<String>()
        if (originalBytes > maxLogFileBytes) reasons.add("file-limit")
        if (originalBytes > totalBudget) reasons.add("total-limit")

        var actualReadBytes = 0L
        var representedBytes = 0L
        var oversizedLineCount = 0
        var exportedBytes = 0L
        var lineBytes = 0L
        var oversizedLine = false
        val lineBuffer = ByteArrayOutputStream(minOf(maxLogLineBytes, 8192))

        BoundedInputStream(BufferedInputStream(file.inputStream()), readBudget).use { input ->
            zos.putNextEntry(ZipEntry(entryName))
            val chunk = ByteArray(8192)

            fun writeText(text: String) {
                val encoded = text.toByteArray(Charsets.UTF_8)
                zos.write(encoded)
                exportedBytes += encoded.size.toLong()
            }

            fun resetLine() {
                lineBytes = 0L
                oversizedLine = false
                lineBuffer.reset()
            }

            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(chunk)
                if (read < 0) break
                for (index in 0 until read) {
                    val value = chunk[index]
                    if (value == '\n'.code.toByte()) {
                        if (oversizedLine) {
                            oversizedLineCount++
                            reasons.add("line-limit")
                            writeText(
                                omissionLine("line-limit", lineBytes + 1L) + "\n"
                            )
                        } else {
                            representedBytes += lineBytes + 1L
                            if (lineBytes > 0L) {
                                val raw = lineBuffer.toByteArray()
                                val end = if (raw.lastOrNull() == '\r'.code.toByte()) {
                                    raw.size - 1
                                } else {
                                    raw.size
                                }
                                val line = String(raw, 0, end, Charsets.UTF_8)
                                if (line.isNotBlank()) {
                                    val redacted = redactor.redactJsonLine(line)
                                    require(redactor.findCredentialLeaks(redacted).isEmpty()) {
                                        "Credential leak in $entryName"
                                    }
                                    writeText(redacted + "\n")
                                }
                            }
                        }
                        resetLine()
                    } else {
                        lineBytes++
                        if (!oversizedLine && lineBytes > maxLogLineBytes) {
                            oversizedLine = true
                            lineBuffer.reset()
                        } else if (!oversizedLine) {
                            lineBuffer.write(value.toInt())
                        }
                    }
                }
            }

            if (lineBytes > 0L || oversizedLine) {
                reasons.add("incomplete-line")
                writeText(omissionLine("incomplete-line", lineBytes) + "\n")
            }
            actualReadBytes = input.bytesRead
            zos.closeEntry()
        }

        if (actualReadBytes < minOf(originalBytes, readBudget)) {
            reasons.add("file-changed")
        }
        if (originalBytes > readBudget && reasons.isEmpty()) {
            reasons.add(if (readBudget == maxLogFileBytes) "file-limit" else "total-limit")
        }
        val summary = LogFileExport(
            name = entryName,
            originalBytes = originalBytes,
            includedBytes = representedBytes,
            exportedBytes = exportedBytes,
            oversizedLineCount = oversizedLineCount,
            reasons = reasons
        )
        if (summary.suppressedBytes > 0L && summary.reasons.isEmpty()) {
            reasons.add("incomplete-line")
        }
        return LogFileWriteResult(
            readBytes = actualReadBytes,
            file = summary.copy(reasons = reasons.toSet())
        )
    }

    private fun omissionLine(reason: String, originalBytes: Long): String =
        JSONObject()
            .put(
                "exportOmission",
                JSONObject()
                    .put("reason", reason)
                    .put("originalBytes", originalBytes)
            )
            .toString()

    private fun buildManifest(
        includeRecentLocations: Boolean,
        exportNow: Long,
        coordinateCount: Int,
        entries: Set<String>,
        logExport: LogExportView
    ): JSONObject {
        val pInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionName = pInfo?.versionName ?: "unknown"
        val versionCode: Long = if (pInfo != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } else 0L

        val result = JSONObject()
            .put("formatVersion", 1)
            .put("createdAtUtc", exportNow)
            .put("appVersionName", versionName)
            .put("appVersionCode", versionCode)
            .put("includeRecentLocations", includeRecentLocations)
            .put("coordinateCount", coordinateCount)
            .put("entries", JSONArray(entries.sorted()))
            .put("logExport", logExport.toJson())

        if (includeRecentLocations) {
            result.put("coordinateRangeStartUtc", exportNow - 24L * 3600_000L)
            result.put("coordinateRangeEndUtc", exportNow)
        } else {
            result.put("coordinateRangeStartUtc", JSONObject.NULL)
            result.put("coordinateRangeEndUtc", JSONObject.NULL)
        }
        return result
    }

    private fun LogExportView.toJson(): JSONObject = JSONObject()
        .put("completeness", if (isPartial) "partial" else "complete")
        .put(
            "limits",
            JSONObject()
                .put("maxLineBytes", limits.maxLineBytes)
                .put("maxFileBytes", limits.maxFileBytes)
                .put("maxTotalBytes", limits.maxTotalBytes)
        )
        .put("includedBytes", includedBytes)
        .put("suppressedBytes", suppressedBytes)
        .put("omittedFileCount", omittedFiles.size)
        .put("oversizedLineCount", oversizedLineCount)
        .put("suppressionReasons", JSONArray(reasons.sorted()))
        .put(
            "files",
            JSONArray(
                files.map { file ->
                    JSONObject()
                        .put("name", file.name)
                        .put("originalBytes", file.originalBytes)
                        .put("includedBytes", file.includedBytes)
                        .put("exportedBytes", file.exportedBytes)
                        .put("suppressedBytes", file.suppressedBytes)
                        .put("oversizedLineCount", file.oversizedLineCount)
                        .put("suppressionReasons", JSONArray(file.reasons.sorted()))
                }
            )
        )
        .put(
            "omittedFiles",
            JSONArray(
                omittedFiles.map { file ->
                    JSONObject()
                        .put("name", file.name)
                        .put("originalBytes", file.originalBytes)
                        .put("reason", file.reason)
                }
            )
        )
    companion object {
        private const val DEFAULT_MAX_LOG_LINE_BYTES = 64 * 1024
        private const val DEFAULT_MAX_LOG_FILE_BYTES = 8L * 1024 * 1024
        private const val DEFAULT_MAX_LOG_TOTAL_BYTES = 24L * 1024 * 1024
        private val LOG_NAME_PATTERN = Regex("""^logs/mobile-\d{4}-\d{2}-\d{2}\.jsonl$""")
        private val EXPORT_FILE_PATTERN = Regex("""^pim-diagnostics-\d+\.zip(?:\.tmp)?$""")
        private val CORE_ENTRIES = setOf(
            "manifest.json", "status.json", "settings.json",
            "database-counts.json", "sync-history.json",
            // REQ-9 / REQ-1~REQ-4：取证两件套随包导出（丢弃明细 + 死因台账）。
            "dropped-locations.jsonl", "forensics.jsonl"
        )

        private fun buildExpectedEntrySet(
            validatedLogEntryNames: List<String>,
            includeRecentLocations: Boolean
        ): Set<String> {
            val result = CORE_ENTRIES.toMutableSet()
            result.addAll(validatedLogEntryNames)
            if (includeRecentLocations) {
                result.add("locations.jsonl")
            }
            return result
        }

        internal fun isValidLogName(name: String): Boolean =
            LOG_NAME_PATTERN.matches(name)
    }

    private suspend fun buildStatus(
        exportNow: Long,
        dao: com.pim.app.data.MobileDataDao
    ): JSONObject {
        val snap = permissionSnapshot()

        val settings = trackingSettingsStore.read()

        val probeResult = connectionProbeStore.result.value

        val status = JSONObject()
            .put("generatedAtUtc", exportNow)
            .put("notificationGranted", snap.notificationGranted)
            .put("preciseLocationGranted", snap.preciseLocationGranted)
            .put("backgroundLocationGranted", snap.backgroundLocationGranted)
            .put("usageAccessGranted", snap.usageAccessGranted)
            .put("activityRecognitionGranted", snap.activityRecognitionGranted)
            .put("batteryOptimizationGranted", snap.batteryOptimizationGranted)
            .put("collectionIntentEnabled", settings.continuousCollectionEnabled)
            .put("serviceRunning", serviceRunning())
            .put("probeOutcome", probeResult?.outcome?.name ?: JSONObject.NULL)
            .put("probeCheckedAtUtc", probeResult?.checkedAtUtcMillis ?: JSONObject.NULL)

        status.put("appUsagePendingCount", db.appUsageDao().unsyncedCount().first())
        status.put("usageEventsPendingCount", dao.pendingUsageEventCount().first())
        status.put("usageSummariesPendingCount", dao.pendingUsageSummaryCount().first())
        status.put("appMetadataPendingCount", dao.pendingAppMetadataCount().first())
        status.put("locationPointsPendingCount", dao.pendingLocationPointCount().first())
        status.put("syncBatchesPendingCount", dao.pendingSyncBatchCount().first())
        status.put("deviceProfilePendingCount", dao.pendingDeviceProfileCount().first())
        status.put("aggregateRejectedCount", dao.aggregateRejectedCount().first())

        val sched = scheduleSnapshot()
        status.put("scheduleFreshness", sched.freshness.name)
        status.put("scheduleLastSuccessAtUtc", sched.lastSuccessAtMillis ?: JSONObject.NULL)
        status.put("scheduleLastAttemptAtUtc", sched.lastAttemptAtMillis ?: JSONObject.NULL)
        status.put("scheduleLastError", mapErrorKindToSummary(sched.lastError, sched.errorKind))

        val rt = runtimeSnapshot()
        status.put("currentPolicyMode", rt.currentPolicyMode)
        status.put("currentPolicyReason", rt.currentPolicyReason ?: JSONObject.NULL)
        status.put("currentPolicyRequestIntervalMillis", rt.requestIntervalMillis ?: JSONObject.NULL)

        // REQ-3：写入失败可见化——导出新增「连续失败次数 + 最近一次失败时间」（AC-3.1 ③）。
        val writeFailure = policyTransitionWriteFailure()
        status.put("policyTransitionWriteFailureCount", writeFailure.consecutiveFailures)
        status.put(
            "policyTransitionWriteLastFailureAtUtc",
            writeFailure.lastFailureAtUtcMillis ?: JSONObject.NULL
        )

        val transitions = dao.recentPolicyTransitions(limit = 20).first()
        val transitionsArr = JSONArray()
        for (t in transitions) {
            transitionsArr.put(
                JSONObject()
                    .put("fromMode", t.fromMode ?: JSONObject.NULL)
                    .put("toMode", t.toMode)
                    .put("reason", t.reason)
                    .put("occurredAtUtc", t.occurredAtUtc)
            )
        }
        status.put("recentPolicyTransitions", transitionsArr)

        return status
    }

    private fun mapErrorKindToSummary(
        lastError: String?,
        errorKind: ScheduleRefreshErrorKind?
    ): Any {
        if (lastError == null) return JSONObject.NULL
        return when (errorKind) {
            ScheduleRefreshErrorKind.Authentication -> "认证失败"
            ScheduleRefreshErrorKind.Network -> "网络错误"
            ScheduleRefreshErrorKind.Server -> "服务器错误"
            ScheduleRefreshErrorKind.Cache -> "缓存错误"
            null -> "未知错误"
        }
    }

    /**
     * 丢弃原因明细（REQ-9 / AC-9.1）：时刻 / 原因 / 准确度 / provider / 策略档。
     * 不设条数上限，条数由 30 天时间清理决定（AC-9.3）。
     */
    private suspend fun buildDroppedLocationDetails(): String {
        val rows = db.mobileDataDao().diagnosticDroppedDetailRows()
        return rows.joinToString("\n") { row ->
            JSONObject()
                .put("recordedAtUtc", row.recordedAtUtc)
                .put("reason", row.reason)
                .put("accuracyMeters", row.accuracyMeters?.toDouble() ?: JSONObject.NULL)
                .put("provider", row.provider ?: JSONObject.NULL)
                .put("policyMode", row.policyMode)
                .toString()
        }
    }

    /**
     * 进程退出原因台账与存活心跳（REQ-1 ~ REQ-4）。
     * 负载本身已在写入时脱敏（不含经纬度/令牌/账号），这里原样带出以便核对死因。
     */
    private suspend fun buildForensicEvents(exportNow: Long): String {
        val rows = db.forensicEventDao().eventsInRange(0L, exportNow)
        return rows.joinToString("\n") { row ->
            JSONObject()
                .put("eventType", row.eventType)
                .put("occurredAtUtc", row.occurredAtUtc)
                .put("clientItemKey", row.clientItemKey)
                .put("syncStatus", row.syncStatus)
                .put("payload", payloadOf(row.payloadJson))
                .toString()
        }
    }

    private fun payloadOf(json: String): Any = try {
        JSONObject(json)
    } catch (_: Exception) {
        json
    }

    private fun buildSettings(): JSONObject {
        val s = trackingSettingsStore.read()
        val verboseEnabled = s.verboseLoggingUntilUtcMillis != null &&
            s.verboseLoggingUntilUtcMillis > nowMillis()
        return JSONObject()
            .put("profile", s.profile)
            .put("continuousCollectionEnabled", s.continuousCollectionEnabled)
            .put("normalIntervalMillis", s.normalIntervalMillis)
            .put("scheduleLowFrequencyIntervalMillis", s.scheduleLowFrequencyIntervalMillis)
            .put("movementIntervalMillis", s.movementIntervalMillis)
            .put("scheduleRecoveryThresholdMeters", s.scheduleRecoveryThresholdMeters)
            .put("altitudeWaitTimeoutMillis", s.altitudeWaitTimeoutMillis)
            .put("syncOnUnmeteredOnly", s.syncOnUnmeteredOnly)
            .put("logRetentionDays", s.logRetentionDays)
            .put("verboseLoggingEnabled", verboseEnabled)
            .put(
                "verboseLoggingUntilUtcMillis",
                s.verboseLoggingUntilUtcMillis ?: JSONObject.NULL
            )
    }

    private suspend fun buildDatabaseCounts(
        dao: com.pim.app.data.MobileDataDao
    ): JSONObject {
        val c = dao.diagnosticDatabaseCounts()
        return JSONObject()
            .put("appUsageRowCount", c.appUsageRowCount)
            .put("mobileUsageEventsRowCount", c.mobileUsageEventsRowCount)
            .put("mobileUsageSummariesRowCount", c.mobileUsageSummariesRowCount)
            .put("mobileAppMetadataRowCount", c.mobileAppMetadataRowCount)
            .put("mobileLocationPointsRowCount", c.mobileLocationPointsRowCount)
            .put("mobileLocationDroppedDiagnosticsRowCount", c.mobileLocationDroppedDiagnosticsRowCount)
            .put("mobileLocationPolicyTransitionsRowCount", c.mobileLocationPolicyTransitionsRowCount)
            .put("mobileSyncBatchesRowCount", c.mobileSyncBatchesRowCount)
            .put("mobileLogsRowCount", c.mobileLogsRowCount)
            .put("mobileDeviceProfileRowCount", c.mobileDeviceProfileRowCount)
            .put("mobileForensicEventsRowCount", c.mobileForensicEventsRowCount)
    }

    private suspend fun buildSyncHistory(
        dao: com.pim.app.data.MobileDataDao
    ): JSONArray {
        val rows = dao.diagnosticSyncHistory(limit = 100)
        val arr = JSONArray()
        for (row in rows) {
            val obj = JSONObject()
                .put("entityType", row.entityType)
                .put("rowCount", row.rowCount)
                .put("startedAtUtc", row.startedAtUtc ?: JSONObject.NULL)
                .put("finishedAtUtc", row.finishedAtUtc ?: JSONObject.NULL)
                .put("syncStatus", row.syncStatus)
                .put("createdAtUtc", row.createdAtUtc)
            arr.put(obj)
        }
        return arr
    }

    private fun locationToJson(loc: com.pim.app.data.DiagnosticLocationRow): JSONObject {
        return JSONObject()
            .put("latitude", loc.latitude)
            .put("longitude", loc.longitude)
            .put("altitudeMeters", loc.altitudeMeters ?: JSONObject.NULL)
            .put("accuracyMeters", loc.accuracyMeters?.toDouble() ?: JSONObject.NULL)
            .put("speedMetersPerSecond", loc.speedMetersPerSecond?.toDouble() ?: JSONObject.NULL)
            .put("bearingDegrees", loc.bearingDegrees?.toDouble() ?: JSONObject.NULL)
            .put("provider", loc.provider ?: JSONObject.NULL)
            .put("recordedAtUtc", loc.recordedAtUtc)
            .put("source", loc.source)
            .put("policyMode", loc.policyMode)
            .put("scheduleLowFrequency", loc.scheduleLowFrequency)
            .put("motionState", loc.motionState ?: JSONObject.NULL)
            .put("syncStatus", loc.syncStatus)
    }
}

private class BoundedInputStream(
    private val delegate: InputStream,
    private val limit: Long
) : InputStream() {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int {
        if (bytesRead >= limit) return -1
        val value = delegate.read()
        if (value >= 0) bytesRead++
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRead >= limit) return -1
        val allowed = minOf(length.toLong(), limit - bytesRead).toInt()
        val read = delegate.read(buffer, offset, allowed)
        if (read > 0) bytesRead += read
        return read
    }

    override fun close() = delegate.close()
}
