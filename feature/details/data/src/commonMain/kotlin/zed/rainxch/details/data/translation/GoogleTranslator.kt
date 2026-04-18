package zed.rainxch.details.data.translation

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import zed.rainxch.details.domain.model.TranslationResult

/**
 * Hits Google's undocumented `translate_a/single` endpoint. Works
 * everywhere Google does, no credentials. May rate-limit or break
 * without notice; Youdao is the escape hatch.
 */
internal class GoogleTranslator(
    private val httpClient: () -> HttpClient,
    private val json: Json,
) : Translator {
    // GET request — constrained by URL length. 4500 leaves headroom for
    // the query params + the URL-encoded text itself.
    override val maxChunkSize: Int = 4500

    override suspend fun translate(
        text: String,
        targetLanguage: String,
        sourceLanguage: String,
    ): TranslationResult {
        val body =
            httpClient()
                .get("https://translate.googleapis.com/translate_a/single") {
                    parameter("client", "gtx")
                    parameter("sl", sourceLanguage)
                    parameter("tl", targetLanguage)
                    parameter("dt", "t")
                    parameter("q", text)
                }.bodyAsText()

        val root = json.parseToJsonElement(body).jsonArray
        val segments = root[0].jsonArray
        val translated =
            segments.joinToString("") { segment ->
                segment.jsonArray[0].jsonPrimitive.content
            }
        val detected =
            runCatching { root[2].jsonPrimitive.content }.getOrNull()

        return TranslationResult(
            translatedText = translated,
            detectedSourceLanguage = detected,
        )
    }
}
