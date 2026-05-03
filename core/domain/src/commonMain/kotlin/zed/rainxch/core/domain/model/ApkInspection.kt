package zed.rainxch.core.domain.model

/**
 * Snapshot of an APK's declared metadata. Produced by
 * [zed.rainxch.core.domain.system.ApkInspector] from either a file on
 * disk (parked install) or an installed package's manifest.
 *
 * Everything here is *as the APK declares it* — the inspector does not
 * resolve it against runtime grant state for permissions, that lives on
 * [ApkPermission.granted] which is `null` for file-based inspections
 * because there's no system-level grant before install.
 */
data class ApkInspection(
    /** App label as the APK declares it (e.g. "Signal"). */
    val appLabel: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long?,
    /** SHA-256 of the APK signer cert, hex-encoded with colons. */
    val signingFingerprint: String?,
    /** Lowest API the APK runs on. */
    val minSdk: Int?,
    /** API the developer compiled against. */
    val targetSdk: Int?,
    /** Permissions declared in the manifest, mapped with protection level. */
    val permissions: List<ApkPermission>,
    /** Fully-qualified main launcher activity, if any. */
    val mainActivity: String?,
    /** Total declared activity count (manifest entries). */
    val activityCount: Int,
    /** Total declared service count. */
    val serviceCount: Int,
    /** Total declared receiver count. */
    val receiverCount: Int,
    /** Bytes on disk (file-based) or APK file size on the device (installed). */
    val fileSizeBytes: Long?,
    /** Absolute path to the inspected APK file, if any. */
    val filePath: String?,
    /** Manifest's `android:debuggable` flag — sketchy if true on a release build. */
    val debuggable: Boolean,
    /** Source of the inspection. */
    val source: Source,
) {
    enum class Source {
        /** Read from a file on disk that's parked for install. */
        FILE,

        /** Read from an installed package's manifest. */
        INSTALLED,
    }
}

data class ApkPermission(
    /** Fully-qualified permission name (e.g. `android.permission.INTERNET`). */
    val name: String,
    /** Short, user-friendly label loaded from the system or derived from [name]. */
    val displayName: String,
    /** Description loaded from the system, when available. */
    val description: String?,
    val protectionLevel: ProtectionLevel,
    /**
     * Runtime grant state on the device. `true`/`false` for installed
     * dangerous permissions; `null` for file-based inspections (no
     * grant exists pre-install) and for normal-protection permissions
     * that are auto-granted at install.
     */
    val granted: Boolean?,
)

/**
 * Coarse bucketing of Android's permission protection levels — enough
 * to drive a "danger color" in the UI without exposing every flag bit.
 */
enum class ProtectionLevel {
    /** Auto-granted at install. Low risk. */
    NORMAL,

    /** Sensitive — requires runtime user grant. Show in red. */
    DANGEROUS,

    /** Granted only to apps signed with the same cert. Show in amber. */
    SIGNATURE,

    /** Privileged platform permissions. Show in deep amber. */
    PRIVILEGED,

    /** Couldn't classify. */
    UNKNOWN,
}
