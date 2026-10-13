package app.saathi.brain

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import app.saathi.agent.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Demo mode (owner, 14 Oct: "can we also run a vision model"): a small vision model on the owner's
 * laptop (Ollama `qwen3-vl:2b-instruct`) describes a picture in plain words. Same link and limits
 * as [LaptopBrain]: debug builds only, 127.0.0.1 through `adb reverse`, nothing to the internet.
 *
 * Callers decide WHAT may be sent (never screenshots from the gallery, never private apps, never
 * PIN / password / protected screens). The answer is only spoken: it never picks a button or an
 * item. Never logs pictures or answers.
 */
object LaptopVision {
    private const val TAG = "SaathiVision"
    private const val BASE = "http://127.0.0.1:11434"
    const val MODEL = "qwen3-vl:2b-instruct"

    /** The first answer loads the model on the laptop (~16 s); later ones take 1–3 s. */
    private const val TIMEOUT_MS = 30_000
    private const val MAX_SIDE = 896
    private const val MAX_CHARS = 420

    enum class Kind { SCENE, PHOTO, SCREEN }

    private fun prompt(kind: Kind, lang: Lang): String {
        val what = when (kind) {
            Kind.SCENE -> "This is what the back camera of a blind person's phone sees right now. Describe what is in front of them: the main things, where they are (left, right, ahead, near), and any text you can read."
            Kind.PHOTO -> "This is a photo from a blind person's gallery. Describe it: who or what is in it, the setting, and any text you can read."
            Kind.SCREEN -> "This is a phone app screen. A blind person asked what the pictures on it show. Describe the pictures, banners and offers, and read any text inside them."
        }
        val language = if (lang == Lang.HI) "simple Hindi written in Devanagari" else "simple English"
        return "$what Answer in $language, in at most three short sentences, like a helpful friend. " +
            "Never say who a person is, and never guess names; just say \"a person\". " +
            "Never read out passwords, PINs or codes. If you are not sure, say so. No lists, no markdown."
    }

    /** A spoken description, or null (laptop not there, too slow, or nothing usable). */
    suspend fun describe(picture: Bitmap, kind: Kind, lang: Lang): String? {
        val t0 = System.currentTimeMillis()
        val text = runCatching { runInterruptible(Dispatchers.IO) { ask(picture, kind, lang) } }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "laptop vision failed: ${e::class.simpleName}")
            }.getOrNull()
        Log.i(TAG, if (text == null) "no description" else "described ($kind) in ${System.currentTimeMillis() - t0} ms")
        return text
    }

    private fun ask(picture: Bitmap, kind: Kind, lang: Lang): String? {
        val body = JSONObject()
            .put("model", MODEL)
            .put("stream", false)
            .put("keep_alive", "10m")
            .put("options", JSONObject().put("temperature", 0.2).put("num_predict", 160))
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt(kind, lang)).put("images", JSONArray().put(jpegBase64(picture))),
                ),
            )
        val c = (URL("$BASE/api/chat").openConnection() as HttpURLConnection).apply {
            connectTimeout = 1_500
            readTimeout = TIMEOUT_MS
            requestMethod = "POST"
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) return null
            val raw = JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("message")?.optString("content") ?: return null
            return clean(raw)
        } finally {
            c.disconnect()
        }
    }

    /** Plain spoken text: no markdown, no lists, not too long (cut at a sentence end). */
    internal fun clean(raw: String): String? {
        val t = raw
            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("[*#_`>|]+"), " ")
            .replace(Regex("^\\s*[-•]\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (t.length < 3) return null
        if (t.length <= MAX_CHARS) return t
        val cut = t.take(MAX_CHARS)
        val end = max(cut.lastIndexOf(". "), max(cut.lastIndexOf("। "), cut.lastIndexOf("? ")))
        return if (end > MAX_CHARS / 2) cut.take(end + 1) else "$cut…"
    }

    private fun jpegBase64(src: Bitmap): String {
        val k = MAX_SIDE.toFloat() / max(src.width, src.height)
        val b = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (b !== src) b.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
