package com.pim.app.notes

import androidx.compose.runtime.Composable

@Composable
fun NoteArtifactOverlay(
    state: NoteArtifactRepositoryState,
    entry: NoteArtifactEntry,
    consentDone: Boolean,
    starting: Boolean,
    selectedKind: NoteArtifactKind,
    selections: Map<NoteArtifactKind, String?>,
    viewedCandidates: Map<NoteArtifactKind, String?>,
    contentViews: Map<NoteArtifactKind, NoteArtifactContentView>,
    entryActions: NoteArtifactEntryActions,
    screenActions: NoteArtifactScreenActions,
) {
    if (!consentDone) {
        NoteArtifactEntrySheet(
            state = state,
            entry = entry,
            starting = starting,
            actions = entryActions,
        )
    } else {
        NoteArtifactScreen(
            state = state.toUiState(
                entry = entry,
                selectedKind = selectedKind,
                selectedCandidateIds = selections,
                viewedCandidateIds = viewedCandidates,
                contentViews = contentViews,
            ),
            actions = screenActions,
        )
    }
}

fun noteArtifactScreenActions(model: NotesViewModel) = NoteArtifactScreenActions(
    dismiss = model::closeArtifacts,
    selectKind = model::selectArtifactKind,
    refresh = { model.refreshArtifacts() },
    changeContentView = model::changeArtifactContentView,
    viewCandidateSource = model::viewArtifactCandidate,
    selectCandidate = model::selectArtifactCandidate,
    changeDraftText = model::stageArtifactText,
    saveLocalDraft = model::saveArtifactLocalDraft,
    saveServerRevision = model::saveArtifactServerRevision,
    regenerate = model::regenerateArtifact,
    stop = model::stopArtifact,
    recover = model::recoverArtifact,
)

fun noteArtifactEntryActions(model: NotesViewModel) = NoteArtifactEntryActions(
    dismiss = model::closeArtifacts,
    refresh = model::refreshArtifacts,
    confirm = model::confirmArtifactStart,
)
