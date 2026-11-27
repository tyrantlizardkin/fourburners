package app.fourburners

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import app.fourburners.data.SessionStore
import app.fourburners.ui.AppViewModel
import app.fourburners.ui.Screen
import app.fourburners.ui.screens.DoneScreen
import app.fourburners.ui.screens.EmptyScreen
import app.fourburners.ui.screens.ImportScreen
import app.fourburners.ui.screens.LiveScreen
import app.fourburners.ui.screens.ScheduleScreen
import app.fourburners.ui.theme.FourBurnersTheme

class MainActivity : ComponentActivity() {

    private lateinit var store: SessionStore
    private lateinit var viewModel: AppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        viewModel = AppViewModel(store)
        handleIntent(intent)
        setContent {
            FourBurnersTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        when (state.screen) {
                            Screen.Empty -> EmptyScreen(
                                onPastePlan = { handleIntent(intent) },
                                onLoadSample = { viewModel.loadSample(this@MainActivity) },
                            )
                            Screen.Import -> ImportScreen(
                                plan = state.plan,
                                errors = state.errors,
                                onStartCooking = { viewModel.startCooking() },
                                onCancel = { viewModel.navigate(Screen.Empty) },
                                onPasteCorrected = { handleIntent(intent) },
                            )
                            Screen.Live -> state.session?.let { session ->
                                LiveScreen(
                                    session = session,
                                    onDone = { viewModel.done(it) },
                                    onStartEarly = { viewModel.startEarly() },
                                    onExtend = { id, sec -> viewModel.extend(id, sec) },
                                    onAcknowledge = { viewModel.acknowledge(it) },
                                    onUndo = { viewModel.undo() },
                                    onViewSchedule = { viewModel.navigate(Screen.Schedule) },
                                )
                            }
                            Screen.Schedule -> state.session?.let { session ->
                                ScheduleScreen(
                                    session = session,
                                    onBack = { viewModel.navigate(Screen.Live) },
                                )
                            }
                            Screen.Done -> {
                                val doneSession = state.session ?: store.load()
                                if (doneSession != null) {
                                    DoneScreen(
                                        session = doneSession,
                                        onClose = {
                                            viewModel.onSessionFinished()
                                            viewModel.navigate(Screen.Empty)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            text?.let { viewModel.importText(it) }
        }
    }
}
