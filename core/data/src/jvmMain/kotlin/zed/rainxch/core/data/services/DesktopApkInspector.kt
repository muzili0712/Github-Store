package zed.rainxch.core.data.services

import zed.rainxch.core.domain.model.ApkInspection
import zed.rainxch.core.domain.system.ApkInspector

/**
 * Desktop has no concept of an APK manifest. The inspector is wired in
 * for symmetry with Android — every call returns `null` so the UI can
 * gate on `inspect(...) != null` without platform branching.
 */
class DesktopApkInspector : ApkInspector {
    override suspend fun inspectFile(filePath: String): ApkInspection? = null

    override suspend fun inspectInstalled(packageName: String): ApkInspection? = null
}
