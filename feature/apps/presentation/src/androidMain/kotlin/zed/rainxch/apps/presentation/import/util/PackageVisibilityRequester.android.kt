package zed.rainxch.apps.presentation.import.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// keep in sync with AndroidExternalAppScanner.GRANT_THRESHOLD
private const val GRANT_THRESHOLD = 30

@Composable
actual fun rememberPackageVisibilityRequester(): PackageVisibilityRequester {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidPackageVisibilityRequester(context) }
}

private class AndroidPackageVisibilityRequester(
    private val context: Context,
) : PackageVisibilityRequester {
    override val isGranted: Boolean
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
            val pm = context.packageManager
            val visible = runCatching { pm.getInstalledPackages(0) }.getOrElse { emptyList() }
            return visible.size >= GRANT_THRESHOLD
        }

    override suspend fun requestOrOpenSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        // QUERY_ALL_PACKAGES is a "special app access" permission as of API 30 — there
        // is no native runtime dialog. Best we can do is land the user on the App Info
        // page where the toggle lives. We can't observe grant from here; the caller
        // re-checks `isGranted` after the user returns.
        val intent =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        runCatching { context.startActivity(intent) }
        return false
    }
}
