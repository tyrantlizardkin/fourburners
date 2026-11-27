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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fourburners.engine.SessionEngine
import app.fourburners.model.Status
import app.fourburners.ui.theme.Aqua
import app.fourburners.ui.theme.Background
import app.fourburners.ui.theme.Foreground
import app.fourburners.ui.theme.Hairline
import app.fourburners.ui.theme.Muted
import app.fourburners.ui.theme.RecipeColors

@Composable
fun ScheduleScreen(
    session: app.fourburners.model.Session,
    onBack: () -> Unit,
) {
    val projected = SessionEngine.project(session, System.currentTimeMillis())
    Column(modifier = Modifier.fillMaxSize().background(Background).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("\u2039 Live") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Full schedule", style = MaterialTheme.typography.headlineSmall.copy(color = Foreground, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(16.dp))

        // Recipe chips
        session.plan.recipes.forEachIndexed { idx, recipe ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(RecipeColors[idx % 4]))
                Spacer(Modifier.width(6.dp))
                Text(recipe.short ?: recipe.name, style = MaterialTheme.typography.bodyMedium.copy(color = Foreground))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Steps in projected order
        projected.forEach { ps ->
            val step = session.plan.steps.first { it.id == ps.stepId }
            val state = session.steps[step.id]
            val isNow = state?.status == Status.RUNNING
            val isDone = state?.status == Status.DONE

            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 8.dp)
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Time column
                val timeText = when {
                    isDone -> state?.let { formatOffset(((it.startedAtMs ?: 0) - session.startedAtMs).toInt() / 1000) } ?: ""
                    isNow -> "now"
                    else -> formatOffset(((ps.startMs - session.startedAtMs) / 1000L).toInt().coerceAtLeast(0))
                }
                Text(timeText, style = MaterialTheme.typography.bodySmall.copy(color = Muted), modifier = Modifier.width(48.dp))

                Spacer(Modifier.width(8.dp))

                // Color bar
                val color = step.recipe?.let { rId ->
                    session.plan.recipes.indexOfFirst { it.id == rId }.takeIf { it >= 0 }?.let { RecipeColors[it % 4] }
                } ?: Hairline
                Box(modifier = Modifier.size(4.dp, 24.dp).background(color))

                Spacer(Modifier.width(8.dp))

                // Step info
                val shortName = step.recipe?.let { rId -> session.plan.recipes.find { it.id == rId }?.short ?: "" } ?: ""
                Text(shortName, style = MaterialTheme.typography.labelSmall.copy(color = Muted))
                Spacer(Modifier.width(4.dp))
                Text(step.title, style = MaterialTheme.typography.bodyMedium.copy(
                    color = if (isDone) Foreground.copy(alpha = 0.45f) else Foreground,
                    textDecoration = if (isNow) androidx.compose.ui.text.style.TextDecoration.Underline else null,
                ))

                Spacer(Modifier.weight(1f))

                // Tag
                val tagText = when {
                    isDone -> "done"
                    isNow -> "running"
                    step.kind.name == "passive" -> "timer ${formatTime(step.durationSec)}"
                    else -> formatTime(step.durationSec)
                }
                Text(tagText, style = MaterialTheme.typography.bodySmall.copy(color = Muted))
            }
        }

        // Final row
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(4.dp, 24.dp).background(Aqua))
            Spacer(Modifier.width(12.dp))
            Text("Everything is ready", style = MaterialTheme.typography.bodyMedium.copy(color = Aqua, fontWeight = FontWeight.Bold))
        }
    }
}
