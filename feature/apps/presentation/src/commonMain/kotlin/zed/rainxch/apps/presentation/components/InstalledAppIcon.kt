package zed.rainxch.apps.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Renders the icon for a tracked app.
 *
 * Resolution order on Android:
 *  1. The icon registered with the system PackageManager for [packageName].
 *  2. The icon embedded inside the parked APK at [apkFilePath] (used for
 *     pending-install rows whose package isn't on the system yet).
 *  3. A generic fallback drawable.
 *
 * On JVM (desktop) the fallback is always used — there's no
 * platform-resident icon registry to consult.
 */
@Composable
expect fun InstalledAppIcon(
    packageName: String,
    appName: String,
    modifier: Modifier = Modifier,
    apkFilePath: String? = null,
)
