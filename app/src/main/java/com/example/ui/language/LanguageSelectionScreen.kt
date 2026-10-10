package com.example.ui.language

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun LanguageSelectionScreen(onLanguageSelected: (String) -> Unit) {
    val context = LocalContext.current
    // Saveable: a rotation went back to English, and Continue then stored English (a production review, 2026-10-10).
    var selectedLang by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("en") }

    // First-run screens sit outside the app's Scaffolds, so this one paints its own background. The XML
    // window theme is dark: without this, a phone in light mode showed light cards on a dark window.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    // Centred as before, and scrolling when the screen is too short for it (a phone in landscape), where Continue was cut
    // off (a production review, 2026-10-10).
    BoxWithConstraints(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
    val screenHeight = maxHeight
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .heightIn(min = screenHeight)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val strings = com.example.ui.i18n.LocalStrings.current
        val freshLightBlue = com.example.ui.theme.Sage // brand sage (first-run screen stays on-brand)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Translate,
                contentDescription = "Language Icon",
                tint = freshLightBlue,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Language",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = freshLightBlue
            )
        }
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedCard(
            onClick = { selectedLang = "en" },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = if (selectedLang == "en") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            )
        ) {
            Text(
                "English",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selectedLang == "en") FontWeight.Bold else FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedCard(
            onClick = { selectedLang = "fa" },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = if (selectedLang == "fa") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            )
        ) {
            Text(
                "فارسی (Persian)",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selectedLang == "fa") FontWeight.Bold else FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedCard(
            onClick = { selectedLang = "de" },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = if (selectedLang == "de") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            )
        ) {
            Text(
                "Deutsch (German)",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selectedLang == "de") FontWeight.Bold else FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onLanguageSelected(selectedLang) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        ) {
            val strings = com.example.ui.i18n.LocalStrings.current
            Text(strings.continueBtn)
        }
    }
    }
    }
}
