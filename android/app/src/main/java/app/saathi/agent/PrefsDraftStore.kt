package app.saathi.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pending typed drafts on the phone (app-private SharedPreferences), so a draft Saathi typed is
 * remembered across a service restart or process death and removed later. Holds the chat title,
 * a SHA-256 hash and the length only: never the message text. Written synchronously (commit)
 * because the record must exist BEFORE the text is typed.
 */
class PrefsDraftStore(context: Context) : DraftStore {
    private val prefs = context.applicationContext.getSharedPreferences("saathi_drafts", Context.MODE_PRIVATE)
    private val lock = Any()

    override fun all(): List<PendingDraft> = synchronized(lock) { read() }

    override fun put(draft: PendingDraft) = synchronized(lock) {
        write(read().filterNot { it.sameAs(draft) } + draft)
    }

    override fun remove(draft: PendingDraft) = synchronized(lock) {
        write(read().filterNot { it.sameAs(draft) })
    }

    private fun read(): List<PendingDraft> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PendingDraft(o.getString("chat"), o.getString("hash"), o.getInt("len"), o.optInt("attempts", 0))
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<PendingDraft>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("chat", it.chat).put("hash", it.hash).put("len", it.length).put("attempts", it.attempts)) }
        prefs.edit().putString(KEY, arr.toString()).commit()
    }

    private companion object {
        const val KEY = "pending"
    }
}
