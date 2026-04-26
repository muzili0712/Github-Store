package zed.rainxch.apps.presentation.import

import zed.rainxch.apps.presentation.import.model.RepoSuggestionUi

sealed interface ExternalImportAction {
    data object OnStart : ExternalImportAction

    data object OnRequestPermission : ExternalImportAction

    data class OnPermissionGranted(val sdkInt: Int?) : ExternalImportAction

    data class OnPermissionDenied(val sdkInt: Int?) : ExternalImportAction

    data object OnSkipCurrentCard : ExternalImportAction

    data object OnSkipForever : ExternalImportAction

    data object OnSkipRemaining : ExternalImportAction

    data class OnPickSuggestion(val suggestion: RepoSuggestionUi) : ExternalImportAction

    data object OnExpandCurrentCard : ExternalImportAction

    data object OnCollapseCurrentCard : ExternalImportAction

    data class OnSearchOverrideChanged(val query: String) : ExternalImportAction

    data object OnSearchOverrideSubmit : ExternalImportAction

    data object OnUndoLast : ExternalImportAction

    data object OnExit : ExternalImportAction

    data object OnDismissCompletionToast : ExternalImportAction
}
