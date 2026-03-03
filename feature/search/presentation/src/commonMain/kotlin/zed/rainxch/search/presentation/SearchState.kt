package zed.rainxch.search.presentation

import zed.rainxch.core.presentation.model.DiscoveryRepository
import zed.rainxch.domain.model.ProgrammingLanguage
import zed.rainxch.domain.model.SearchPlatform
import zed.rainxch.domain.model.SortBy
import zed.rainxch.domain.model.SortOrder
import zed.rainxch.search.presentation.utils.ParsedGithubLink

data class SearchState(
    val query: String = "",
    val repositories: List<DiscoveryRepository> = emptyList(),
    val selectedSearchPlatform: SearchPlatform = SearchPlatform.All,
    val selectedSortBy: SortBy = SortBy.BestMatch,
    val selectedSortOrder: SortOrder = SortOrder.Descending,
    val selectedLanguage: ProgrammingLanguage = ProgrammingLanguage.All,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val hasMorePages: Boolean = true,
    val totalCount: Int? = null,
    val isLanguageSheetVisible: Boolean = false,
    val isSortByDialogVisible: Boolean = false,
    val detectedLinks: List<ParsedGithubLink> = emptyList(),
    val clipboardLinks: List<ParsedGithubLink> = emptyList(),
    val isClipboardBannerVisible: Boolean = false,
    val autoDetectClipboardEnabled: Boolean = true,
)
