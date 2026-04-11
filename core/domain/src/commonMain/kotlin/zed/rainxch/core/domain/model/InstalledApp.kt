package zed.rainxch.core.domain.model

data class InstalledApp(
    val packageName: String,
    val repoId: Long,
    val repoName: String,
    val repoOwner: String,
    val repoOwnerAvatarUrl: String,
    val repoDescription: String?,
    val primaryLanguage: String?,
    val repoUrl: String,
    val installedVersion: String,
    val installedAssetName: String?,
    val installedAssetUrl: String?,
    val latestVersion: String?,
    val latestAssetName: String?,
    val latestAssetUrl: String?,
    val latestAssetSize: Long?,
    val appName: String,
    val installSource: InstallSource,
    val installedAt: Long,
    val lastCheckedAt: Long,
    val lastUpdatedAt: Long,
    val isUpdateAvailable: Boolean,
    val signingFingerprint: String?,
    val updateCheckEnabled: Boolean = true,
    val releaseNotes: String? = "",
    val systemArchitecture: String,
    val fileExtension: String,
    val isPendingInstall: Boolean = false,
    val installedVersionName: String? = null,
    val installedVersionCode: Long = 0L,
    val latestVersionName: String? = null,
    val latestVersionCode: Long? = null,
    val latestReleasePublishedAt: String? = null,
    val includePreReleases: Boolean = false,
    /**
     * Optional regex applied to asset names. When set, only assets whose
     * names match the pattern are considered installable for this app —
     * the building block for tracking one app inside a monorepo that ships
     * multiple apps (e.g. `ente-auth.*` against `ente-io/ente`).
     */
    val assetFilterRegex: String? = null,
    /**
     * When true, the update check walks back through past releases looking
     * for one whose assets match [assetFilterRegex]. Required for monorepos
     * where the latest release belongs to a sibling app.
     */
    val fallbackToOlderReleases: Boolean = false,
    /**
     * Stable identifier for the asset variant (e.g. `arm64-v8a`,
     * `universal`) that the user has chosen to track. Derived from the
     * picked asset filename's tail (everything after the version) so it
     * survives version bumps. `null` means "auto-pick by architecture".
     */
    val preferredAssetVariant: String? = null,
    /**
     * Set when the update checker can't find an asset matching
     * [preferredAssetVariant] in a fresh release — typically because the
     * maintainer renamed or restructured the artefacts. The UI shows a
     * "variant changed" prompt; the flag is cleared once the user picks
     * a new variant.
     */
    val preferredVariantStale: Boolean = false,
)
