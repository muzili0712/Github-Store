package zed.rainxch.core.domain.util

/**
 * Compiled, validated wrapper around a per-app asset name regex.
 *
 * Use [AssetFilter.parse] when reading a (possibly user-supplied) pattern out
 * of storage or a form field — it returns `null` for blank input and a
 * [Result.failure] for an invalid regex, so the caller can decide whether to
 * surface a validation error.
 *
 * Once compiled, [matches] is allocation-free for the hot path used by
 * `checkForUpdates` (compile once per app, evaluate against many asset names).
 *
 * Matching uses [Regex.containsMatchIn], not [Regex.matches]. That makes
 * casual patterns like `ente-auth` or `arm64` "just work" without forcing the
 * user to wrap the value in `.*` — it matches Obtainium's behaviour.
 */
class AssetFilter private constructor(
    val pattern: String,
    private val regex: Regex,
) {
    fun matches(assetName: String): Boolean = regex.containsMatchIn(assetName)

    override fun equals(other: Any?): Boolean = other is AssetFilter && other.pattern == pattern

    override fun hashCode(): Int = pattern.hashCode()

    override fun toString(): String = "AssetFilter($pattern)"

    companion object {
        /**
         * Parses a raw user-supplied pattern.
         *
         * @return `null` if [raw] is null/blank, otherwise a [Result] wrapping
         *   either the compiled filter or the [PatternSyntaxException]-equivalent
         *   exception thrown by Kotlin's regex compiler.
         */
        fun parse(raw: String?): Result<AssetFilter>? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            return runCatching {
                AssetFilter(pattern = trimmed, regex = Regex(trimmed, RegexOption.IGNORE_CASE))
            }
        }

        /**
         * Suggests a sensible filter from a sample asset name. Strips the
         * version suffix (anything from the first `-<digit>` onward) and
         * returns the leading prefix as a literal-prefix anchor.
         *
         * Examples:
         *   ente-auth-3.2.5-arm64-v8a.apk  →  ente-auth-
         *   Photos-1.7.0-universal.apk     →  Photos-
         *   app_2024-01-15.apk             →  app_
         *   no-version.apk                 →  null   (cannot derive a useful prefix)
         *
         * Returns `null` when the asset name has no clear version anchor —
         * blindly returning the full filename would create a filter that
         * matches only that exact build.
         */
        fun suggestFromAssetName(assetName: String): String? {
            // Try the common "name-1.2.3" / "name_1.2.3" / "name 1.2.3" patterns.
            val versionAnchor = Regex("[-_ .]\\d")
            val match = versionAnchor.find(assetName) ?: return null
            val prefix = assetName.substring(0, match.range.first + 1)
            // Need at least 2 meaningful chars; otherwise the suggestion is noise.
            return prefix.takeIf { it.length >= 2 }
        }
    }
}
