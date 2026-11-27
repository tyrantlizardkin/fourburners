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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fourburners.engine.SessionEngine
import app.fourburners.engine.Simulator
import app.fourburners.model.Status
import app.fourburners.ui.theme.Aqua
import app.fourburners.ui.theme.Background
import app.fourburners.ui.theme.Foreground
import app.fourburners.ui.theme.Muted
import app.fourburners.ui.theme.RecipeColors

@Composable
fun LiveScreen(
    session: app.fourburners.model.Session,
    onDone: (String) -> Unit,
    onStartEarly: () -> Unit,
    onExtend: (String, Int) -> Unit,
    onAcknowledge: (String) -> Unit,
    onUndo: () -> Unit,
    onViewSchedule: () -> Unit,
) {
    var _nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (_nowMs > 0) 1f else 0f, animationSpec = androidx.compose.animation.core.tween(1000), label = ""
    )
    val view = SessionEngine.view(session, _nowMs)
    val projected = SessionEngine.project(session, _nowMs)

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        renderNowBlock(session, view, projected, _nowMs, onDone, onExtend)
        renderFreeState(session, view, projected, _nowMs, onStartEarly)
        renderInterruptBar(session, view, onDone)
        renderTimers(session, view, _nowMs)
        renderThenSection(projected, session, _nowMs)
        renderBottomButtons(onViewSchedule)
        renderUndoBar(session, _nowMs, onUndo)
    }
}

