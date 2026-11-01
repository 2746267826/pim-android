package com.pim.app.notes

import android.content.Context
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

data class NotesState(
    val loaded: Boolean = false,
    val entries: List<NoteEntry> = emptyList(),
    val documents: Map<String, NoteDocument> = emptyMap(),
    val savedRevisions: Map<String, Long> = emptyMap(),
    val editHistory: Map<String, NoteHistoryAvailability> = emptyMap(),
    val errors: Set<String> = emptySet(),
    val loadFailed: Boolean = false
)

data class NoteHistoryAvailability(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoDepth: Int = 0,
    val redoDepth: Int = 0,
)

@Singleton
class NoteRepository @Inject constructor(@ApplicationContext context: Context) {
    private val notesRoot = File(context.noBackupFilesDir, "notes")
    private val store = NoteFileStore(notesRoot)
    private val editHistory = NoteEditHistoryStore(File(notesRoot, ".edit-history"))
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
                histories.clear()
                histories.putAll(documents.mapValues { (_, document) ->
                    runCatching { editHistory.read(document.id) }.getOrNull()
                        ?.takeIf { it.current().string("id") == document.id }
                        ?.reconcile(document)
                        ?: NoteEditHistory.initial(document)
                })
                synchronized(this@NoteRepository) {
                    mutableState.value = NotesState(
                        loaded = true,
                        entries = entries,
                        documents = documents,
                        savedRevisions = documents.mapValues { it.value.revision },
                        editHistory = histories.mapValues { (_, history) -> history.availability() },
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
            val note = checkNotNull(state.value.documents[id])
            if (state.value.savedRevisions[id] != note.revision) store.save(note)
            histories[id]?.let(editHistory::save)
            synchronized(this@NoteRepository) {
                val current = state.value
                mutableState.value = current.copy(
                    savedRevisions = current.savedRevisions + (id to note.revision),
                    editHistory = histories[id]?.let { current.editHistory + (id to it.availability()) } ?: current.editHistory,
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
        val imported = open().use(store::importDocument)
        histories[imported.id] = NoteEditHistory.initial(imported)
        publish(imported, recordHistory = false)
        imported
    }

    @Synchronized fun undo(id: String): Boolean = moveHistory(id, forward = false)
    @Synchronized fun redo(id: String): Boolean = moveHistory(id, forward = true)

    private fun moveHistory(id: String, forward: Boolean): Boolean {
        val current = state.value.documents[id] ?: return false
        val history = histories[id] ?: NoteEditHistory.initial(current).also { histories[id] = it }
        val target = if (forward) history.cursor + 1 else history.cursor - 1
        if (target !in history.snapshots.indices) return false
        val nextHistory = history.movedTo(target)
        val next = current.withHistoryContent(nextHistory.current())
        histories[id] = nextHistory
        publish(next, recordHistory = false)
        return true
    }

    private fun publish(note: NoteDocument) {
        publish(note, recordHistory = true)
    }

    private fun publish(note: NoteDocument, recordHistory: Boolean) {
        val old = state.value
        if (recordHistory) {
            val previous = old.documents[note.id]
            if (previous != null) {
                val history = histories[note.id] ?: NoteEditHistory.initial(previous)
                val key = previous.historyEditKey(note)
                if (key.isNotEmpty()) histories[note.id] = history.record(note, key, System.currentTimeMillis())
            }
        }
        val entries = listOf(NoteEntry(note.id, note.title)) + old.entries.filterNot { it.id == note.id }
        mutableState.value = old.copy(
            entries = entries,
            documents = old.documents + (note.id to note),
            editHistory = histories[note.id]?.let { old.editHistory + (note.id to it.availability()) } ?: old.editHistory,
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
}
