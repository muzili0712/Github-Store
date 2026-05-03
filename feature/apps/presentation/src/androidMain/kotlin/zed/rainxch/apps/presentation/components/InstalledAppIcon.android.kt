package zed.rainxch.apps.presentation.components

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import org.jetbrains.compose.resources.painterResource
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.app_icon

private const val TAG = "InstalledAppIcon"

@Composable
actual fun InstalledAppIcon(
    packageName: String,
    appName: String,
    modifier: Modifier,
    apkFilePath: String?,
) {
    val packageManager = LocalContext.current.packageManager
    val iconBitmap =
        remember(packageName, apkFilePath, packageManager) {
            resolveInstalledIcon(packageManager, packageName)
                ?: apkFilePath?.let { resolveApkIcon(packageManager, it) }
        }

    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap,
            contentDescription = appName,
            modifier = modifier,
        )
    } else {
        Image(
            painter = painterResource(Res.drawable.app_icon),
            contentDescription = appName,
            modifier = modifier,
        )
    }
}

private fun resolveInstalledIcon(
    packageManager: PackageManager,
    packageName: String,
): ImageBitmap? =
    try {
        packageManager
            .getApplicationIcon(packageName)
            .toBitmap()
            .asImageBitmap()
    } catch (t: Throwable) {
        // Wide catch: NameNotFoundException is the common case but
        // PackageManager can also throw SecurityException on cross-user
        // reads, plus toBitmap() can throw if the drawable can't be
        // rasterized (unsupported drawable type, OOM on huge icons).
        // Composition crash on the Apps screen is the worst-case
        // outcome — silently fall back to the default icon instead.
        Log.w(TAG, "failed to load installed icon for $packageName", t)
        null
    }

private fun resolveApkIcon(
    packageManager: PackageManager,
    apkFilePath: String,
): ImageBitmap? =
    try {
        // PackageManager.getApplicationIcon(applicationInfo) needs sourceDir
        // to point at the APK so loadIcon() resolves the embedded drawable.
        // Without setting sourceDir/publicSourceDir loadIcon() returns the
        // default Android boilerplate icon — useless as a fallback.
        val info = getPackageArchiveInfoCompat(packageManager, apkFilePath)
        val appInfo = info?.applicationInfo ?: return null
        appInfo.sourceDir = apkFilePath
        appInfo.publicSourceDir = apkFilePath
        appInfo
            .loadIcon(packageManager)
            ?.toBitmap()
            ?.asImageBitmap()
    } catch (t: Throwable) {
        Log.w(TAG, "failed to load icon from APK at $apkFilePath", t)
        null
    }

private fun getPackageArchiveInfoCompat(
    packageManager: PackageManager,
    apkFilePath: String,
) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageArchiveInfo(
            apkFilePath,
            PackageManager.PackageInfoFlags.of(0L),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageArchiveInfo(apkFilePath, 0)
    }
