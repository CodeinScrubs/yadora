package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Minimal theme customization: appearance mode (System / Light / Dark) + an accent color.
 * Saves to the shared prefs and calls [onThemeChange] so the whole app recolors instantly.
 * Accent "" means the default Quiet-Mastery indigo; any other value is a parseable #RRGGBB hex.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(onBack: () -> Unit, onThemeChange: (String, String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE) }
    val strings = com.example.ui.i18n.LocalStrings.current
    val isFa = strings.languageCode == "fa"

    var mode by remember { mutableStateOf(sp.getString("theme_mode", "system") ?: "system") }
    var accent by remember { mutableStateOf(sp.getString("accent_color", "") ?: "") }

    val modes = listOf(
        "system" to (if (isFa) "سیستم" else if (strings.languageCode == "de") "System" else "System"),
        "light" to (if (isFa) "روشن" else if (strings.languageCode == "de") "Hell" else "Light"),
        "dark" to (if (isFa) "تیره" else if (strings.languageCode == "de") "Dunkel" else "Dark")
    )
    // storage value -> swatch color. "" is the default sage brand. A small, curated set that all
    // harmonize with the warm-paper surface (no neon); none collide with the reserved rating hues.
    val accents = listOf(
        "" to com.example.ui.theme.Sage, // Sage (default brand)
        "#33689B" to Color(0xFF33689B),  // Steel blue
        "#6B5B95" to Color(0xFF6B5B95),  // Muted plum
        "#A8554E" to Color(0xFFA8554E)   // Clay
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isFa) "تم و رنگ‌ها" else if (strings.languageCode == "de") "Design & Farben" else "Theme & Colors") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (isFa) "بازگشت" else if (strings.languageCode == "de") "Zurück" else "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
        ) {
            Text(
                text = if (isFa) "حالت" else if (strings.languageCode == "de") "Darstellung" else "Appearance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { (value, label) ->
                    FilterChip(
                        selected = mode == value,
                        onClick = {
                            mode = value
                            sp.edit().putString("theme_mode", value).apply()
                            onThemeChange(value, accent)
                        },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                text = if (isFa) "رنگ اصلی" else if (strings.languageCode == "de") "Akzentfarbe" else "Accent color",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                accents.forEach { (hex, color) ->
                    val selected = accent == hex
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            )
                            .clickable {
                                accent = hex
                                sp.edit().putString("accent_color", hex).apply()
                                onThemeChange(mode, hex)
                            }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = if (isFa) "تغییرات بلافاصله اعمال می‌شوند." else if (strings.languageCode == "de") "Änderungen gelten sofort." else "Changes apply instantly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
