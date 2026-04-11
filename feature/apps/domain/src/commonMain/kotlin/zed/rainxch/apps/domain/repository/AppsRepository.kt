package zed.rainxch.apps.domain.repository

import kotlinx.coroutines.flow.Flow
import zed.rainxch.apps.domain.model.GithubRepoInfo
import zed.rainxch.apps.domain.model.ImportResult
import zed.rainxch.core.domain.model.DeviceApp
import zed.rainxch.core.domain.model.GithubRelease
import zed.rainxch.core.domain.model.InstalledApp

interface AppsRepository {
    suspend fun getApps(): Flow<List<InstalledApp>>

    suspend fun openApp(
        installedApp: InstalledApp,
        onCantLaunchApp: () -> Unit = { },
    )

    suspend fun getLatestRelease(
        owner: String,
        repo: String,
        includePreReleases: Boolean = false,
    ): GithubRelease?

    suspend fun getDeviceApps(): List<DeviceApp>

    suspend fun getTrackedPackageNames(): Set<String>

    suspend fun fetchRepoInfo(owner: String, repo: String): GithubRepoInfo?

    suspend fun linkAppToRepo(
        deviceApp: DeviceApp,
        repoInfo: GithubRepoInfo,
        assetFilterRegex: String? = null,
        fallbackToOlderReleases: Boolean = false,
        /**
         * Filename of the asset the user picked in the link sheet (or null
         * if no picker was shown — e.g. the repo had no installable assets
         * and the link is purely for tracking). When set, the implementation
         * derives a stable variant tag from it via `AssetVariant` and
         * persists it as `preferredAssetVariant`, so subsequent updates
         * stay on the same variant.
         */
        pickedAssetName: String? = null,
        /**
         * How many installable assets were offered to the user when they
         * picked. Single-asset releases (count == 1) skip variant memory
         * entirely because there's nothing to pin.
         */
        pickedAssetSiblingCount: Int = 0,
        /**
         * Direct variant tag (already extracted) — takes precedence over
         * the [pickedAssetName] derivation. Used by the import path
         * where we already have the tag from a previous export rather
         * than a fresh asset filename to extract from.
         */
        preferredAssetVariant: String? = null,
    )

    suspend fun exportApps(): String

    suspend fun importApps(json: String): ImportResult
}
