package app.saathi.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var setupDone by mutableStateOf(prefs.getBoolean("setupDone", false)); private set
    var theme by mutableStateOf(ThemeChoice.valueOf(prefs.getString("theme", ThemeChoice.System.name)!!)); private set
    var soundOn by mutableStateOf(prefs.getBoolean("soundOn", true)); private set
    /** null = follow the phone's language. */
    var languageTag by mutableStateOf(prefs.getString("languageTag", null)); private set
    var speechRate by mutableStateOf(prefs.getFloat("speechRate", 1.0f)); private set
    var spendingLimit by mutableStateOf(prefs.getInt("spendingLimit", 500)); private set
    var walkSensitivity by mutableStateOf(WalkSensitivity.valueOf(prefs.getString("walk", WalkSensitivity.Medium.name)!!)); private set
    /** Braille: play a tone on raised dots. null = automatic (sound only when the phone can't vibrate). */
    var brailleSound by mutableStateOf(if (prefs.contains("brailleSound")) prefs.getBoolean("brailleSound", false) else null); private set
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

    /** Result -> Braille: public text to feel (already read aloud). Memory only; never an OTP or PIN. */
    var brailleText by mutableStateOf<String?>(null); internal set

    /** Volume-down long-press anywhere -> open Listening. Collected by the nav host. */
    val talkRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val talk: SharedFlow<Unit> get() = talkRequests

    /** Volume-down held before the nav host was listening (cold start): open Listening once ready. */
    @Volatile var pendingTalk = false

    fun requestTalk() {
        if (talkRequests.subscriptionCount.value == 0 || !talkRequests.tryEmit(Unit)) pendingTalk = true
    }

    fun finishSetup() { setupDone = true; prefs.edit().putBoolean("setupDone", true).apply() }
    fun updateTheme(t: ThemeChoice) { theme = t; prefs.edit().putString("theme", t.name).apply() }
    fun updateSound(on: Boolean) { soundOn = on; prefs.edit().putBoolean("soundOn", on).apply() }
    fun updateLanguage(tag: String?) { languageTag = tag; prefs.edit().putString("languageTag", tag).apply() }
    fun updateSpeechRate(r: Float) { speechRate = r.coerceIn(0.7f, 1.5f); prefs.edit().putFloat("speechRate", speechRate).apply() }
    fun updateWalkSensitivity(s: WalkSensitivity) { walkSensitivity = s; prefs.edit().putString("walk", s.name).apply() }
    fun updateBrailleSound(on: Boolean) { brailleSound = on; prefs.edit().putBoolean("brailleSound", on).apply() }

    /** Braille reading style: rhythm (dots played in time) or touch (slide a finger over one cell). */
    var brailleRhythm by mutableStateOf(prefs.getBoolean("brailleRhythm", true)); private set
    /** Say each word after its rhythm (learning aid). Never for private text. */
    var brailleSpeak by mutableStateOf(prefs.getBoolean("brailleSpeak", true)); private set
    /** Rhythm speed, adapted to the reader (Pace.MIN..Pace.MAX). */
    var braillePace by mutableFloatStateOf(prefs.getFloat("braillePace", 1f)); private set
    /** Finished Learn-braille lessons (1-based). */
    val brailleLessons = mutableStateListOf<Int>().apply {
        addAll(prefs.getString("brailleLessons", "")!!.split(',').mapNotNull { it.toIntOrNull() })
    }
    fun updateBrailleRhythm(on: Boolean) { brailleRhythm = on; prefs.edit().putBoolean("brailleRhythm", on).apply() }
    fun updateBrailleSpeak(on: Boolean) { brailleSpeak = on; prefs.edit().putBoolean("brailleSpeak", on).apply() }
    fun updateBraillePace(p: Float) { braillePace = p; prefs.edit().putFloat("braillePace", p).apply() }
    /** Learn braille: recent right/wrong answers per letter or quiz item (Mastery string). */
    var brailleMastery by mutableStateOf(prefs.getString("brailleMastery", "")!!); private set
    fun updateBrailleMastery(v: String) { brailleMastery = v; prefs.edit().putString("brailleMastery", v).apply() }
    fun finishBrailleLesson(n: Int) {
        if (n in brailleLessons) return
        brailleLessons.add(n)
        prefs.edit().putString("brailleLessons", brailleLessons.joinToString(",")).apply()
    }

    /** Only ever called after the user confirmed on a sheet whose default is Cancel. */
    fun updateSpendingLimit(v: Int) { spendingLimit = v; prefs.edit().putInt("spendingLimit", v).apply() }

    /**
     * Settings > Money > "Allow placing orders". Default OFF (like ALLOW_ORDERS=false in the
     * prototype): Blinkit tasks stop at the read-back. Turning it on needs a confirmation sheet
     * (Cancel first); even then every order needs the read-back and a clear yes.
     */
    var allowOrders by mutableStateOf(prefs.getBoolean("allowOrders", false)); private set
    fun updateAllowOrders(on: Boolean) { allowOrders = on; prefs.edit().putBoolean("allowOrders", on).apply() }

    /**
     * Settings > "Floating Saathi" (default ON): the round Saathi bubble over other apps, drawn by
     * Saathi's accessibility service. The service watches this preference ([FLOATING_KEY]).
     */
    var floatingSaathi by mutableStateOf(prefs.getBoolean(FLOATING_KEY, true)); private set
    fun updateFloatingSaathi(on: Boolean) { floatingSaathi = on; prefs.edit().putBoolean(FLOATING_KEY, on).apply() }

    /**
     * Settings > "Listen after Saathi speaks" (owner request 14 Oct). Default OFF: guide mode is
     * push-to-talk. On: after each guide turn, once Saathi has finished speaking, the mic opens by
     * itself ([app.saathi.agent.AutoListen]). The session's echo checks and the Confirm sheet stay.
     */
    var listenAfterSpeaking by mutableStateOf(prefs.getBoolean("listenAfterSpeaking", false)); private set

    /** Settings > "Understand Hindi speech": listen in hi-IN (takes Hinglish too); Saathi's own voice is unchanged. */
    var hindiSpeech by mutableStateOf(prefs.getBoolean("hindiSpeech", false)); private set
    fun updateHindiSpeech(on: Boolean) { hindiSpeech = on; prefs.edit().putBoolean("hindiSpeech", on).apply() }
    fun updateListenAfterSpeaking(on: Boolean) { listenAfterSpeaking = on; prefs.edit().putBoolean("listenAfterSpeaking", on).apply() }

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
        allowOrders = false; floatingSaathi = true; listenAfterSpeaking = false; hindiSpeech = false
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

    companion object {
        const val PREFS = "saathi"
        const val FLOATING_KEY = "floatingSaathi"

        /** Read without an AppState (the accessibility service). */
        fun floatingEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(FLOATING_KEY, true)
    }
}
