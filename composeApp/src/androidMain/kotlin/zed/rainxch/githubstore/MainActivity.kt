package zed.rainxch.githubstore

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject
import zed.rainxch.core.data.services.LocalizationManager
import zed.rainxch.core.data.utils.AndroidShareManager
import zed.rainxch.core.domain.repository.TweaksRepository
import zed.rainxch.core.domain.utils.ShareManager
import zed.rainxch.githubstore.app.deeplink.DeepLinkParser

private const val LANGUAGE_PREF_READ_TIMEOUT_MS = 2000L

class MainActivity : ComponentActivity() {
    private var deepLinkUri by mutableStateOf<String?>(null)
    private val shareManager: ShareManager by inject()
    private val tweaksRepository: TweaksRepository by inject()
    private val localizationManager: LocalizationManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()

        // Register activity result launcher for file picker (must be before STARTED)
        (shareManager as? AndroidShareManager)?.registerActivityResultLauncher(this)

        // Apply the persisted language override BEFORE Compose kicks off
        // so the very first frame resolves strings against the user's
        // choice. `runBlocking` is acceptable here — DataStore reads are
        // cheap and we only block once per Activity creation (including
        // the post-language-swap recreate() path below). Without this,
        // recreate() would briefly flash the old locale before settling.
        runBlocking {
            val tag =
                try {
                    withTimeoutOrNull(LANGUAGE_PREF_READ_TIMEOUT_MS) {
                        tweaksRepository.getAppLanguage().first()
                    }
                } catch (_: Exception) {
                    null
                }
            localizationManager.setActiveLanguageTag(tag)
        }

        super.onCreate(savedInstanceState)

        handleIncomingIntent(intent)

        // Watch for runtime language changes from the Tweaks picker.
        // Drop the initial emission (already applied above) and
        // recreate() on any subsequent change — Android preserves
        // `rememberSaveable` / ViewModel state through recreate, so
        // scroll offsets, nav stack, and form fields all survive while
        // every string re-resolves against the new locale. `key()` in
        // the composition can't pull off the same trick: it changes
        // the composite-key hash under it, which breaks
        // `rememberSaveable` lookups and snaps LazyColumns back to 0.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tweaksRepository
                    .getAppLanguage()
                    .drop(1)
                    .collect { newTag ->
                        localizationManager.setActiveLanguageTag(newTag)
                        recreate()
                    }
            }
        }

        setContent {
            DisposableEffect(Unit) {
                val listener =
                    Consumer<Intent> { newIntent ->
                        handleIncomingIntent(newIntent)
                    }
                addOnNewIntentListener(listener)
                onDispose {
                    removeOnNewIntentListener(listener)
                }
            }

            App(deepLinkUri = deepLinkUri)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val uriString =
            when (intent.action) {
                Intent.ACTION_VIEW -> {
                    intent.data?.toString()
                }

                Intent.ACTION_SEND -> {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    sharedText?.let { DeepLinkParser.extractSupportedUrl(it) }
                }

                else -> {
                    null
                }
            }

        uriString?.let { deepLinkUri = it }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