@Composable
private fun renderNowBlock(session: app.fourburners.model.Session, view: app.fourburners.engine.LiveState, projected: List<app.fourburners.engine.ProjectedStep>, nowMs: Long, onDone: (String) -> Unit, onExtend: (String, Int) -> Unit) {
    if (view.nowStepId == null) return
    val step = session.plan.steps.first { it.id == view.nowStepId }
    val recipeColor = step.recipe?.let { rId ->
        session.plan.recipes.indexOfFirst { it.id == rId }.takeIf { it >= 0 }?.let { RecipeColors[it % 4] }
    } ?: Foreground

    Column(modifier = Modifier.fillMaxWidth().background(recipeColor).padding(20.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shortName = step.recipe?.let { sid -> session.plan.recipes.find { it.id == sid }?.short ?: "" } ?: ""
            Text(shortName, style = MaterialTheme.typography.labelSmall.copy(color = Background, fontWeight = FontWeight.SemiBold))
            val elapsed = SessionEngine.elapsedSec(session, nowMs)
            val serveIn = projected.lastOrNull()?.let { ps -> (ps.endMs - nowMs) / 1000L } ?: 0L
            Text("${formatTime(elapsed)} · serve in ${if (serveIn > 0) formatTime(serveIn.toInt()) else "done"}", style = MaterialTheme.typography.labelSmall.copy(color = Background))
        }
        Spacer(Modifier.height(8.dp))
        Text(step.title, style = MaterialTheme.typography.displaySmall.copy(color = Background), fontWeight = FontWeight.Bold)
        step.detail?.let { detail ->
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium.copy(color = Background.copy(alpha = 0.8f)))
        }
        if (step.tools.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            val toolNames = step.tools.joinToString(" \u00B7 ") { tid -> session.plan.tools.find { it.id == tid.id }?.name ?: tid.id }
            Text(toolNames, style = MaterialTheme.typography.bodySmall.copy(color = Background.copy(alpha = 0.7f)))
        }
        Spacer(Modifier.height(12.dp))
        val secLeft = if (step.kind.name == "passive") {
            ((Simulator.endMs(session, step) - nowMs) / 1000L).coerceAtLeast(0L).toInt()
        } else {
            val elapsed = SessionEngine.elapsedSec(session, nowMs)
            elapsed - step.plannedStartSec - step.durationSec
        }
        if (secLeft > 0 && secLeft < 60) {
            Text("$secLeft s left", style = MaterialTheme.typography.displayLarge.copy(color = Background, fontWeight = FontWeight.Bold))
        } else if (secLeft <= 0) {
            Text("Done!", style = MaterialTheme.typography.headlineMedium.copy(color = Background, fontWeight = FontWeight.Bold))
        } else {
            Text("${secLeft / 60} min left", style = MaterialTheme.typography.displayLarge.copy(color = Background, fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onExtend(step.id, 60) }, modifier = Modifier.width(96.dp).height(76.dp)) { Text("+1 min") }
            Button(onClick = { onDone(step.id) }, modifier = Modifier.weight(1f).height(76.dp)) {
                Text("Done", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun renderFreeState(session: app.fourburners.model.Session, view: app.fourburners.engine.LiveState, projected: List<app.fourburners.engine.ProjectedStep>, nowMs: Long, onStartEarly: () -> Unit) {
    if (view.nowStepId != null || view.finished) return
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = CardDefaults.outlinedShape) {
        Column(modifier = Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nothing needs you", style = MaterialTheme.typography.headlineSmall.copy(color = Muted))
            view.free?.nextStepId?.let { nextId ->
                val nextStep = session.plan.steps.first { it.id == nextId }
                val projNext = projected.find { it.stepId == nextId }
                if (projNext != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(formatTime(((projNext.startMs - nowMs) / 1000L).toInt().coerceAtLeast(0)), style = MaterialTheme.typography.displayLarge.copy(color = Aqua, fontWeight = FontWeight.Bold))
                    Text("next: ${nextStep.title}", style = MaterialTheme.typography.bodyMedium.copy(color = Muted))
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onStartEarly, modifier = Modifier.height(76.dp)) { Text("Start next early") }
        }
    }
}

@Composable
private fun renderInterruptBar(session: app.fourburners.model.Session, view: app.fourburners.engine.LiveState, onDone: (String) -> Unit) {
    if (view.interruptIds.isEmpty()) return
    view.interruptIds.forEach { intId ->
        val intStep = session.plan.steps.first { it.id == intId }
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(64.dp), shape = CardDefaults.outlinedShape) {
            Row(modifier = Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(intStep.title, style = MaterialTheme.typography.bodyMedium.copy(color = Foreground))
                Spacer(Modifier.weight(1f))
                Button(onClick = { onDone(intId) }, modifier = Modifier.width(64.dp).height(56.dp)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun renderTimers(session: app.fourburners.model.Session, view: app.fourburners.engine.LiveState, nowMs: Long) {
    if (view.timerIds.isEmpty()) return
    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        view.timerIds.forEach { timerId ->
            val timerStep = session.plan.steps.first { it.id == timerId }
            val endMs = Simulator.endMs(session, timerStep)
            val remaining = ((endMs - nowMs) / 1000L).coerceAtLeast(0L).toInt()
            val recipeColor = timerStep.recipe?.let { rId ->
                session.plan.recipes.indexOfFirst { it.id == rId }.takeIf { it >= 0 }?.let { RecipeColors[it % 4] }
            } ?: Aqua
            Card(modifier = Modifier.fillMaxWidth(), shape = CardDefaults.outlinedShape) {
                Row(modifier = Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(4.dp).clip(androidx.compose.foundation.shape.CircleShape).background(recipeColor))
                    Spacer(Modifier.width(8.dp))
                    val recipeName = timerStep.recipe?.let { sid -> session.plan.recipes.find { it.id == sid }?.short ?: "" }
                    Text("$recipeName \u00B7 ${timerStep.timerLabel ?: "timer"}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = Foreground))
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(remaining), style = MaterialTheme.typography.headlineLarge.copy(color = Foreground, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
private fun renderThenSection(projected: List<app.fourburners.engine.ProjectedStep>, session: app.fourburners.model.Session, nowMs: Long) {
    val pendingSteps = projected.filter { ps -> session.steps[ps.stepId]?.status != Status.DONE }.take(2)
    if (pendingSteps.isEmpty()) return
    Column(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp)) {
        Text("THEN", style = MaterialTheme.typography.labelMedium.copy(color = Muted, fontWeight = FontWeight.SemiBold))
        pendingSteps.forEach { ps ->
            val pStep = session.plan.steps.first { it.id == ps.stepId }
            val startSec = ((ps.startMs - nowMs) / 1000L).toInt().coerceAtLeast(0)
            Text("${formatTime(startSec)} ${pStep.title}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = Foreground))
        }
    }
}

@Composable
private fun renderBottomButtons(onViewSchedule: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {}, modifier = Modifier.weight(1f).height(56.dp)) { Text("Adjust") }
        OutlinedButton(onClick = {}, modifier = Modifier.weight(1f).height(56.dp)) { Text("Kitchen") }
        Button(onClick = onViewSchedule, modifier = Modifier.weight(1f).height(56.dp)) { Text("Full schedule") }
    }
}

@Composable
private fun renderUndoBar(session: app.fourburners.model.Session, nowMs: Long, onUndo: () -> Unit) {
    if (!SessionEngine.canUndo(session, nowMs)) return
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp)) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Marked done", style = MaterialTheme.typography.bodyMedium.copy(color = Muted))
            Spacer(Modifier.weight(1f))
            Button(onClick = onUndo, modifier = Modifier.height(56.dp).width(56.dp)) { Text("U") }
        }
    }
}
