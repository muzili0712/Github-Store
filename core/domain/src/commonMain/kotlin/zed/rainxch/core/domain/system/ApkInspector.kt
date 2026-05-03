package zed.rainxch.core.domain.system

import zed.rainxch.core.domain.model.ApkInspection

/**
 * Reads metadata out of an APK file or an installed package and returns
 * the result as an [ApkInspection]. Used by the "Inspect APK" sheet to
 * show users what a package declares before they install it (and to
 * audit installed packages after the fact).
 *
 * Implementations live per-platform; on Android the Inspector reads
 * from `PackageManager`, on JVM/desktop it returns `null` (no APK
 * concept on those targets).
 */
interface ApkInspector {
    /**
     * Inspects an APK file at [filePath]. Returns `null` if the file
     * doesn't exist, isn't a valid APK, or the platform can't extract
     * metadata.
     */
    suspend fun inspectFile(filePath: String): ApkInspection?

    /**
     * Inspects an installed package by [packageName]. Returns `null`
     * if the package isn't on the system or metadata is unavailable.
     */
    suspend fun inspectInstalled(packageName: String): ApkInspection?
}
