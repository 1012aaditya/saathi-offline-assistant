package app.saathi.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.saathi.ui.theme.ThemeChoice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import org.json.JSONObject

/** Per-app permission level (Settings > Apps). Payments default Off. */
enum class AppLevel(val label: String) { ReadOnly("Read only"), AskFirst("Ask first"), Off("Off") }

enum class Outcome(val label: String) {
    Done("Done"), Cancelled("Cancelled"), StoppedBeforeOrder("Stopped before ordering"),
    HandedOver("Handed to you"), Failed("Didn't work"),
}

data class HistoryItem(val title: String, val outcome: Outcome, val timeMillis: Long)

enum class WalkSensitivity { Low, Medium, High }

/**
 * Everything Saathi remembers. Stored only on the phone (SharedPreferences), never sent anywhere.
 * Compose reads the `by mutableStateOf` fields directly, so screens update when they change.
 */
class AppState(context: Context) {
    private val prefs = context.getSharedPreferences("saathi", Context.MODE_PRIVATE)

    var setupDone by mutableStateOf(prefs.getBoolean("setupDone", false)); private set
    var theme by mutableStateOf(ThemeChoice.valueOf(prefs.getString("theme", ThemeChoice.System.name)!!)); private set
    var soundOn by mutableStateOf(prefs.getBoolean("soundOn", true)); private set
    /** null = follow the phone's language. */
    var languageTag by mutableStateOf(prefs.getString("languageTag", null)); private set
    var speechRate by mutableStateOf(prefs.getFloat("speechRate", 1.0f)); private set
    var spendingLimit by mutableStateOf(prefs.getInt("spendingLimit", 500)); private set
    var walkSensitivity by mutableStateOf(WalkSensitivity.valueOf(prefs.getString("walk", WalkSensitivity.Medium.name)!!)); private set
    val appLevels = mutableStateListOf<Pair<String, AppLevel>>().apply {
        addAll(
            listOf("WhatsApp" to AppLevel.AskFirst, "Grocery app" to AppLevel.AskFirst, "Payments" to AppLevel.Off).map { (app, def) ->
                app to AppLevel.valueOf(prefs.getString("level.$app", def.name)!!)
            },
        )
    }
    val trustedContacts = mutableStateListOf<String>().apply {
        addAll(prefs.getString("contacts", null)?.split('\n')?.filter { it.isNotBlank() } ?: listOf("Mom", "Dad"))
    }
    val history = mutableStateListOf<HistoryItem>().apply { addAll(loadHistory()) }

    /** The last thing the user said (Listening -> Working). */
    var lastRequest by mutableStateOf(""); internal set

    /** Volume-down long-press anywhere -> open Listening. Collected by the nav host. */
    val talkRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val talk: SharedFlow<Unit> get() = talkRequests

    fun finishSetup() { setupDone = true; prefs.edit().putBoolean("setupDone", true).apply() }
    fun updateTheme(t: ThemeChoice) { theme = t; prefs.edit().putString("theme", t.name).apply() }
    fun updateSound(on: Boolean) { soundOn = on; prefs.edit().putBoolean("soundOn", on).apply() }
    fun updateLanguage(tag: String?) { languageTag = tag; prefs.edit().putString("languageTag", tag).apply() }
    fun updateSpeechRate(r: Float) { speechRate = r.coerceIn(0.7f, 1.5f); prefs.edit().putFloat("speechRate", speechRate).apply() }
    fun updateWalkSensitivity(s: WalkSensitivity) { walkSensitivity = s; prefs.edit().putString("walk", s.name).apply() }

    /** Only ever called after the user confirmed on a sheet whose default is Cancel. */
    fun updateSpendingLimit(v: Int) { spendingLimit = v; prefs.edit().putInt("spendingLimit", v).apply() }

    fun updateAppLevel(app: String, level: AppLevel) {
        val i = appLevels.indexOfFirst { it.first == app }
        if (i >= 0) appLevels[i] = app to level
        prefs.edit().putString("level.$app", level.name).apply()
    }

    fun addContact(name: String) {
        if (name.isBlank() || name in trustedContacts) return
        trustedContacts.add(name.trim())
        prefs.edit().putString("contacts", trustedContacts.joinToString("\n")).apply()
    }

    fun removeContact(name: String) {
        trustedContacts.remove(name)
        prefs.edit().putString("contacts", trustedContacts.joinToString("\n")).apply()
    }

    fun record(title: String, outcome: Outcome) {
        history.add(0, HistoryItem(title, outcome, System.currentTimeMillis()))
        saveHistory()
    }

    fun clearHistory() { history.clear(); saveHistory() }

    /** Settings > Privacy > Delete all data. */
    fun deleteAll() {
        prefs.edit().clear().apply()
        history.clear()
        trustedContacts.clear(); trustedContacts.addAll(listOf("Mom", "Dad"))
        setupDone = false; theme = ThemeChoice.System; soundOn = true; languageTag = null
        speechRate = 1.0f; spendingLimit = 500; walkSensitivity = WalkSensitivity.Medium
        listOf("WhatsApp" to AppLevel.AskFirst, "Grocery app" to AppLevel.AskFirst, "Payments" to AppLevel.Off)
            .forEach { (a, l) -> updateAppLevel(a, l) }
    }

    private fun loadHistory(): List<HistoryItem> = runCatching {
        val arr = JSONArray(prefs.getString("history", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HistoryItem(o.getString("t"), Outcome.valueOf(o.getString("o")), o.getLong("m"))
        }
    }.getOrDefault(emptyList())

    private fun saveHistory() {
        val arr = JSONArray()
        history.take(200).forEach { arr.put(JSONObject().put("t", it.title).put("o", it.outcome.name).put("m", it.timeMillis)) }
        prefs.edit().putString("history", arr.toString()).apply()
    }
}
