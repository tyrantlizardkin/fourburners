package app.fourburners.data

import android.content.Context
import app.fourburners.model.Session
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val SESSION_FILE = "session.json"

// Strict JSON: no unknown keys allowed (matches parser settings).
val SessionJson = Json { ignoreUnknownKeys = false; isLenient = true }

class SessionStore(private val context: Context) {

    private var cachedFile: java.io.File? = null

    private fun getFile(): java.io.File {
        if (cachedFile != null) return cachedFile!!
        return context.getFileStreamPath(SESSION_FILE).also { cachedFile = it }
    }

    /** Persist a session. Throws on failure so callers can detect issues. */
    fun save(session: Session) {
        val json = SessionJson.encodeToString(session)
        context.openFileOutput(SESSION_FILE, Context.MODE_PRIVATE).use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        }
    }

    /** Load the last saved session, or null if none exists. */
    fun load(): Session? = try {
        val text = context.openFileInput(SESSION_FILE).bufferedReader().use { it.readText() }
        SessionJson.decodeFromString(text)
    } catch (_: Exception) {
        null
    }

    /** Remove the saved session. */
    fun clear() {
        getFile().delete()
    }

    /** Whether a session is persisted. */
    fun hasSession(): Boolean = try {
        val f = getFile()
        f.exists() && f.length() > 0
    } catch (_: Exception) {
        false
    }
}
