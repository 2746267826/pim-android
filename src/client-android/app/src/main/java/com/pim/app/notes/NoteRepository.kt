package com.pim.app.notes

import android.content.Context
import com.pim.app.nativework.quicknotes.isLegacyQuickNoteHtml
import com.pim.core.contract.QuickNoteDetailDto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

data class NotesState(
    val loaded: Boolean = false,
    val entries: List<NoteEntry> = emptyList(),
    val documents: Map<String, NoteDocument> = emptyMap(),
    val savedRevisions: Map<String, Long> = emptyMap(),
    val editHistory: Map<String, NoteHistoryAvailability> = emptyMap(),
    val historyUnavailable: Map<String, NoteHistoryUnavailableReason> = emptyMap(),
    val importQuarantine: NoteImportQuarantineSummary = NoteImportQuarantineSummary(),
    val errors: Set<String> = emptySet(),
    val loadFailed: Boolean = false
)

data class NoteHistoryAvailability(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoDepth: Int = 0,
    val redoDepth: Int = 0,
)

enum class NoteHistoryUnavailableReason {
    SnapshotTooLarge,
    DamagedJournal,
}

@Singleton
class NoteRepository @Inject constructor(@ApplicationContext context: Context) {
    private val notesRoot = File(context.noBackupFilesDir, "notes")
    private val store = NoteFileStore(notesRoot)
    private val editHistory = NoteEditHistoryStore(File(notesRoot, ".edit-history"))
    private val migrationLinks = LegacyQuickNoteMigrationStore(File(context.noBackupFilesDir, "legacy-quicknote-migrations"))
    private val histories = mutableMapOf<String, NoteEditHistory>()
    private val mutableState = MutableStateFlow(NotesState())
    val state = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<String>(Channel.UNLIMITED)
    private val initialization = Mutex()
    private val writes = Mutex()

