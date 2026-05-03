package zed.rainxch.core.data.network.interceptor

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.http.HttpHeaders
import io.ktor.util.AttributeKey
import zed.rainxch.core.domain.repository.AuthenticationState

class UnauthorizedInterceptor(
    private val authenticationState: AuthenticationState,
) {
    class Config {
        var authenticationState: AuthenticationState? = null
    }

    companion object Plugin : HttpClientPlugin<Config, UnauthorizedInterceptor> {
        override val key: AttributeKey<UnauthorizedInterceptor> =
            AttributeKey("UnauthorizedInterceptor")

        override fun prepare(block: Config.() -> Unit): UnauthorizedInterceptor {
            val config = Config().apply(block)
            return UnauthorizedInterceptor(
                authenticationState =
                    requireNotNull(config.authenticationState) {
                        "AuthenticationState must be provided"
                    },
            )
        }

        override fun install(
            plugin: UnauthorizedInterceptor,
            scope: HttpClient,
        ) {
            scope.receivePipeline.intercept(HttpReceivePipeline.After) {
                val tokenKey = extractBearerToken(subject.call.request.headers[HttpHeaders.Authorization])
                if (subject.status.value == 401) {
                    plugin.authenticationState.notifySessionExpired(tokenKey)
                } else {
                    plugin.authenticationState.notifyRequestSucceeded(tokenKey)
                }
                proceedWith(subject)
            }
        }

        private fun extractBearerToken(headerValue: String?): String? {
            if (headerValue.isNullOrEmpty()) return null
            val trimmed = headerValue.trim()
            val withoutScheme = when {
                trimmed.startsWith("Bearer ", ignoreCase = true) ->
                    trimmed.substring("Bearer ".length)
                trimmed.startsWith("token ", ignoreCase = true) ->
                    trimmed.substring("token ".length)
                else -> trimmed
            }
            return withoutScheme.trim().takeIf { it.isNotEmpty() }
        }
    }
}
