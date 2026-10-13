package app.saathi.agent

import app.saathi.safety.Apps
import app.saathi.safety.CommandContext
import app.saathi.safety.payeeMatches
import java.text.Normalizer as TextNormalizer

/*
 * "Open ANY app" ("open camera", "Instagram kholo", "कैमरा खोलो"), offline.
 *
 * The safety gate's OpenApp check allows only Apps.APP_ALIASES (WhatsApp, Google Pay, Blinkit,
 * Settings) and is NOT changed. Any other app goes through this separate, explicit path:
 *  - [AppResolver]: the spoken name -> one of the phone's launcher apps (label, aliases, Hindi).
 *  - [LaunchPolicy]: may Saathi open it? Saathi itself no; payment apps never while Payments is
 *    Off; an app the user set to "Not allowed" never; an app Saathi hasn't opened before only
 *    after the user's own yes (remembered per app).
 *  - The app layer then ONLY starts the app's launcher intent. No taps, no typing, no reading
 *    beyond the read-only "what's on screen?". Saathi can't operate unknown apps offline yet.
 */

/** One app on the phone's launcher (from a MAIN/LAUNCHER intent query, not QUERY_ALL_PACKAGES). */
data class LaunchableApp(val label: String, val packageName: String)

object AppResolver {
    sealed interface Result {
        data class One(val app: LaunchableApp) : Result
        data class Many(val apps: List<LaunchableApp>) : Result
        data object None : Result
    }

    /** Spoken Hindi / Hinglish / short names -> the word the app's label uses. Keys are normalised. */
    private val WORDS: Map<String, String> = mapOf(
        "कैमरा" to "camera", "कैमरे" to "camera", "kamera" to "camera", "cam" to "camera",
        "गैलरी" to "gallery", "गेलरी" to "gallery", "galary" to "gallery", "gallary" to "gallery",
        "फोटो" to "photos", "फोटोज" to "photos", "फोटोस" to "photos", "photo" to "photos", "pictures" to "photos",
        "pics" to "photos", "images" to "photos", "तस्वीरें" to "photos", "तस्वीर" to "photos", "tasveer" to "photos",
        "यूट्यूब" to "youtube", "यूटूब" to "youtube", "yt" to "youtube", "you tube" to "youtube",
        "इंस्टाग्राम" to "instagram", "इंस्टा" to "instagram", "insta" to "instagram",
        "फेसबुक" to "facebook", "fb" to "facebook",
        "क्रोम" to "chrome", "गूगल" to "google", "जीमेल" to "gmail", "ईमेल" to "gmail",
        "मैप्स" to "maps", "मैप" to "maps", "नक्शा" to "maps", "map" to "maps", "google maps" to "maps",
        "कैलकुलेटर" to "calculator", "calc" to "calculator", "कैलेंडर" to "calendar",
        "घड़ी" to "clock", "घडी" to "clock", "अलार्म" to "clock", "alarm" to "clock",
        "फोन" to "phone", "dialer" to "phone", "डायलर" to "phone",
        "कॉन्टैक्ट्स" to "contacts", "कॉन्टैक्ट" to "contacts", "संपर्क" to "contacts",
        "म्यूजिक" to "music", "गाने" to "music", "गाना" to "music",
        "स्पॉटिफाई" to "spotify", "टेलीग्राम" to "telegram", "स्नैपचैट" to "snapchat",
        "अमेज़न" to "amazon", "अमेजन" to "amazon", "अमेज़ॅन" to "amazon", "फ्लिपकार्ट" to "flipkart",
        "जोमैटो" to "zomato", "ज़ोमैटो" to "zomato", "स्विगी" to "swiggy", "जेप्टो" to "zepto",
        "उबर" to "uber", "ओला" to "ola", "रैपिडो" to "rapido",
        "पेटीएम" to "paytm", "फोनपे" to "phonepe", "phone pe" to "phonepe", "भीम" to "bhim",
        "प्ले स्टोर" to "play store", "playstore" to "play store", "फाइल्स" to "files", "फाइलें" to "files",
        "नोट्स" to "notes", "रिकॉर्डर" to "recorder", "रेडियो" to "radio", "टॉर्च" to "torch",
    ).mapKeys { norm(it.key) }

    /** Kinds of app every phone has under a maker-specific name / package. */
    private class Kind(val words: Set<String>, val labels: Set<String>, val packages: Set<String>)

    private val KINDS = listOf(
        Kind(
            words = setOf("gallery", "photos", "album", "albums"),
            labels = setOf("gallery", "photos", "google photos", "album", "albums", "photo gallery"),
            packages = setOf(
                "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.miui.gallery", "com.android.gallery3d",
                "com.coloros.gallery3d", "com.oneplus.gallery", "com.vivo.gallery", "com.motorola.gallery", "com.android.gallery",
                "com.google.android.apps.photosgo", "com.transsion.gallery", "com.realme.gallery",
            ),
        ),
        Kind(
            words = setOf("camera"),
            labels = setOf("camera", "google camera"),
            packages = setOf(
                "com.android.camera", "com.android.camera2", "com.sec.android.app.camera", "com.google.android.GoogleCamera",
                "com.oneplus.camera", "com.motorola.camera3", "com.motorola.camera2", "org.codeaurora.snapcam", "com.oppo.camera",
                "com.vivo.camera", "com.huawei.camera", "com.transsion.camera", "com.mediatek.camera",
            ),
        ),
    )

