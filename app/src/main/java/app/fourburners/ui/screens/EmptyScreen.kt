package app.fourburners.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.fourburners.ui.theme.Foreground
import app.fourburners.ui.theme.Muted

@Composable
fun EmptyScreen(
    onPastePlan: () -> Unit,
    onLoadSample: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Branding
            Text(
                text = "Four Burners",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = Foreground,
                ),
                textAlign = TextAlign.Center,
            )

            // Status
            Text(
                text = "No cook in progress",
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = Muted,
                ),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            // Explanation
            Text(
                text = "This app runs a cook plan for two or three recipes at once.\nIt does not make plans.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Muted,
                    textAlign = TextAlign.Center,
                ),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            // How-to
            Text(
                text = "1. Ask Claude for a cook plan\n" +
                        "2. On the reply, tap Share and choose Four Burners\n" +
                        "3. Check the plan here and start cooking",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Muted,
                ),
            )

            Spacer(Modifier.height(24.dp))

            // Buttons
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onPastePlan,
                    modifier = Modifier.width(200.dp),
                ) {
                    Text("Paste plan")
                }

                OutlinedButton(
                    onClick = onLoadSample,
                    modifier = Modifier.width(200.dp),
                ) {
                    Text("Load sample")
                }
            }
        }
    }
}
