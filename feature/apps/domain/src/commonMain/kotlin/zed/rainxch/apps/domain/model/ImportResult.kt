package zed.rainxch.apps.domain.model

data class ImportResult(
    val imported: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val nonGitHubSkipped: Int = 0,
    val importedItems: List<String> = emptyList(),
    val skippedItems: List<String> = emptyList(),
    val nonGitHubItems: List<String> = emptyList(),
    val failedItems: List<String> = emptyList(),
    val sourceFormat: ImportFormat = ImportFormat.UNKNOWN,
    val unknownFormatPreview: String? = null,
)

enum class ImportFormat {
    NATIVE,
    OBTAINIUM,
    UNKNOWN,
}
