package zed.rainxch.auth.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import zed.rainxch.auth.domain.repository.AuthenticationRepository
import zed.rainxch.auth.presentation.mapper.toUi
import zed.rainxch.auth.presentation.model.AuthLoginState
import zed.rainxch.auth.presentation.model.GithubDeviceStartUi
import zed.rainxch.core.domain.logging.GitHubStoreLogger
import zed.rainxch.core.domain.utils.BrowserHelper
import zed.rainxch.core.domain.utils.ClipboardHelper
import zed.rainxch.githubstore.core.presentation.res.*

class AuthenticationViewModel(
    private val authenticationRepository: AuthenticationRepository,
    private val browserHelper: BrowserHelper,
    private val clipboardHelper: ClipboardHelper,
    private val scope: CoroutineScope,
    private val logger: GitHubStoreLogger,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var hasLoadedInitialData = false
    private var countdownJob: Job? = null
    private var pollingJob: Job? = null

    private val _state: MutableStateFlow<AuthenticationState> =
        MutableStateFlow(AuthenticationState())

    private val _events = Channel<AuthenticationEvents>(capacity = Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val state =
        _state
            .onStart {
                if (!hasLoadedInitialData) {
                    scope.launch {
                        authenticationRepository.accessTokenFlow.collect { token ->
                            _state.update {
                                it.copy(
                                    loginState =
                                        if (token.isNullOrEmpty()) {
                                            AuthLoginState.LoggedOut
                                        } else {
                                            _events.trySend(AuthenticationEvents.OnNavigateToMain)
                                            AuthLoginState.LoggedIn
                                        },
                                )
                            }
                        }
                    }

                    restoreFromSavedState()
                    hasLoadedInitialData = true
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = AuthenticationState(),
            )

    fun onAction(action: AuthenticationAction) {
        when (action) {
            is AuthenticationAction.StartLogin -> {
                startLogin()
            }

            is AuthenticationAction.CopyCode -> {
                copyCode(action.start)
            }

            is AuthenticationAction.OpenGitHub -> {
                openGitHub(action.start)
            }

            AuthenticationAction.MarkLoggedIn -> {
                _state.update { it.copy(loginState = AuthLoginState.LoggedIn) }
            }

            AuthenticationAction.MarkLoggedOut -> {
                _state.update { it.copy(loginState = AuthLoginState.LoggedOut) }
            }

            is AuthenticationAction.OnInfo -> {
                _state.update {
                    it.copy(
                        info = action.message,
                    )
                }
            }

            AuthenticationAction.SkipLogin -> {
                _events.trySend(AuthenticationEvents.OnNavigateToMain)
            }

            AuthenticationAction.PollNow,
            AuthenticationAction.OnResumed,
            -> {
                tryPollIfReady()
            }
        }
    }

    private fun startCountdown(remainingSeconds: Int) {
        countdownJob?.cancel()
        countdownJob =
            viewModelScope.launch {
                var remaining = remainingSeconds
                while (remaining > 0) {
                    _state.update { currentState ->
                        val loginState = currentState.loginState
                        if (loginState is AuthLoginState.DevicePrompt) {
                            currentState.copy(
                                loginState = loginState.copy(remainingSeconds = remaining),
                            )
                        } else {
                            return@launch
                        }
                    }
                    delay(1000L)
                    remaining--
                }

                pollingJob?.cancel()
                clearSavedState()
                _state.update {
                    it.copy(
                        loginState =
                            AuthLoginState.Error(
                                message = getString(Res.string.auth_error_code_expired),
                                recoveryHint = getString(Res.string.auth_hint_try_again),
                            ),
                    )
                }
            }
    }

    private fun tryPollIfReady() {
        val loginState = _state.value.loginState
        if (loginState is AuthLoginState.DevicePrompt && !_state.value.isPolling) {
            pollOnce(loginState.start.deviceCode)
        }
    }

    private fun startLogin() {
        viewModelScope.launch {
            try {
                val start =
                    withContext(Dispatchers.IO) {
                        authenticationRepository.startDeviceFlow()
                    }

                val startUi = start.toUi()

                withContext(Dispatchers.Main.immediate) {
                    _state.update {
                        it.copy(
                            loginState =
                                AuthLoginState.DevicePrompt(
                                    start = startUi,
                                    remainingSeconds = start.expiresInSec,
                                ),
                            copied = false,
                        )
                    }

                    saveToSavedState(start.deviceCode, startUi)
                    startCountdown(start.expiresInSec)
                    startPolling(start.deviceCode)

                    try {
                        clipboardHelper.copy(
                            label = getString(Res.string.enter_code_on_github),
                            text = start.userCode,
                        )
                        _state.update { it.copy(copied = true) }
                    } catch (e: Exception) {
                        logger.debug("Failed to copy to clipboard: ${e.message}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                countdownJob?.cancel()
                pollingJob?.cancel()
                clearSavedState()
                val (message, hint) = categorizeError(t)
                withContext(Dispatchers.Main.immediate) {
                    _state.update {
                        it.copy(
                            loginState =
                                AuthLoginState.Error(
                                    message = message,
                                    recoveryHint = hint,
                                ),
                        )
                    }
                }
            }
        }
    }

    private fun startPolling(deviceCode: String) {
        pollingJob?.cancel()
        pollingJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    doPoll(deviceCode)
                }
            }
    }

    private fun pollOnce(deviceCode: String) {
        viewModelScope.launch {
            doPoll(deviceCode)
        }
    }

    private suspend fun doPoll(deviceCode: String) {
        _state.update { it.copy(isPolling = true) }
        try {
            val result =
                withContext(Dispatchers.IO) {
                    authenticationRepository.pollDeviceTokenOnce(deviceCode)
                }

            result
                .onSuccess { token ->
                    if (token != null) {
                        pollingJob?.cancel()
                        countdownJob?.cancel()
                        clearSavedState()
                        _state.update {
                            it.copy(loginState = AuthLoginState.LoggedIn, isPolling = false)
                        }
                        _events.trySend(AuthenticationEvents.OnNavigateToMain)
                    } else {
                        _state.update { it.copy(isPolling = false) }
                    }
                }.onFailure { error ->
                    pollingJob?.cancel()
                    countdownJob?.cancel()
                    clearSavedState()
                    val (message, hint) = categorizeError(error)
                    _state.update {
                        it.copy(
                            loginState =
                                AuthLoginState.Error(message = message, recoveryHint = hint),
                            isPolling = false,
                        )
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _state.update { it.copy(isPolling = false) }
            logger.debug("Unexpected poll error: ${t.message}")
        }
    }

    // region SavedStateHandle

    private fun saveToSavedState(
        deviceCode: String,
        startUi: GithubDeviceStartUi,
    ) {
        savedStateHandle[KEY_DEVICE_CODE] = deviceCode
        savedStateHandle[KEY_USER_CODE] = startUi.userCode
        savedStateHandle[KEY_VERIFICATION_URI] = startUi.verificationUri
        savedStateHandle[KEY_VERIFICATION_URI_COMPLETE] = startUi.verificationUriComplete
        savedStateHandle[KEY_INTERVAL_SEC] = startUi.intervalSec
        savedStateHandle[KEY_EXPIRES_IN_SEC] = startUi.expiresInSec
        savedStateHandle[KEY_START_TIME_MILLIS] = System.currentTimeMillis()
    }

    private fun clearSavedState() {
        SAVED_STATE_KEYS.forEach { savedStateHandle.remove<Any>(it) }
    }

    private fun restoreFromSavedState() {
        val deviceCode = savedStateHandle.get<String>(KEY_DEVICE_CODE) ?: return
        val userCode = savedStateHandle.get<String>(KEY_USER_CODE) ?: return
        val verificationUri = savedStateHandle.get<String>(KEY_VERIFICATION_URI) ?: return
        val expiresInSec = savedStateHandle.get<Int>(KEY_EXPIRES_IN_SEC) ?: return
        val intervalSec = savedStateHandle.get<Int>(KEY_INTERVAL_SEC) ?: 5
        val startTimeMillis = savedStateHandle.get<Long>(KEY_START_TIME_MILLIS) ?: return

        val elapsedSec = ((System.currentTimeMillis() - startTimeMillis) / 1000).toInt()
        val remainingSec = expiresInSec - elapsedSec

        if (remainingSec <= 0) {
            clearSavedState()
            return
        }

        val startUi =
            GithubDeviceStartUi(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = verificationUri,
                verificationUriComplete = savedStateHandle.get<String>(KEY_VERIFICATION_URI_COMPLETE),
                intervalSec = intervalSec,
                expiresInSec = expiresInSec,
            )

        _state.update {
            it.copy(loginState = AuthLoginState.DevicePrompt(startUi, remainingSec))
        }

        startCountdown(remainingSec)
        startPolling(deviceCode)
        pollOnce(deviceCode)
    }

    // endregion

    private suspend fun categorizeError(t: Throwable): Pair<String, String?> {
        val msg = t.message ?: return getString(Res.string.error_unknown) to null
        val lowerMsg = msg.lowercase()
        return when {
            "timeout" in lowerMsg || "timed out" in lowerMsg -> {
                msg to getString(Res.string.auth_hint_check_connection)
            }

            "network" in lowerMsg || "unresolvedaddress" in lowerMsg || "connect" in lowerMsg -> {
                msg to getString(Res.string.auth_hint_check_connection)
            }

            "expired" in lowerMsg || "expire" in lowerMsg -> {
                msg to getString(Res.string.auth_hint_try_again)
            }

            "denied" in lowerMsg || "access_denied" in lowerMsg -> {
                msg to getString(Res.string.auth_hint_denied)
            }

            else -> {
                msg to null
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
        pollingJob?.cancel()
    }

    private fun openGitHub(start: GithubDeviceStartUi) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            try {
                val url = start.verificationUriComplete ?: start.verificationUri
                browserHelper.openUrl(url)
            } catch (e: Exception) {
                logger.debug("Failed to open browser: ${e.message}")
            }
        }
    }

    private fun copyCode(start: GithubDeviceStartUi) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            try {
                clipboardHelper.copy(
                    label = "GitHub Code",
                    text = start.userCode,
                )

                _state.update {
                    val currentRemaining =
                        (it.loginState as? AuthLoginState.DevicePrompt)?.remainingSeconds ?: 0

                    it.copy(
                        loginState = AuthLoginState.DevicePrompt(start, currentRemaining),
                        copied = true,
                    )
                }
            } catch (e: Exception) {
                logger.debug("Failed to copy to clipboard: ${e.message}")
                _state.update {
                    val currentRemaining =
                        (it.loginState as? AuthLoginState.DevicePrompt)?.remainingSeconds ?: 0

                    it.copy(
                        loginState = AuthLoginState.DevicePrompt(start, currentRemaining),
                        copied = false,
                    )
                }
            }
        }
    }

    companion object {
        private const val KEY_DEVICE_CODE = "auth_device_code"
        private const val KEY_USER_CODE = "auth_user_code"
        private const val KEY_VERIFICATION_URI = "auth_verification_uri"
        private const val KEY_VERIFICATION_URI_COMPLETE = "auth_verification_uri_complete"
        private const val KEY_INTERVAL_SEC = "auth_interval_sec"
        private const val KEY_EXPIRES_IN_SEC = "auth_expires_in_sec"
        private const val KEY_START_TIME_MILLIS = "auth_start_time_millis"
        private const val POLL_INTERVAL_MS = 15_000L

        private val SAVED_STATE_KEYS =
            listOf(
                KEY_DEVICE_CODE,
                KEY_USER_CODE,
                KEY_VERIFICATION_URI,
                KEY_VERIFICATION_URI_COMPLETE,
                KEY_INTERVAL_SEC,
                KEY_EXPIRES_IN_SEC,
                KEY_START_TIME_MILLIS,
            )
    }
}
