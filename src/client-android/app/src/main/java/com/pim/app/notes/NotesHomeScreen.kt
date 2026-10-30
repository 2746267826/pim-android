package com.pim.app.notes

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun NotesHomeScreen(modifier: Modifier = Modifier, model: NotesViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val notes by model.notes.state.collectAsStateWithLifecycle()
    val recordings by model.recordings.state.collectAsStateWithLifecycle()
    val uploads by model.uploads.state.collectAsStateWithLifecycle()
    val asr by model.asr.state.collectAsStateWithLifecycle()
    val artifacts by model.artifacts.state.collectAsStateWithLifecycle()
    val artifactEntry by model.artifactEntry.collectAsStateWithLifecycle()
    val artifactConsentDone by model.artifactConsentDone.collectAsStateWithLifecycle()
    val artifactStarting by model.artifactStarting.collectAsStateWithLifecycle()
    val artifactKind by model.artifactKind.collectAsStateWithLifecycle()
    val artifactSelections by model.artifactSelections.collectAsStateWithLifecycle()
    val artifactViews by model.artifactViews.collectAsStateWithLifecycle()
    val artifactContentViews by model.artifactContentViews.collectAsStateWithLifecycle()
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
        NoteLibraryScreen(notes, null, recordings, false,
            onCreate = { context.startActivity(NativeNotesActivity.intent(context, model.notes.create(), true)) },
            onOpen = { id -> if (id != null) context.startActivity(NativeNotesActivity.intent(context, id, true)) },
            onExit = null, uploads = uploads, currentTarget = target,
            onResumeUpload = model::resumeUpload, onPauseUpload = model::pauseUpload,
            asrTaskCount = asr.tasks.size, onAsr = { showAsr = true })
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
                selections = artifactSelections,
                viewedCandidates = artifactViews,
                contentViews = artifactContentViews,
                entryActions = noteArtifactEntryActions(model),
                screenActions = noteArtifactScreenActions(model),
            )
        }
      }
    }
}

@Composable
internal fun rememberUploadTarget(repository: NoteUploadRepository): NoteUploadTarget? {
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    var target by remember(repository) { mutableStateOf(repository.target()) }
    DisposableEffect(lifecycle, repository) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) target = repository.target()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return target
}
