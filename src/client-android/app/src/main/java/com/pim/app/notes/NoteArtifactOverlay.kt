package com.pim.app.notes

import androidx.compose.runtime.Composable

@Composable
fun NoteArtifactOverlay(
    state: NoteArtifactRepositoryState,
    entry: NoteArtifactEntry,
    consentDone: Boolean,
    starting: Boolean,
    selectedKind: NoteArtifactKind,
    pendingSelections: Map<NoteArtifactKind, String?>,
    viewedCandidates: Map<NoteArtifactKind, String?>,
    contentViews: Map<NoteArtifactKind, NoteArtifactContentView>,
    suggestionsState: NoteTaskSuggestionsState,
    entryActions: NoteArtifactEntryActions,
    screenActions: NoteArtifactScreenActions,
    suggestionActions: NoteTaskSuggestionsActions,
) {
    if (suggestionsState.source != null) {
        NoteTaskSuggestionsScreen(state = suggestionsState, actions = suggestionActions)
    } else if (!consentDone) {
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
                pendingSelectionCandidateIds = pendingSelections,
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
    openTaskSuggestions = model::openTaskSuggestions,
    selectCandidate = model::selectArtifactCandidate,
    changeDraftText = model::stageArtifactText,
    saveLocalDraft = model::saveArtifactLocalDraft,
    saveServerRevision = model::saveArtifactServerRevision,
    regenerate = model::regenerateArtifact,
    stop = model::stopArtifact,
    recover = model::recoverArtifact,
    retryFailedSteps = model::retryArtifactSteps,
)

fun noteTaskSuggestionsActions(model: NotesViewModel) = NoteTaskSuggestionsActions(
    dismiss = model::closeTaskSuggestions,
    refresh = model::refreshTaskSuggestions,
    revalidate = model::revalidateTaskSuggestionSession,
    extract = { scope, destination, acknowledged -> model.extractTaskSuggestions(scope, destination, acknowledged) },
    edit = { scope, id, title, description, priority, due -> model.editTaskSuggestion(scope, id, title, description, priority, due) },
    save = { scope, id -> model.saveTaskSuggestion(scope, id) },
    discard = { scope, id -> model.discardTaskSuggestionDraft(scope, id) },
    rebase = { scope, id -> model.rebaseTaskSuggestionDraft(scope, id) },
    confirm = { scope, id -> model.confirmTaskSuggestion(scope, id) },
    dismissSuggestion = { scope, id -> model.dismissTaskSuggestion(scope, id) },
)

fun noteArtifactEntryActions(model: NotesViewModel) = NoteArtifactEntryActions(
    dismiss = model::closeArtifacts,
    refresh = model::refreshArtifacts,
    confirm = model::confirmArtifactStart,
)
