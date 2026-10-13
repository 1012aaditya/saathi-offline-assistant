package app.saathi.safety

/** Port of the package constants and alias maps in saathi/config.py. */
object Apps {
    const val WHATSAPP = "com.whatsapp"
    const val GPAY = "com.google.android.apps.nbu.paisa.user"
    const val BLINKIT = "com.grofers.customerapp"
    const val SETTINGS_APP = "com.android.settings"

    /** Spoken / typed app names -> package. Keys are lowercase. */
    val APP_ALIASES: Map<String, String> = mapOf(
        "whatsapp" to WHATSAPP,
        "व्हाट्सएप" to WHATSAPP,
        "gpay" to GPAY,
        "google pay" to GPAY,
        "जीपे" to GPAY,
        "blinkit" to BLINKIT,
        "ब्लिंकिट" to BLINKIT,
        "settings" to SETTINGS_APP,
    )

    val PAYMENT_APPS: Set<String> = setOf(
        GPAY,
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp", // BHIM
    )

    val ORDER_APPS: Set<String> = setOf(BLINKIT)
}

/**
 * The config values saathi/safety.py reads, with the same defaults as saathi/config.py
 * (Settings.max_amount_inr = 10, which is also check()'s default cap).
 */
class SafetyConfig(val maxAmountInr: Int = 10) {
    init {
        require(maxAmountInr >= 1) { "maxAmountInr must be at least 1" }
    }

    // Fixed, like the constants in saathi/config.py: callers can't replace or empty these lists,
    // because an empty payment/order list would silently switch off R1, R3, R6 and R8.
    val whatsapp: String get() = Apps.WHATSAPP
    val appAliases: Map<String, String> get() = Apps.APP_ALIASES
    val paymentApps: Set<String> get() = Apps.PAYMENT_APPS
    val orderApps: Set<String> get() = Apps.ORDER_APPS
}
