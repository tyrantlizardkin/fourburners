package app.fourburners.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fourburners.engine.SessionEngine
import app.fourburners.model.Kind
import app.fourburners.ui.theme.Aqua
import app.fourburners.ui.theme.Amber
import app.fourburners.ui.theme.Background
import app.fourburners.ui.theme.Foreground
import app.fourburners.ui.theme.Muted

@Composable
fun DoneScreen(
    session: app.fourburners.model.Session,
    onClose: () -> Unit,
) {
    val nowMs = System.currentTimeMillis()
    val elapsedSec = SessionEngine.elapsedSec(session, nowMs)
    val totalPlannedSec = session.plan.steps.sumOf { it.durationSec }
    val activeSec = session.plan.steps.filter { it.kind == Kind.ACTIVE }.sumOf { it.durationSec }
    val diffSec = elapsedSec - totalPlannedSec

    Column(
        modifier = Modifier.fillMaxSize().background(Background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Overline
        Text("Done", style = MaterialTheme.typography.labelLarge.copy(color = Aqua, fontWeight = FontWeight.SemiBold))

        Spacer(Modifier.height(8.dp))

        // Main title
        Text("Everything is ready.", style = MaterialTheme.typography.displayMedium.copy(color = Foreground, fontWeight = FontWeight.Bold))

        Spacer(Modifier.height(24.dp))

        // Stat tiles
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatTile("Took", formatTime(elapsedSec))
            StatTile("Planned", formatTime(totalPlannedSec))
            val diffColor = if (diffSec > 0) Amber else Foreground
            StatTile("Diff", if (diffSec > 0) "+${formatTime(diffSec)}" else formatTime(diffSec), color = diffColor)
        }

        Spacer(Modifier.height(24.dp))

        // One line info
        val startTime = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(session.startedAtMs))
        Text("$startTime \u00B7 $activeSec min hands-on ${elapsedSec - activeSec} min free", style = MaterialTheme.typography.bodyMedium.copy(color = Muted))

        Spacer(Modifier.height(32.dp))

        // Close button
        Button(onClick = onClose, modifier = Modifier.width(200.dp).height(56.dp)) {
            Text("Close")
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, color: androidx.compose.ui.graphics.Color = Foreground) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium.copy(color = color, fontWeight = FontWeight.Bold))
        Text(label, style = MaterialTheme.typography.labelMedium.copy(color = Muted))
    }
}
