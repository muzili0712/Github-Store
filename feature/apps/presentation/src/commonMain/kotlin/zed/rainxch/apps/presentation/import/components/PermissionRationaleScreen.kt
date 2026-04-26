package zed.rainxch.apps.presentation.import.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import zed.rainxch.apps.presentation.import.ExternalImportAction
import zed.rainxch.apps.presentation.import.util.rememberPackageVisibilityRequester
import zed.rainxch.apps.presentation.import.util.rememberSdkInt

private const val BODY_COPY =
    "We can scan your installed apps and match them to GitHub releases — so updates and detection just work.\n\n" +
        "To do that, we need to see which apps you have. Without permission we can only see about 5 apps; with it, we can see all of them.\n\n" +
        "We never send the list of your apps anywhere without your permission. The match runs on your device. " +
        "The optional backend lookup sends only the package name and app label of apps you ask us to match — never a full list of what's installed."

@Composable
fun PermissionRationaleScreen(
    onAction: (ExternalImportAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sdkInt = rememberSdkInt()
    val requester = rememberPackageVisibilityRequester()
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )

            Text(
                // TODO i18n: extract to strings.xml
                text = "Find your GitHub apps",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            Text(
                // TODO i18n: extract to strings.xml
                text = BODY_COPY,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onAction(ExternalImportAction.OnPermissionDenied(sdkInt)) },
                ) {
                    // TODO i18n: extract to strings.xml
                    Text("Not now")
                }
                Button(onClick = {
                    scope.launch {
                        onAction(ExternalImportAction.OnRequestPermission)
                        if (requester.isGranted) {
                            onAction(ExternalImportAction.OnPermissionGranted(sdkInt))
                        } else {
                            requester.requestOrOpenSettings()
                            // We can't auto-confirm grant from a settings deep-link.
                            // Optimistically advance — the scanner's degraded path
                            // handles the actual visibility outcome.
                            onAction(ExternalImportAction.OnPermissionGranted(sdkInt))
                        }
                    }
                }) {
                    // TODO i18n: extract to strings.xml
                    Text("Continue")
                }
            }
        }
    }
}