    init {
        // Saving belongs to the application, not the lifetime of an editor Activity.
        scope.launch {
            for (id in pending) {
                try {
                    checkpoint(id)
                } catch (_: Exception) {
                    synchronized(this@NoteRepository) { mutableState.value = state.value.copy(errors = state.value.errors + id) }
                }
            }
        }
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        initialization.withLock {
            if (state.value.loaded) return@withLock
            try {
                val entries = store.entries()
                val documents = entries.filterNot { it.damaged }.associate { it.id to store.read(it.id) }
                runCatching { store.recoverAbandonedImports() }
                val importQuarantine = runCatching { store.importQuarantineSummary() }
                    .getOrDefault(NoteImportQuarantineSummary())
                val restoredHistories = mutableMapOf<String, NoteEditHistory>()
                val unavailableHistories = mutableMapOf<String, NoteHistoryUnavailableReason>()
                documents.forEach { (id, document) ->
                    if (!NoteEditHistory.canStore(document)) {
                        unavailableHistories[id] = NoteHistoryUnavailableReason.SnapshotTooLarge
                    } else if (!editHistory.exists(id)) {
                        restoredHistories[id] = NoteEditHistory.initial(document)
                    } else {
                        val result = runCatching { editHistory.read(id)?.reconcile(document) }
                        val restored = result.getOrNull()
                        if (restored == null || restored.noteId != id) {
                            unavailableHistories[id] = NoteHistoryUnavailableReason.DamagedJournal
                        } else {
                            restoredHistories[id] = restored
                        }
                    }
                }
                synchronized(this@NoteRepository) {
                    histories.clear()
                    histories.putAll(restoredHistories)
                    mutableState.value = NotesState(
                        loaded = true,
                        entries = entries,
                        documents = documents,
                        savedRevisions = documents.mapValues { it.value.revision },
                        editHistory = documents.mapValues { (id, _) ->
                            histories[id]?.availability() ?: NoteHistoryAvailability()
                        },
                        historyUnavailable = unavailableHistories,
                        importQuarantine = importQuarantine,
                    )
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.value = state.value.copy(loadFailed = true) }
        }
    }

    @Synchronized fun create(): String {
        check(state.value.loaded)
        val note = NoteDocument.create()
        publish(note)
        return note.id
    }

    @Synchronized fun update(id: String, transform: (NoteDocument) -> NoteDocument) {
        val old = checkNotNull(state.value.documents[id]) { "Document is not loaded; refusing an untracked edit" }
        val transformed = transform(old)
        if (transformed.source == old.source) return
        val next = transformed.nextRevision()
        next.validate()
        publish(next)
    }

    fun retry(id: String) { pending.trySend(id) }
    fun photoFile(noteId: String, assetId: String) = store.photoFile(noteId, assetId)
    fun saveInsertion(anchor: NoteInsertion) = store.saveInsertion(anchor)
    fun readInsertion(token: String) = store.readInsertion(token)
    fun removeInsertion(token: String) = store.removeInsertion(token)

    suspend fun checkpoint(id: String): NoteDocument = withContext(Dispatchers.IO) {
        writes.withLock {
            // Capture the note and its journal under the same repository lock. A history
            // snapshot can then never be paired with a different note revision.
            val (note, history) = synchronized(this@NoteRepository) {
                val note = checkNotNull(state.value.documents[id])
                val history = histories[id]?.takeIf {
                    it.matches(note) && NoteEditHistory.canStore(note)
                }
                note to history
            }
            if (state.value.savedRevisions[id] != note.revision) store.save(note)
            val preserveDamagedJournal = state.value.historyUnavailable[id] ==
                NoteHistoryUnavailableReason.DamagedJournal
            if (history != null) editHistory.save(history)
            else if (!preserveDamagedJournal) editHistory.delete(id)
            synchronized(this@NoteRepository) {
                val current = state.value
                val currentNote = current.documents[id]
                val stillCurrent = currentNote?.revision == note.revision
                val currentHistory = histories[id]?.takeIf {
                    currentNote?.let(it::matches) == true
                }?.availability()
                mutableState.value = current.copy(
                    savedRevisions = if (stillCurrent) {
                        current.savedRevisions + (id to note.revision)
                    } else {
                        current.savedRevisions
                    },
                    editHistory = if (currentNote != null) {
                        current.editHistory + (id to (currentHistory ?: NoteHistoryAvailability()))
                    } else {
                        current.editHistory - id
                    },
                    errors = current.errors - id,
                )
            }
            note
        }
    }

    suspend fun insertPhoto(id: String, anchor: NoteInsertion, open: () -> InputStream) = withContext(Dispatchers.IO) {
        // A camera result can arrive immediately after process recreation, before the initial load.
        initialize()
        check(state.value.loaded && state.value.documents.containsKey(id)) { "Target document is unavailable" }
        val block = open().use { store.importPhoto(id, it) }
        update(id) { it.insert(anchor, block) }
    }

    suspend fun exportDocument(note: NoteDocument, open: () -> OutputStream) = withContext(Dispatchers.IO) {
        open().use { store.exportDocument(note, it) }
    }

    suspend fun importDocument(open: () -> InputStream): NoteDocument = withContext(Dispatchers.IO) {
        initialize()
        try {
            val imported = open().use(store::importDocument)
            writes.withLock {
                synchronized(this@NoteRepository) {
                    NoteEditHistory.initialOrNull(imported)?.let { histories[imported.id] = it }
                        ?: histories.remove(imported.id)
                    publish(imported, recordHistory = false)
                }
                imported
            }
        } finally {
            val importQuarantine = store.importQuarantineSummary()
            synchronized(this@NoteRepository) {
                mutableState.value = state.value.copy(importQuarantine = importQuarantine)
            }
        }
    }

    suspend fun importLegacyQuickNote(
        identity: LegacyQuickNoteMigrationIdentity,
        detail: QuickNoteDetailDto,
        mappings: LegacyQuickNoteMigrationLinks = migrationLinks,
        checkActive: () -> Unit = {},
    ): NoteDocument = withContext(Dispatchers.IO) {
        initialize()
        require(detail.id == identity.legacyNoteId) { "Legacy quick-note identity changed" }
        validateMigrationSize(detail)
        writes.withLock {
            val link = mappings.find(identity)
            if (link != null) {
                require(link.noteId == identity.noteId)
                val existing = state.value.documents[link.noteId] ?: store.read(link.noteId)
                require(existing.hasLegacySource(identity)) { "Mapped native note is unavailable; source record was not changed" }
                return@withLock existing
            }

            val importedAt = System.currentTimeMillis()
            val candidate = createLegacyQuickNoteDocument(identity, detail, importedAt)
            val existing = state.value.documents[identity.noteId] ?: store.readForCreate(identity.noteId)
            val document = if (existing == null) candidate else {
                require(existing.hasLegacySource(identity, detail)) {
                    "A local note already occupies the stable migration identity; no source record was changed"
                }
                existing
            }

            // The note is written first. If the link write fails, a retry recognizes the same
            // deterministic note and completes the mapping without changing the remote source.
            checkActive()
            if (existing == null) store.save(document)
            checkActive()
            mappings.put(
                LegacyQuickNoteMigrationLink(
                    serverIdentity = identity.binding.serverIdentity,
                    ownerId = identity.binding.ownerId,
                    legacyNoteId = identity.legacyNoteId,
                    noteId = identity.noteId,
                    createdAtMillis = importedAt,
                )
            )
            synchronized(this@NoteRepository) {
                if (state.value.documents[document.id] == null) {
                    NoteEditHistory.initialOrNull(document)?.let { histories[document.id] = it }
                    publish(document, recordHistory = false)
                }
            }
            document
        }
    }

    @Synchronized fun undo(id: String): Boolean = moveHistory(id, forward = false)
    @Synchronized fun redo(id: String): Boolean = moveHistory(id, forward = true)

    private fun moveHistory(id: String, forward: Boolean): Boolean {
        val current = state.value.documents[id] ?: return false
        val history = histories[id]?.takeIf { it.matches(current) }
            ?: NoteEditHistory.initialOrNull(current)?.also { histories[id] = it }
            ?: return false
        val target = if (forward) history.cursor + 1 else history.cursor - 1
        if (target !in history.snapshots.indices) return false
        val next = current.withHistoryContent(history.movedTo(target).current())
        val nextHistory = history.movedTo(target, next.revision)
        histories[id] = nextHistory
        publish(next, recordHistory = false)
        return true
    }

    private fun publish(note: NoteDocument) {
        publish(note, recordHistory = true)
    }

    @Synchronized
    private fun publish(note: NoteDocument, recordHistory: Boolean) {
        val old = state.value
        if (recordHistory) {
            val previous = old.documents[note.id]
            if (previous != null) {
                if (!NoteEditHistory.canStore(note)) {
                    histories.remove(note.id)
                } else if (!NoteEditHistory.canStore(previous)) {
                    // A note that comes back below the budget starts a fresh history at this
                    // revision; recreating the oversized previous snapshot would defeat the cap.
                    histories[note.id] = NoteEditHistory.initial(note)
                } else {
                    val history = histories[note.id]?.takeIf { it.matches(previous) }
                        ?: NoteEditHistory.initial(previous)
                    val key = previous.historyEditKey(note)
                    if (key.isNotEmpty()) histories[note.id] = history.record(note, key, System.currentTimeMillis())
                }
            } else {
                NoteEditHistory.initialOrNull(note)?.let { histories[note.id] = it }
            }
        }
        val entries = listOf(NoteEntry(note.id, note.title)) + old.entries.filterNot { it.id == note.id }
        val availability = histories[note.id]?.takeIf { it.matches(note) }?.availability()
        val unavailable = when {
            availability != null -> old.historyUnavailable - note.id
            !NoteEditHistory.canStore(note) -> old.historyUnavailable +
                (note.id to NoteHistoryUnavailableReason.SnapshotTooLarge)
            else -> old.historyUnavailable
        }
        mutableState.value = old.copy(
            entries = entries,
            documents = old.documents + (note.id to note),
            editHistory = old.editHistory + (note.id to (availability ?: NoteHistoryAvailability())),
            historyUnavailable = unavailable,
            errors = old.errors - note.id,
        )
        pending.trySend(note.id)
    }

    private fun NoteEditHistory.availability() = NoteHistoryAvailability(
        canUndo = canUndo,
        canRedo = canRedo,
        undoDepth = cursor,
        redoDepth = snapshots.lastIndex - cursor,
    )

    private fun createLegacyQuickNoteDocument(
        identity: LegacyQuickNoteMigrationIdentity,
        detail: QuickNoteDetailDto,
        importedAtMillis: Long,
    ): NoteDocument {
        val editableCopy = if (detail.isLegacyQuickNoteHtml()) "" else detail.contentMarkdown
        val blank = NoteDocument.create()
        val document = blank.copy(source = JsonObject(blank.source + mapOf(
            "id" to JsonPrimitive(identity.noteId),
            "title" to JsonPrimitive("旧快速记录 · ${detail.id.take(8)}"),
            "createdAtMillis" to JsonPrimitive(importedAtMillis),
            "blocks" to JsonArray(listOf(
                LegacyQuickNoteSource(identity, detail, importedAtMillis).block(),
                markdownBlock(editableCopy),
            )),
        )))
        require(document.source.toString().toByteArray(Charsets.UTF_8).size <= NoteEditHistory.MAX_SNAPSHOT_BYTES) {
            "旧记录过大，无法安全创建有界本机副本；旧记录保留且未更改。"
        }
        return document.also(NoteDocument::validate)
    }

    private fun NoteDocument.hasLegacySource(
        identity: LegacyQuickNoteMigrationIdentity,
        detail: QuickNoteDetailDto? = null,
    ): Boolean {
        val block = blocks.firstOrNull { it["kind"]?.jsonPrimitive?.content == "legacyQuickNote" } ?: return false
        val importedAt = block["importedAtMillis"]?.jsonPrimitive?.long ?: return false
        val fieldsMatch = block["id"]?.jsonPrimitive?.content == UUIDFromDigest("${identity.stableKey}:source-block") &&
            block["legacyNoteId"]?.jsonPrimitive?.content == identity.legacyNoteId &&
            block["ownerId"]?.jsonPrimitive?.content == identity.binding.ownerId &&
            block["serverIdentity"]?.jsonPrimitive?.content == identity.binding.serverIdentity
        if (!fieldsMatch) return false
        return detail == null || block == LegacyQuickNoteSource(
                identity,
                detail,
                importedAt,
            ).block()
    }
}
