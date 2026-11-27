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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fourburners.model.Step
import app.fourburners.parse.PlanError
import app.fourburners.ui.theme.Aqua
import app.fourburners.ui.theme.Background
import app.fourburners.ui.theme.Foreground
import app.fourburners.ui.theme.Muted
import app.fourburners.ui.theme.RecipeColors
import app.fourburners.ui.theme.Rose

@Composable
fun ImportScreen(
    plan: app.fourburners.model.Plan?,
    errors: List<PlanError>,
    onStartCooking: () -> Unit,
    onCancel: () -> Unit,
    onPasteCorrected: () -> Unit,
) {
    val hasErrors = errors.isNotEmpty()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!hasErrors && plan != null) {
            ImportPlan(plan, onStartCooking, onCancel)
        } else {
            ImportError(errors, onPasteCorrected, onCancel)
        }
    }
}

@Composable
private fun ImportPlan(plan: app.fourburners.model.Plan, onStart: () -> Unit, onCancel: () -> Unit) {
    val totalSec = plan.steps.sumOf { it.durationSec }
    val activeSec = plan.steps.filter { it.kind.name == "active" }.sumOf { it.durationSec }

    Text("New cook plan", style = MaterialTheme.typography.labelLarge.copy(color = Muted))
    Text(plan.title ?: "Untitled plan", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = Foreground))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        StatTile("Total", formatTime(totalSec))
        StatTile("Hands-on", formatTime(activeSec))
        StatTile("Steps", plan.steps.size.toString())
    }

    val readyAt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(
        java.util.Date(System.currentTimeMillis() + totalSec * 1000L)
    )
    Text("Start now, ready at $readyAt", style = MaterialTheme.typography.bodyMedium.copy(color = Aqua))

    plan.recipes.forEachIndexed { idx, recipe ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .clip(RoundedCornerShape(16.dp)).background(RecipeColors[idx % 4].copy(alpha = 0.2f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(RecipeColors[idx % 4]))
            Spacer(Modifier.width(8.dp))
            Text(recipe.name, style = MaterialTheme.typography.bodyMedium.copy(color = Foreground))
        }
    }

    Surface(modifier = Modifier.fillMaxWidth(), color = Background, shape = RoundedCornerShape(8.dp)) {
        plan.steps.forEach { step -> StepRow(step) }
    }

    Spacer(Modifier.height(24.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
        Button(onClick = onStart, modifier = Modifier.weight(2f)) { Text("Start cooking") }
    }
}

@Composable
private fun ImportError(errors: List<PlanError>, onPasteCorrected: () -> Unit, onCancel: () -> Unit) {
    Text("Plan not loaded", style = MaterialTheme.typography.headlineSmall.copy(color = Rose))
    Spacer(Modifier.height(12.dp))
    Surface(modifier = Modifier.fillMaxWidth(), color = Background, shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            errors.forEach { err ->
                Text("Rule ${err.rule}: ${err.message}", style = MaterialTheme.typography.bodyMedium.copy(color = Rose))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Nothing was changed.", style = MaterialTheme.typography.bodySmall.copy(color = Muted))
    Spacer(Modifier.height(24.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onPasteCorrected, modifier = Modifier.weight(2f)) { Text("Paste a corrected plan") }
        Button(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Close") }
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall.copy(color = Foreground))
        Text(label, style = MaterialTheme.typography.labelMedium.copy(color = Muted))
    }
}

@Composable
private fun StepRow(step: Step) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(formatOffset(step.plannedStartSec), style = MaterialTheme.typography.bodySmall.copy(color = Muted))
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4)))
        Spacer(Modifier.width(8.dp))
        Text(step.title, style = MaterialTheme.typography.bodyMedium.copy(color = Foreground))
        Spacer(Modifier.weight(1f))
        val tagText = if (step.kind.name == "active") " ${formatTime(step.durationSec)}" else " timer ${formatTime(step.durationSec)}"
        Text(tagText, style = MaterialTheme.typography.bodySmall.copy(color = Muted))
    }
}
