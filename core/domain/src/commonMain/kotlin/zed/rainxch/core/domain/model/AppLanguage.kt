package zed.rainxch.core.domain.model

/**
 * A user-selectable UI language for the app. Each entry corresponds to a
 * `values-<qualifier>` directory that ships with the Compose resources
 * bundle, so the [tag] must match what the Android-style locale qualifier
 * resolves to (e.g. `zh-rCN` → language tag `zh-CN`).
 *
 * [displayName] is intentionally hard-coded in the native script so the
 * picker is readable regardless of the currently active UI language — a
 * user stuck in the wrong language needs to recognise their own language
 * to escape.
 */
data class AppLanguage(
    /** IETF BCP 47 language tag (e.g. `en`, `zh-CN`, `pt-BR`). */
    val tag: String,
    /** Native-script label, e.g. `简体中文`, `Español`. */
    val displayName: String,
)

/**
 * Registry of languages the app currently ships translations for. Keep
 * in sync with `core/presentation/src/commonMain/composeResources/values-*`
 * directories. Order is the order shown in the Tweaks picker (English
 * first as the source-of-truth language, rest alphabetised by tag).
 */
object AppLanguages {
    val ALL: List<AppLanguage> =
        listOf(
            AppLanguage("en", "English"),
            AppLanguage("ar", "العربية"),
            AppLanguage("bn", "বাংলা"),
            AppLanguage("es", "Español"),
            AppLanguage("fr", "Français"),
            AppLanguage("hi", "हिन्दी"),
            AppLanguage("it", "Italiano"),
            AppLanguage("ja", "日本語"),
            AppLanguage("ko", "한국어"),
            AppLanguage("pl", "Polski"),
            AppLanguage("ru", "Русский"),
            AppLanguage("tr", "Türkçe"),
            AppLanguage("zh-CN", "简体中文"),
        )

    fun findByTag(tag: String?): AppLanguage? =
        if (tag.isNullOrBlank()) null else ALL.find { it.tag == tag }

    fun containsTag(tag: String?): Boolean = findByTag(tag) != null
}
