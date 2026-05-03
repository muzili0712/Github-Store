package zed.rainxch.details.domain.model

import zed.rainxch.core.domain.model.ApkPackageInfo
import zed.rainxch.core.domain.model.GithubRepoSummary

data class SaveInstalledAppParams(
    val repo: GithubRepoSummary,
    val apkInfo: ApkPackageInfo,
    val assetName: String,
    val assetUrl: String,
    val assetSize: Long,
    val releaseTag: String,
    val isPendingInstall: Boolean,
    val isFavourite: Boolean,
    val siblingAssetCount: Int,
    val pickedAssetIndex: Int?,
    // Path to the parked APK on disk when this row is being saved as a
    // pending install (e.g. system installer was launched but the user
    // hasn't accepted yet). The apps row uses this to drive its one-tap
    // Install retry button and to extract a real app icon while the
    // package isn't yet on the system. `null` for genuine completed
    // installs and for non-pending saves.
    val pendingInstallFilePath: String? = null,
)
