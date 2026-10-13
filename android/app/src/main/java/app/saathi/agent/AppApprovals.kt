package app.saathi.agent

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which other apps the user let Saathi open ("Saathi hasn't opened Instagram before. Open it
 * now?" -> yes = Allowed). Settings > Apps lists them with Allow / Not allowed; a Not allowed app
 * is never opened. Stored only on the phone (package, label, answer). A "no" to the question is
 * not remembered: Saathi simply asks again next time.
 */
object AppApprovals {
    private const val PREFS = "saathi_apps"
    private const val KEY = "approvals"

    data class Entry(val packageName: String, val label: String, val approval: Approval)

    /** Compose reads this list directly (Settings > Apps). */
    val entries = mutableStateListOf<Entry>()

    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
            val list = runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Entry(o.getString("p"), o.getString("l"), Approval.valueOf(o.getString("a")))
                }
            }.getOrDefault(emptyList())
            entries.clear()
            entries.addAll(list)
            loaded = true
        }
    }

    fun get(context: Context, packageName: String): Approval? {
        load(context)
        return entries.firstOrNull { it.packageName == packageName }?.approval
    }

    fun set(context: Context, packageName: String, label: String, approval: Approval) {
        load(context)
        val i = entries.indexOfFirst { it.packageName == packageName }
        val e = Entry(packageName, label, approval)
        if (i >= 0) entries[i] = e else entries.add(e)
        save(context)
    }

    /** Settings > Privacy > Delete all data. */
    fun clear(context: Context) {
        load(context)
        entries.clear()
        save(context)
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("p", it.packageName).put("l", it.label).put("a", it.approval.name)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
