package zed.rainxch.core.data.mappers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import zed.rainxch.core.domain.model.ExportedApp
import zed.rainxch.core.domain.model.ObtainiumApp

data class ObtainiumMapResult(
    val exported: ExportedApp?,
    val nonGitHubLabel: String?,
)

fun ObtainiumApp.toExportedAppOrSkip(json: Json): ObtainiumMapResult {
    val rawUrl = url.trim()
    val (owner, repo) = parseGithubOwnerRepo(rawUrl)
        ?: return ObtainiumMapResult(
            exported = null,
            nonGitHubLabel = (name?.takeIf { it.isNotBlank() } ?: id.ifBlank { rawUrl }) +
                if (rawUrl.isNotEmpty()) " ($rawUrl)" else "",
        )

    val packageName = id.trim().takeIf { it.isNotBlank() } ?: return ObtainiumMapResult(
        exported = null,
        nonGitHubLabel = "$owner/$repo (missing package id)",
    )

    val additional = additionalSettingsRaw
    val (filterRegex, fallbackToOlderReleases) = parseAdditionalSettings(additional, json)

    return ObtainiumMapResult(
        exported = ExportedApp(
            packageName = packageName,
            repoOwner = owner,
            repoName = repo,
            repoUrl = rawUrl,
            assetFilterRegex = filterRegex,
            fallbackToOlderReleases = fallbackToOlderReleases,
            preferredAssetVariant = null,
            preferredAssetTokens = null,
            assetGlobPattern = null,
            pickedAssetIndex = preferredApkIndex,
            pickedAssetSiblingCount = null,
        ),
        nonGitHubLabel = null,
    )
}

private fun parseGithubOwnerRepo(rawUrl: String): Pair<String, String>? {
    if (rawUrl.isBlank()) return null
    val trimmed = rawUrl.substringBefore('?').substringBefore('#').trimEnd('/')
    val withoutScheme = trimmed.removePrefix("https://").removePrefix("http://")
    val withoutHost = withoutScheme
        .removePrefix("www.")
        .removePrefix("github.com/")
    if (withoutScheme == withoutHost && !withoutScheme.startsWith("github.com")) return null
    val cleaned = if (withoutScheme.startsWith("github.com")) {
        withoutScheme.removePrefix("github.com").trimStart('/')
    } else {
        withoutHost
    }
    val parts = cleaned.split('/')
    if (parts.size < 2) return null
    val owner = parts[0]
    val repo = parts[1]
    if (owner.isBlank() || repo.isBlank()) return null
    if (owner.length > 39 || repo.length > 100) return null
    return owner to repo
}

private fun parseAdditionalSettings(
    additional: kotlinx.serialization.json.JsonElement?,
    json: Json,
): Pair<String?, Boolean> {
    if (additional == null) return null to false
    val obj = when (additional) {
        is JsonPrimitive -> additional.contentOrNull?.let {
            runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
        }
        else -> runCatching { additional.jsonObject }.getOrNull()
    } ?: return null to false

    val filter = obj["apkFilterRegEx"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val invertFilter = obj["invertAPKFilter"]?.jsonPrimitive?.runCatching { boolean }?.getOrNull() == true
    val fallback = obj["fallbackToOlderReleases"]?.jsonPrimitive?.runCatching { boolean }?.getOrNull() == true

    val effectiveFilter = if (invertFilter) null else filter
    return effectiveFilter to fallback
}
