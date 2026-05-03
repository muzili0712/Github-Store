package zed.rainxch.core.presentation.components.announcements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import zed.rainxch.core.domain.model.AnnouncementCategory
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.announcements_category_news
import zed.rainxch.githubstore.core.presentation.res.announcements_category_privacy
import zed.rainxch.githubstore.core.presentation.res.announcements_category_security
import zed.rainxch.githubstore.core.presentation.res.announcements_category_status
import zed.rainxch.githubstore.core.presentation.res.announcements_category_survey
import zed.rainxch.githubstore.core.presentation.res.announcements_mute_locked
import zed.rainxch.githubstore.core.presentation.res.announcements_mute_settings_title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuteSettingsBottomSheet(
    mutedCategories: Set<AnnouncementCategory>,
    onToggle: (AnnouncementCategory, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(horizontal = 24.dp, vertical = 8.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.announcements_mute_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            AnnouncementCategory.entries.forEach { category ->
                CategoryRow(
                    category = category,
                    label = stringResource(categoryLabel(category)),
                    enabled = category !in mutedCategories,
                    locked = !category.isMutable,
                    onToggle = { newEnabled -> onToggle(category, !newEnabled) },
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CategoryRow(
    category: AnnouncementCategory,
    label: String,
    enabled: Boolean,
    locked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (locked) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = stringResource(Res.string.announcements_mute_locked),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = if (locked) ({ /* no-op */ }) else onToggle,
            enabled = !locked,
        )
    }
}

private fun categoryLabel(category: AnnouncementCategory): StringResource = when (category) {
    AnnouncementCategory.NEWS -> Res.string.announcements_category_news
    AnnouncementCategory.PRIVACY -> Res.string.announcements_category_privacy
    AnnouncementCategory.SURVEY -> Res.string.announcements_category_survey
    AnnouncementCategory.SECURITY -> Res.string.announcements_category_security
    AnnouncementCategory.STATUS -> Res.string.announcements_category_status
}
