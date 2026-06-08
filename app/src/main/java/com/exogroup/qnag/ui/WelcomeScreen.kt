package com.exogroup.qnag.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.R

@Composable
fun WelcomeScreen(
    versionName: String,
    onAddInstance: () -> Unit,
    onImportConfiguration: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Composite the two adaptive-icon layers (background gradient + white Q foreground)
        // in a circle-clipped Box. Using the individual drawables avoids the crash that
        // painterResource produces when given an adaptive-icon XML on API 26+.
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_background),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "qNag",
            style = MaterialTheme.typography.displayMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Phone-side Nagios monitoring client",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Monitor one or more Nagios instances, receive Android alerts, ACK/recheck " +
            "problems, schedule downtime, and use Reliability Mode for foreground polling.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Version $versionName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onAddInstance,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add Nagios instance")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onImportConfiguration,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Import configuration")
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Before using qNag for on-call monitoring, validate notifications, sound, " +
            "battery settings, exact alarm permission, and Reliability Mode on your device.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
