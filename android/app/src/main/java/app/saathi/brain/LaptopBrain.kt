package app.saathi.brain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.saathi.agent.Brain
import app.saathi.agent.BrainPrompt
import app.saathi.safety.Apps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Demo mode (owner, 14 Oct: "use this laptop's resources for the demo"): the guide's smart
 * understanding runs on the owner's laptop (Ollama, a local model) instead of on the tablet, where
 * Gemma 3 1B took ~22 s per answer.
 *
 *  - The tablet reaches it ONLY at 127.0.0.1:11434, which `adb reverse tcp:11434 tcp:11434`
 *    forwards to the laptop over the adb link (scripts/laptop_brain.sh). Nothing goes to the internet.
 *  - Only debug builds can use it: they alone get the INTERNET permission and a network config that
 *    allows plain HTTP to 127.0.0.1 only (src/debug). Release builds have no INTERNET permission.
 *  - Private apps never reach it ([mayRead]): WhatsApp, payments, messages, mail, phone, contacts and
 *    Settings stay on the tablet's rules.
 *  - The model only interprets; GuideBrain still checks its answer and every action goes through the
 *    safety gate. Never logs prompts or answers.
 */
object LaptopBrain : Brain {
    private const val TAG = "SaathiLaptop"
    private const val BASE = "http://127.0.0.1:11434"
    const val MODEL = "qwen3:4b"
    private const val PROBE_TIMEOUT_MS = 1_500

    /** Never sent to the laptop. */
    private val KEEP_ON_PHONE: Set<String> = Apps.PAYMENT_APPS + setOf(
        Apps.WHATSAPP, "com.whatsapp.w4b", Apps.SETTINGS_APP,
        "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.google.android.gm", "com.samsung.android.email.provider",
        "com.google.android.dialer", "com.samsung.android.dialer",
        "com.google.android.contacts", "com.samsung.android.app.contacts",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var reachable = false

    override val ready: Boolean get() = reachable

    /** This build may talk to the laptop (debug builds only: release has no INTERNET permission). */
    fun usable(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    fun mayRead(packageName: String): Boolean = packageName !in KEEP_ON_PHONE

    /** Is the laptop there with the model? In the background; [then] gets the answer. */
    fun probeAsync(then: (Boolean) -> Unit = {}) {
        scope.launch {
            val up = runCatching {
                val c = open("/api/tags", PROBE_TIMEOUT_MS)
                try {
                    if (c.responseCode != 200) return@runCatching false
                    val models = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("models") ?: JSONArray()
                    (0 until models.length()).any { models.getJSONObject(it).optString("name") == MODEL }
                } finally {
                    c.disconnect()
                }
            }.getOrDefault(false)
            if (up != reachable) Log.i(TAG, if (up) "laptop model reachable ($MODEL)" else "laptop model not reachable")
            reachable = up
            then(up)
        }
    }

    override suspend fun complete(prompt: String, timeoutMs: Long): String? {
        if (!reachable) return null
        val t0 = System.currentTimeMillis()
        val text = runCatching { runInterruptible(Dispatchers.IO) { ask(prompt, timeoutMs.toInt()) } }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "laptop answer failed: ${e::class.simpleName}")
                reachable = false
                probeAsync()
            }.getOrNull()
        Log.i(TAG, if (text == null) "no laptop answer" else "laptop answered in ${System.currentTimeMillis() - t0} ms")
        return text
    }

    /**
     * The guide's prompt is written for Gemma (turn markers, and the model turn already opened with
     * [BrainPrompt.PREFILL]); here it goes as one chat message with JSON output, and the answer comes
     * back the way BrainParser expects: the JSON with "intent" first, minus the prefill.
     */
    private fun ask(prompt: String, timeoutMs: Int): String? {
        val content = prompt
            .removeSuffix(BrainPrompt.PREFILL)
            .replace("<start_of_turn>user\n", "")
            .replace("<start_of_turn>model\n", "")
            .replace("<end_of_turn>\n", "")
            .trim()
        val body = JSONObject()
            .put("model", MODEL)
            .put("stream", false)
            .put("think", false)
            .put("format", "json")
            .put("keep_alive", "30m")
            .put("options", JSONObject().put("temperature", 0.2).put("num_predict", 200))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val c = open("/api/chat", timeoutMs)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) return null
            val raw = JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("message")?.optString("content") ?: return null
            val answer = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            // "intent" first (Android's JSONObject keeps insertion order), then the rest.
            val ordered = JSONObject().put("intent", answer.opt("intent") ?: JSONObject.NULL)
            for (k in answer.keys()) if (k != "intent") ordered.put(k, answer.opt(k))
            val compact = ordered.toString()
            return if (compact.startsWith(BrainPrompt.PREFILL)) compact.removePrefix(BrainPrompt.PREFILL) else null
        } finally {
            c.disconnect()
        }
    }

    private fun open(path: String, timeoutMs: Int): HttpURLConnection =
        (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = timeoutMs
            useCaches = false
        }
}
