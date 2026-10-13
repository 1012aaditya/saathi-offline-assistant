package app.saathi.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pending typed drafts on the phone (app-private SharedPreferences), so a draft Saathi typed is
 * remembered across a service restart or process death and removed later. Holds the chat title,
 * SHA-256 hashes, lengths and what Saathi did (typed / replaced / added): never message text.
 * Written synchronously (commit) because the record must exist BEFORE the text is typed.
 *
 * The user's own earlier text (when Saathi replaced or added to it) is kept in process memory
 * only, never written here: after process death a REPLACE can't be undone exactly and the user is
 * told to retype it; an ADD still can (the original is the start of the box, proven by its hash).
 */
class PrefsDraftStore(context: Context) : DraftStore {
    private val prefs = context.applicationContext.getSharedPreferences("saathi_drafts", Context.MODE_PRIVATE)
    private val lock = Any()
    private val originals = Originals() // memory only

    override fun all(): List<PendingDraft> = synchronized(lock) { read() }

    override fun put(draft: PendingDraft) = synchronized(lock) {
        write(read().filterNot { it.sameAs(draft) } + draft)
    }

    override fun remove(draft: PendingDraft) = synchronized(lock) {
        write(read().filterNot { it.sameAs(draft) })
        originals.remove(draft)
    }

    override fun rememberOriginal(draft: PendingDraft, text: String) = originals.put(draft, text)

    override fun original(draft: PendingDraft): String? = originals.get(draft)

    private fun read(): List<PendingDraft> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PendingDraft(
                chat = o.getString("chat"),
                hash = o.getString("hash"),
                length = o.getInt("len"),
                attempts = o.optInt("attempts", 0),
                mode = runCatching { DraftMode.valueOf(o.optString("mode", "NEW")) }.getOrDefault(DraftMode.NEW),
                originalHash = o.optString("origHash", ""),
                originalLength = o.optInt("origLen", 0),
            )
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<PendingDraft>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("chat", it.chat).put("hash", it.hash).put("len", it.length).put("attempts", it.attempts)
                    .put("mode", it.mode.name).put("origHash", it.originalHash).put("origLen", it.originalLength),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).commit()
    }

    private companion object {
        const val KEY = "pending"
    }
}
