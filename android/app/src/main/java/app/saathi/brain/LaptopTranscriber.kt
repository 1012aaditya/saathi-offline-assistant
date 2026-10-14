package app.saathi.brain

import android.util.Base64
import android.util.Log
import app.saathi.voice.Transcriber
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Demo builds: speech to text on the laptop bridge (scripts/openai_bridge.py → OpenAI), reached
 * like [LaptopBrain] at 127.0.0.1 through `adb reverse`; available whenever the laptop answered
 * its last check. The recording is sent once and never stored or logged here.
 */
object LaptopTranscriber : Transcriber {
    private const val TAG = "SaathiLaptop"
    private const val URL_STT = "http://127.0.0.1:11434/api/transcribe"

    override val available: Boolean get() = LaptopBrain.ready

    override fun transcribe(wav: ByteArray): String? {
        val t0 = System.currentTimeMillis()
        val body = JSONObject().put("audio_b64", Base64.encodeToString(wav, Base64.NO_WRAP)).toString().toByteArray()
        val c = (URL(URL_STT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 1_500
            readTimeout = 12_000
            requestMethod = "POST"
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            c.outputStream.use { it.write(body) }
            if (c.responseCode != 200) return null
            JSONObject(c.inputStream.bufferedReader().readText()).optString("text")
                .trim().trimEnd('.', '!', '?', '।', ',').trim() // "Search for milk." -> the rules want plain words
                .takeIf { it.isNotBlank() }
                .also { Log.i(TAG, "speech to text in ${System.currentTimeMillis() - t0} ms") }
        } catch (e: Exception) {
            Log.w(TAG, "speech to text failed: ${e::class.simpleName}")
            null
        } finally {
            c.disconnect()
        }
    }
}
