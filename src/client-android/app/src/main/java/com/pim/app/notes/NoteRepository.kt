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
    val errors: Set<String> = emptySet(),
    val loadFailed: Boolean = false
)

@Singleton
class NoteRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = NoteFileStore(File(context.noBackupFilesDir, "notes"))
    private val mutableState = MutableStateFlow(NotesState())
    val state = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<String>(Channel.UNLIMITED)
    private val initialization = Mutex()

    init {
        // Saving belongs to the application, not the lifetime of an editor Activity.
        scope.launch {
            for (id in pending) {
                val note = state.value.documents[id] ?: continue
                if (state.value.savedRevisions[id] == note.revision) continue
                try {
                    store.save(note)
                    synchronized(this@NoteRepository) {
                        val current = state.value
                        mutableState.value = current.copy(savedRevisions = current.savedRevisions + (id to note.revision), errors = current.errors - id)
                    }
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
                synchronized(this@NoteRepository) {
                    mutableState.value = NotesState(true, entries, documents, documents.mapValues { it.value.revision })
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

    private fun publish(note: NoteDocument) {
        val old = state.value
        val entries = listOf(NoteEntry(note.id, note.title)) + old.entries.filterNot { it.id == note.id }
        mutableState.value = old.copy(entries = entries, documents = old.documents + (note.id to note), errors = old.errors - note.id)
        pending.trySend(note.id)
    }
}
