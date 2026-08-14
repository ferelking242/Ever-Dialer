package com.coolappstore.everdialer.by.svhp.view.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.PhoneCallback
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.coolappstore.everdialer.by.svhp.controller.util.PreferenceManager

/** One selectable "Choose Sim" option shown in [ChooseSimDialog]. */
data class SimChoiceOption(
    val value: String,
    val label: String,
    val subLabel: String? = null,
    val icon: ImageVector
)

/** All available "Choose Sim" options, in display order. */
val SIM_CHOICE_OPTIONS = listOf(
    SimChoiceOption(PreferenceManager.SIM_CHOICE_SETTINGS, "According to Settings", "Use the app-wide default SIM setting", Icons.Default.Tune),
    SimChoiceOption(PreferenceManager.SIM_CHOICE_ASK, "Ask Every Time", "Show the SIM picker on every call", Icons.Default.HelpOutline),
    SimChoiceOption(PreferenceManager.SIM_CHOICE_SIM1, "SIM 1", null, Icons.Default.SimCard),
    SimChoiceOption(PreferenceManager.SIM_CHOICE_SIM2, "SIM 2", null, Icons.Default.SimCard),
    SimChoiceOption(PreferenceManager.SIM_CHOICE_LAST_FOR_CONTACT, "Last Used SIM for This Contact", "Reuse the SIM from the most recent call with them", Icons.Default.History),
    SimChoiceOption(PreferenceManager.SIM_CHOICE_LAST_IN_CALL, "Last Used SIM in Previous Call", "Reuse the SIM from the last call made from the app", Icons.Default.PhoneCallback)
)

/** Returns the display label for a stored "Choose Sim" preference value. */
fun simChoiceLabel(value: String): String =
    SIM_CHOICE_OPTIONS.firstOrNull { it.value == value }?.label ?: SIM_CHOICE_OPTIONS.first().label

/**
 * Floating popup listing every "Choose Sim" option for a contact, with the currently selected
 * option checked off.
 */
@Composable
fun ChooseSimDialog(
    currentChoice: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    "Choose Sim",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
                SIM_CHOICE_OPTIONS.forEach { option ->
                    val selected = option.value == currentChoice
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option.value) }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            option.icon,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                            if (option.subLabel != null) {
                                Text(
                                    option.subLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (selected) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp)
                ) { Text("Cancel") }
            }
        }
    }
}