    /** Lowercase, Unicode NFC, Devanagari nukta dropped, single spaces, no "app" at the end. */
    fun norm(s: String): String =
        TextNormalizer.normalize(s.lowercase(), TextNormalizer.Form.NFC)
            .replace("़", "")
            .replace(Regex("[\\s_\\-]+"), " ")
            .trim()
            .removeSuffix(" app")
            .removeSuffix(" ऐप")
            .trim()

    /** The spoken app name in the words app labels use (English; Devanagari mapped / transliterated). */
    fun key(spoken: String): String {
        val n = norm(spoken)
        if (n.isEmpty()) return ""
        WORDS[n]?.let { return it }
        val mapped = n.split(' ').joinToString(" ") { w -> WORDS[w] ?: w }
        return if (mapped.any { it in 'ऀ'..'ॿ' }) Names.transliterate(mapped).trim() else mapped
    }

    /**
     * The installed app the user means. Exact label first ("YouTube"), then the gallery / camera
     * kinds ("photos" -> the phone's gallery app), then the safety port's fuzzy matcher
     * (token_set_ratio >= 80: "maps" -> "Google Maps"). Several -> [Result.Many] (ask, never guess).
     */
    fun resolve(spoken: String, installed: List<LaunchableApp>, ownPackage: String = ""): Result {
        val apps = installed.filter { it.packageName != ownPackage && it.label.isNotBlank() }.distinctBy { it.packageName }
        val k = key(spoken)
        if (k.isEmpty() || apps.isEmpty()) return Result.None
        val raw = norm(spoken)
        oneOrMany(apps.filter { norm(it.label) == k || norm(it.label) == raw })?.let { return it }
        KINDS.firstOrNull { k in it.words }?.let { kind ->
            oneOrMany(apps.filter { it.packageName in kind.packages || norm(it.label) in kind.labels })?.let { return it }
        }
        val ctx = CommandContext(command = "", names = listOf(k))
        return oneOrMany(apps.filter { payeeMatches(ctx, norm(it.label)) }) ?: Result.None
    }

    private fun oneOrMany(list: List<LaunchableApp>): Result? = when {
        list.isEmpty() -> null
        list.size == 1 -> Result.One(list.first())
        else -> Result.Many(list.sortedBy { it.label.length }.take(4))
    }
}

/** The user's per-app answer, remembered on the phone (Settings > Apps shows and changes it). */
enum class Approval { ALLOWED, NOT_ALLOWED }

sealed interface LaunchDecision {
    /** Approved before: start its launcher intent. */
    data object Launch : LaunchDecision

    /** Never opened by Saathi: ask the user first ("Open it now?"); only their yes opens it. */
    data object AskFirst : LaunchDecision

    /** One of the apps Saathi operates (WhatsApp, Google Pay, Blinkit, Settings): use [Command.OpenApp] and its level. */
    data class Managed(val alias: String) : LaunchDecision

    data class Refuse(val reason: RefuseReason) : LaunchDecision
}

enum class RefuseReason { SAATHI_ITSELF, NOT_ALLOWED, PAYMENTS_OFF, GROCERY_OFF }

object LaunchPolicy {
    /** UPI / wallet / banking apps beyond the safety list. Opened only while Payments isn't Off. */
    val EXTRA_PAYMENT_APPS: Set<String> = setOf(
        "com.mobikwik_new", "com.freecharge.android", "com.dreamplug.androidapp", "money.super.payments",
        "com.sbi.lotusintouch", "com.sbi.upi", "com.csam.icici.bank.imobile", "com.snapwork.hdfc", "com.msf.kbank.mobile",
        "com.axis.mobile", "com.bankofbaroda.upi", "com.infrasofttech.indianBank", "com.whatsapp.w4b.payments",
        "in.amazon.mShop.android.shopping.pay", "com.myairtelapp", "com.fampay.in", "com.navi.app",
    )

    private val PAYMENT_LABEL = Regex("(?i)(\\bpay\\b|^pay|pay$|\\bupi\\b|bank|wallet|\\bbhim\\b|paytm|phonepe|पे$|बैंक)")

    /** Payment apps: the safety list, more UPI / banking packages, and pay / bank / wallet labels. Errs towards "payment". */
    fun isPaymentApp(packageName: String, label: String): Boolean =
        packageName in Apps.PAYMENT_APPS || packageName in EXTRA_PAYMENT_APPS || PAYMENT_LABEL.containsMatchIn(label)

    /**
     * Whether Saathi may open [app] now. Checked when asked AND again right before launching.
     * Payments Off / Grocery Off / Not allowed always win over an earlier approval.
     */
    fun decide(
        app: LaunchableApp,
        approval: Approval?,
        paymentsOff: Boolean,
        groceryOff: Boolean,
        ownPackage: String,
    ): LaunchDecision {
        if (app.packageName == ownPackage) return LaunchDecision.Refuse(RefuseReason.SAATHI_ITSELF)
        if (isPaymentApp(app.packageName, app.label) && paymentsOff) return LaunchDecision.Refuse(RefuseReason.PAYMENTS_OFF)
        if (app.packageName in Apps.ORDER_APPS && groceryOff) return LaunchDecision.Refuse(RefuseReason.GROCERY_OFF)
        Apps.APP_ALIASES.entries.firstOrNull { it.value == app.packageName }?.let { return LaunchDecision.Managed(it.key) }
        return when (approval) {
            Approval.NOT_ALLOWED -> LaunchDecision.Refuse(RefuseReason.NOT_ALLOWED)
            Approval.ALLOWED -> LaunchDecision.Launch
            null -> LaunchDecision.AskFirst
        }
    }
}
