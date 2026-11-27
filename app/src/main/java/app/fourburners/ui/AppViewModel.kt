package app.fourburners.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fourburners.data.SessionStore
import app.fourburners.engine.SessionEngine
import app.fourburners.model.Plan
import app.fourburners.model.Session
import app.fourburners.parse.ParseResult
import app.fourburners.parse.PlanParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Screen {
    object Empty : Screen
    object Import : Screen
    object Live : Screen
    object Schedule : Screen
    object Done : Screen
}

data class UiState(
    val screen: Screen = Screen.Empty,
    val plan: Plan? = null,
    val errors: List<app.fourburners.parse.PlanError> = emptyList(),
    val session: Session? = null,
)

class AppViewModel(
    private val store: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (store.hasSession()) {
                store.load()?.let { session ->
                    _state.update { it.copy(session = session) }
                    _state.update { it.copy(screen = Screen.Live) }
                    return@launch
                }
            }
            _state.update { it.copy(screen = Screen.Empty) }
        }
    }

    fun onSessionFinished() {
        store.clear()
        _state.update { s -> s.copy(session = null, screen = Screen.Done) }
    }

    fun importText(text: String) {
        when (val result = PlanParser.parse(text)) {
            is ParseResult.Ok -> {
                _state.update { s -> s.copy(plan = result.plan, errors = emptyList(), screen = Screen.Import) }
            }
            is ParseResult.Failed -> {
                _state.update { s -> s.copy(plan = null, errors = result.errors, screen = Screen.Import) }
            }
        }
    }

    fun loadSample(context: android.content.Context) {
        val text = context.assets.open("sample-plan.json").bufferedReader().use { it.readText() }
        importText(text)
    }

    fun startCooking() {
        val plan = _state.value.plan ?: return
        val session = SessionEngine.start(plan, System.currentTimeMillis())
        store.save(session)
        _state.update { s -> s.copy(session = session, screen = Screen.Live) }
    }

    fun done(stepId: String) {
        val s = _state.value.session ?: return
        val session = SessionEngine.done(s, stepId, System.currentTimeMillis())
        store.save(session)
        _state.update { it.copy(session = session) }
        checkFinished(session)
    }

    fun startEarly() {
        val s = _state.value.session ?: return
        val session = SessionEngine.startEarly(s, System.currentTimeMillis())
        store.save(session)
        _state.update { it.copy(session = session) }
        checkFinished(session)
    }

    fun extend(stepId: String, sec: Int) {
        val s = _state.value.session ?: return
        val session = SessionEngine.extend(s, stepId, sec, System.currentTimeMillis())
        store.save(session)
        _state.update { it.copy(session = session) }
        checkFinished(session)
    }

    fun acknowledge(stepId: String) {
        val s = _state.value.session ?: return
        val session = SessionEngine.acknowledge(s, stepId, System.currentTimeMillis())
        store.save(session)
        _state.update { it.copy(session = session) }
    }

    fun undo() {
        val s = _state.value.session ?: return
        val session = SessionEngine.undo(s, System.currentTimeMillis())
        store.save(session)
        _state.update { it.copy(session = session) }
    }

    private var _showClockTimes = false
    val showClockTimes: Boolean get() = _showClockTimes
    fun toggleClockTimes() { _showClockTimes = !_showClockTimes }

    private fun checkFinished(session: Session) {
        if (SessionEngine.view(session, System.currentTimeMillis()).finished) {
            store.clear()
            _state.update { s -> s.copy(session = null, screen = Screen.Done) }
        }
    }

    fun navigate(screen: Screen) {
        _state.update { it.copy(screen = screen) }
    }
}
