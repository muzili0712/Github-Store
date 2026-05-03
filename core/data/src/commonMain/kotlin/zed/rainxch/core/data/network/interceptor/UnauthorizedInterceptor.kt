package zed.rainxch.core.data.network.interceptor

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.http.HttpHeaders
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import zed.rainxch.core.domain.repository.AuthenticationState

class UnauthorizedInterceptor(
    private val authenticationState: AuthenticationState,
    private val scope: CoroutineScope,
) {
    class Config {
        var authenticationState: AuthenticationState? = null
        var scope: CoroutineScope? = null
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
                scope =
                    requireNotNull(config.scope) {
                        "CoroutineScope must be provided"
                    },
            )
        }

        override fun install(
            plugin: UnauthorizedInterceptor,
            scope: HttpClient,
        ) {
            scope.receivePipeline.intercept(HttpReceivePipeline.After) {
                val tokenKey = extractBearerToken(subject.call.request.headers[HttpHeaders.Authorization])
                val statusCode = subject.status.value
                when {
                    statusCode == 401 -> {
                        plugin.scope.launch {
                            plugin.authenticationState.notifySessionExpired(tokenKey)
                        }
                    }
                    statusCode in 200..299 -> {
                        plugin.scope.launch {
                            plugin.authenticationState.notifyRequestSucceeded(tokenKey)
                        }
                    }
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
