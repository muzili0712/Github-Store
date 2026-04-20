package zed.rainxch.core.data.services

interface LocalizationManager {
    /**
     * Returns the current device language code in ISO 639-1 format (e.g., "en", "zh", "ja")
     * Can include region code if available (e.g., "zh-CN", "pt-BR")
     */
    fun getCurrentLanguageCode(): String

    /**
     * Returns the primary language code without region (e.g., "zh" from "zh-CN")
     */
    fun getPrimaryLanguageCode(): String

    /**
     * Overrides the process-wide JVM `Locale.getDefault()` used by
     * Compose Resources' `LocalComposeEnvironment` for string
     * resolution. Passing `null` (or blank) restores the original
     * system locale captured at instance construction.
     *
     * Must be called from the composition side (see `App()`) *before*
     * the `key(appLanguage)`-wrapped content remounts, so the new
     * locale is picked up when `stringResource` re-reads
     * `Locale.current` on recomposition.
     */
    fun setActiveLanguageTag(tag: String?)
}
