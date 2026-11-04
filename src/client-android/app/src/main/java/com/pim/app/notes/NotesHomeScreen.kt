package com.pim.app.notes

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun NotesHomeScreen(modifier: Modifier = Modifier, model: NotesViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val notes by model.notes.state.collectAsStateWithLifecycle()
    val cloudNotes by model.cloudNotesState.collectAsStateWithLifecycle()
    val recordings by model.recordings.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val uploads by model.uploads.state.collectAsStateWithLifecycle()
    val autoSubmitAuthorization by model.uploads.autoSubmitAuthorization.collectAsStateWithLifecycle()
    val asr by model.asr.state.collectAsStateWithLifecycle()
    val artifacts by model.artifacts.state.collectAsStateWithLifecycle()
    val artifactEntry by model.artifactEntry.collectAsStateWithLifecycle()
    val artifactConsentDone by model.artifactConsentDone.collectAsStateWithLifecycle()
    val artifactStarting by model.artifactStarting.collectAsStateWithLifecycle()
    val artifactKind by model.artifactKind.collectAsStateWithLifecycle()
    val artifactPendingSelections by model.artifactPendingSelections.collectAsStateWithLifecycle()
    val artifactViews by model.artifactViews.collectAsStateWithLifecycle()
    val artifactContentViews by model.artifactContentViews.collectAsStateWithLifecycle()
    val taskSuggestions by model.taskSuggestions.state.collectAsStateWithLifecycle()
    val legacyMigration by model.legacyQuickNoteMigrationState.collectAsStateWithLifecycle()
    val legacyMigrationActions = remember(model, context) {
        legacyQuickNoteMigrationActions(model) { id ->
            context.startActivity(NativeNotesActivity.intent(context, id, true))
        }
    }
    val target = rememberUploadTarget(model.uploads)
    var showAsr by remember { mutableStateOf(false) }
    val asrActions = remember(model) {
        NoteAsrActions(
            refresh = model::refreshAsr,
            refreshTask = model::refreshAsrTask,
            loadSegments = model::loadAsrSegments,
            stop = model::stopAsr,
            retry = model::retryAsr,
            start = model::startAsr,
            replayStart = model::replayAsrStart,
            replayRetry = model::replayAsrRetry,
            openDerived = { versionId, noteId, asrTaskId ->
                model.openArtifacts(versionId, noteId, asrTaskId, "")
            },
        )
    }
    val message by model.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it, withDismissAction = true); model.dismissMessage(it) } }
    Surface(modifier) {
      Box(Modifier.fillMaxSize()) {
        NoteLibraryScreen(notes, null, recordings, busy,
            onCreate = { context.startActivity(NativeNotesActivity.intent(context, model.notes.create(), true)) },
            onOpen = { id -> if (id != null) context.startActivity(NativeNotesActivity.intent(context, id, true)) },
            onExit = null, uploads = uploads, currentTarget = target, autoSubmitAuthorization = autoSubmitAuthorization,
            onEnableAutoSubmit = model::enableAutoSubmit, onRevokeAutoSubmit = model::revokeAutoSubmit,
            onResumeUpload = model::resumeUpload, onPauseUpload = model::pauseUpload,
            asrTaskCount = asr.tasks.size, onAsr = { showAsr = true },
            onLegacyQuickNotes = legacyMigrationActions.open,
            cloudNotes = cloudNotes, onRefreshCloudNotes = model::refreshCloudNotes,
            onOpenCloudNote = { id -> model.openCloudNote(id) { restoredId ->
                context.startActivity(NativeNotesActivity.intent(context, restoredId, true))
            } })
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(com.pim.app.ui.theme.PimTheme.dimensions.spaceLarge)
            .padding(bottom = com.pim.app.ui.theme.PimTheme.dimensions.noteDockClearance))
        if (showAsr) NoteAsrSurface(
            state = asr,
            currentTarget = target,
            initialVersionId = null,
            onDismiss = { showAsr = false },
            actions = asrActions,
        )
        artifactEntry?.let { entry ->
            NoteArtifactOverlay(
                state = artifacts,
                entry = entry,
                consentDone = artifactConsentDone,
                starting = artifactStarting,
                selectedKind = artifactKind,
                pendingSelections = artifactPendingSelections,
                viewedCandidates = artifactViews,
                contentViews = artifactContentViews,
                suggestionsState = taskSuggestions,
                entryActions = noteArtifactEntryActions(model),
                screenActions = noteArtifactScreenActions(model),
                suggestionActions = noteTaskSuggestionsActions(model),
            )
        }
        if (legacyMigration.visible) LegacyQuickNoteMigrationScreen(
            state = legacyMigration,
            onRefresh = legacyMigrationActions.refresh,
            onPage = legacyMigrationActions.page,
            onSelect = legacyMigrationActions.select,
            onConfirm = legacyMigrationActions.confirm,
            onRevoke = legacyMigrationActions.revoke,
            onDismissPreview = legacyMigrationActions.dismissPreview,
            onOpenNote = legacyMigrationActions.openNote,
            onClose = legacyMigrationActions.close,
        )
      }
    }
}

@Composable
internal fun rememberUploadTarget(repository: NoteUploadRepository): NoteUploadTarget? {
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var target by remember(repository) { mutableStateOf(repository.target()) }
    DisposableEffect(lifecycle, repository) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                target = repository.target()
                scope.launch { repository.resumeAutoSubmits() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return target
}
