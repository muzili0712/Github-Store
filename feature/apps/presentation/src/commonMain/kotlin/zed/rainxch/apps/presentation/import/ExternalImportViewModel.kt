package zed.rainxch.apps.presentation.import

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import zed.rainxch.apps.presentation.import.model.CandidateUi
import zed.rainxch.apps.presentation.import.model.ImportPhase
import zed.rainxch.apps.presentation.import.model.RepoSuggestionUi
import zed.rainxch.apps.presentation.import.model.SuggestionSource
import zed.rainxch.core.domain.logging.GitHubStoreLogger
import zed.rainxch.core.domain.repository.ExternalImportRepository
import zed.rainxch.core.domain.system.ExternalAppCandidate
import zed.rainxch.core.domain.system.InstallerKind
import zed.rainxch.core.domain.system.RepoMatchResult
import zed.rainxch.core.domain.system.RepoMatchSource
import zed.rainxch.core.domain.system.RepoMatchSuggestion

class ExternalImportViewModel(
    private val externalImportRepository: ExternalImportRepository,
    private val logger: GitHubStoreLogger,
) : ViewModel() {
    private var hasStarted = false
    private var scanJob: Job? = null

    private val _state = MutableStateFlow(ExternalImportState())
    val state =
        _state
            .onStart {
                if (!hasStarted) {
                    hasStarted = true
                    startScanIfIdle()
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = ExternalImportState(),
            )

    private val _events = Channel<ExternalImportEvent>()
    val events = _events.receiveAsFlow()

    fun onAction(action: ExternalImportAction) {
        when (action) {
            ExternalImportAction.OnStart -> {
                if (_state.value.phase == ImportPhase.Idle) startScanIfIdle()
            }

            ExternalImportAction.OnRequestPermission -> {
                _state.update { it.copy(phase = ImportPhase.RequestingPermission) }
            }

            ExternalImportAction.OnPermissionGranted -> {
                _state.update { it.copy(isPermissionDenied = false) }
                startScanIfIdle(force = true)
            }

            ExternalImportAction.OnPermissionDenied -> {
                _state.update { it.copy(isPermissionDenied = true) }
                startScanIfIdle(force = true)
            }

            ExternalImportAction.OnSkipCurrentCard -> skipCurrent(neverAsk = false)

            ExternalImportAction.OnSkipForever -> skipCurrent(neverAsk = true)

            is ExternalImportAction.OnPickSuggestion -> pickSuggestion(action.suggestion)

            ExternalImportAction.OnExpandCurrentCard -> {
                _state.update { it.copy(currentExpanded = true) }
            }

            ExternalImportAction.OnCollapseCurrentCard -> {
                _state.update { it.copy(currentExpanded = false) }
            }

            is ExternalImportAction.OnSearchOverrideChanged -> {
                _state.update { it.copy(searchOverrideQuery = action.query) }
            }

            ExternalImportAction.OnSearchOverrideSubmit -> {
                // TODO Week 3: wire to BackendApiClient.search
                _state.update { it.copy(isSearching = true) }
                _state.update {
                    it.copy(
                        isSearching = false,
                        searchOverrideResults = persistentListOf(),
                    )
                }
            }

            ExternalImportAction.OnUndoLast -> Unit

            ExternalImportAction.OnExit -> {
                viewModelScope.launch {
                    _events.send(ExternalImportEvent.NavigateBack)
                }
            }

            ExternalImportAction.OnDismissCompletionToast -> {
                _state.update { it.copy(showCompletionToast = false) }
            }
        }
    }

    private fun startScanIfIdle(force: Boolean = false) {
        if (!force && _state.value.phase != ImportPhase.Idle) return
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            try {
                _state.update { it.copy(phase = ImportPhase.Scanning, errorMessage = null) }

                // runFullScan must precede pendingCandidatesFlow().first(): the in-memory candidate
                // snapshot is process-scoped and empty on cold start, so without a scan first the
                // wizard would render zero cards even with PENDING_REVIEW rows in the DAO.
                externalImportRepository.runFullScan()

                val candidates = externalImportRepository.pendingCandidatesFlow().first()

                _state.update {
                    it.copy(
                        phase = ImportPhase.AutoImporting,
                        totalCandidates = candidates.size,
                    )
                }

                val matches = externalImportRepository.resolveMatches(candidates)
                val summary = externalImportRepository.importAutoMatched(matches)

                val autoLinkedPackages =
                    matches
                        .filter { it.topConfidence >= AUTO_LINK_THRESHOLD }
                        .map { it.packageName }
                        .toSet()

                val reviewCandidates =
                    candidates.filter { it.packageName !in autoLinkedPackages }
                val reviewMatchesByPkg =
                    matches.associateBy { it.packageName }

                val cards =
                    reviewCandidates
                        .mapNotNull { candidate ->
                            val match = reviewMatchesByPkg[candidate.packageName]
                            buildCard(candidate, match)
                        }.toImmutableList()

                if (cards.isEmpty()) {
                    _state.update {
                        it.copy(
                            phase = ImportPhase.Done,
                            cards = persistentListOf(),
                            currentCardIndex = 0,
                            autoImported = summary.linked,
                            showCompletionToast = true,
                        )
                    }
                    _events.send(ExternalImportEvent.PlayConfetti)
                } else {
                    _state.update {
                        it.copy(
                            phase = ImportPhase.AwaitingReview,
                            cards = cards,
                            currentCardIndex = 0,
                            currentExpanded = false,
                            autoImported = summary.linked,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("External import scan failed: ${e.message}")
                _state.update {
                    it.copy(
                        phase = ImportPhase.Idle,
                        errorMessage = e.message,
                    )
                }
                _events.send(ExternalImportEvent.ShowError(e.message ?: "Scan failed"))
            }
        }
    }

    private fun buildCard(
        candidate: ExternalAppCandidate,
        match: RepoMatchResult?,
    ): CandidateUi? {
        val suggestionsDomain = match?.suggestions.orEmpty()
        val top = suggestionsDomain.maxByOrNull { it.confidence }
        val preselected =
            if (top != null && top.confidence in PRESELECT_MIN..PRESELECT_MAX) top.toUi() else null

        return CandidateUi(
            packageName = candidate.packageName,
            appLabel = candidate.appLabel,
            versionName = candidate.versionName,
            installerLabel = candidate.installerKind.toUiLabel(),
            suggestions = suggestionsDomain.take(3).map { it.toUi() }.toImmutableList(),
            preselectedSuggestion = preselected,
        )
    }

    private fun skipCurrent(neverAsk: Boolean) {
        val current = _state.value.currentCard ?: return
        viewModelScope.launch {
            try {
                externalImportRepository.skipPackage(current.packageName, neverAsk = neverAsk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Skip failed for ${current.packageName}: ${e.message}")
            }
            advanceAfter { it.copy(skipped = it.skipped + 1) }
        }
    }

    private fun pickSuggestion(suggestion: RepoSuggestionUi) {
        val current = _state.value.currentCard ?: return
        val preselected = current.preselectedSuggestion
        val source = if (suggestion == preselected) "preselected" else "alternative"

        viewModelScope.launch {
            val result =
                try {
                    externalImportRepository.linkManually(
                        packageName = current.packageName,
                        owner = suggestion.owner,
                        repo = suggestion.repo,
                        source = source,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }

            if (result.isFailure) {
                logger.error(
                    "Manual link failed for ${current.packageName}: " +
                        "${result.exceptionOrNull()?.message}",
                )
                _events.send(
                    ExternalImportEvent.ShowError(
                        result.exceptionOrNull()?.message ?: "Link failed",
                    ),
                )
                return@launch
            }
            advanceAfter { it.copy(manuallyLinked = it.manuallyLinked + 1) }
        }
    }

    private suspend fun advanceAfter(transform: (ExternalImportState) -> ExternalImportState) {
        val nextIndex = _state.value.currentCardIndex + 1
        val total = _state.value.cards.size
        val done = nextIndex >= total

        _state.update { current ->
            val tallied = transform(current).copy(currentExpanded = false)
            if (done) {
                tallied.copy(
                    phase = ImportPhase.Done,
                    showCompletionToast = true,
                )
            } else {
                tallied.copy(currentCardIndex = nextIndex)
            }
        }

        if (done) {
            _events.send(ExternalImportEvent.PlayConfetti)
        }
    }

    private fun RepoMatchSuggestion.toUi(): RepoSuggestionUi =
        RepoSuggestionUi(
            owner = owner,
            repo = repo,
            confidence = confidence,
            source =
                when (source) {
                    RepoMatchSource.MANIFEST -> SuggestionSource.MANIFEST
                    RepoMatchSource.SEARCH -> SuggestionSource.SEARCH
                    RepoMatchSource.FINGERPRINT -> SuggestionSource.FINGERPRINT
                    RepoMatchSource.MANUAL -> SuggestionSource.MANUAL
                },
            stars = stars,
            description = description,
        )

    private fun InstallerKind.toUiLabel(): String =
        when (this) {
            InstallerKind.STORE_OBTAINIUM -> "Obtainium"
            InstallerKind.STORE_FDROID -> "F-Droid"
            InstallerKind.BROWSER -> "Browser"
            InstallerKind.SIDELOAD -> "Sideload"
            InstallerKind.GITHUB_STORE_SELF -> "GitHub Store"
            InstallerKind.UNKNOWN -> "Unknown source"
            else -> "Unknown source"
        }

    companion object {
        private const val AUTO_LINK_THRESHOLD = 0.85
        private const val PRESELECT_MIN = 0.5
        private const val PRESELECT_MAX = 0.85
    }
}
