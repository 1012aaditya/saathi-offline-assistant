package app.saathi.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.saathi.MainActivity
import app.saathi.safety.Action
import app.saathi.safety.Apps
import app.saathi.safety.Back
import app.saathi.safety.Bounds
import app.saathi.safety.Direction
import app.saathi.safety.Element
import app.saathi.safety.Home
import app.saathi.safety.OpenApp
import app.saathi.safety.Point
import app.saathi.safety.Screen
import app.saathi.safety.Scroll
import app.saathi.safety.Search
import app.saathi.safety.Tap
import app.saathi.safety.TypeText
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Reads the active window into the safety [Screen] and performs actions via
 * AccessibilityNodeInfo (works with TalkBack on, so TalkBack is never toggled). The raw actuator
 * is private and handed only to [DeviceBridge], where every action is gated first.
 *
 * Also: Volume-down long-press from ANY app = talk to Saathi (the app under it is read first, for
 * "what's on screen"); a short press still lowers the volume.
 *
 * Logs never contain screen or message text: only step kinds and counts.
 */
class SaathiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SaathiA11y"
        private const val LONG_PRESS_MS = 600L
        private const val MAX_NODES = 1500
        private const val MAX_DEPTH = 60
        private const val SENSITIVE_NODES = 800

        @Volatile
        var running: SaathiAccessibilityService? = null
            private set

        /** True when the owner switched Saathi on in Android's Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, SaathiAccessibilityService::class.java)
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val on = list.split(':').any { ComponentName.unflattenFromString(it) == me }
            return on || running != null
        }

        /** Android's Accessibility settings, where the owner switches Saathi on (never done for them). */
        fun openSettings(context: Context) {
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var volumeDown = false
    private var longFired = false
    private val longPress = Runnable { onTalkKey() }

    /** The latest task snapshot: its node handles back Tap / TypeText by element index. */
    @Volatile
    private var last: Snapshot? = null

    private var down = false

    /** Floating Saathi: the bubble + panel this service draws over other apps (null when not connected). */
    private var overlay: app.saathi.overlay.OverlayManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
        down = false
        DeviceBridge.attach(actuator)
        Log.i(TAG, "connected")
        overlay = app.saathi.overlay.OverlayManager(this).also { it.start() }
        // Remove a draft an earlier run typed but couldn't remove (service restart, process death).
        TaskRunner.onServiceConnected(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /**
     * Unbind / destroy (also when `uiautomator dump` suspends accessibility services). The screen
     * can't be read or acted on any more, so a running task stops here; a draft it typed stays in
     * the write-ahead record and is removed when the service connects again (TaskRunner).
     */
    private fun shutdown() {
        if (down) return
        down = true
        Log.i(TAG, "disconnected")
        TaskRunner.onServiceLost()
        if (running === this) running = null
        DeviceBridge.detach(actuator)
        main.removeCallbacks(longPress)
        overlay?.stop()
        overlay = null
        last = null
    }

    /** Screens are read on demand; events only tell Floating Saathi to look at what's in front (throttled). */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The overlay's own redraws don't change what's in front.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && event.packageName?.toString() == packageName) return
        overlay?.onScreenEvent()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onInterrupt() = Unit

    // -- Volume-down push-to-talk ----------------------------------------------------------------

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (!volumeDown) {
                    volumeDown = true
                    longFired = false
                    main.postDelayed(longPress, LONG_PRESS_MS)
                }
            }
            KeyEvent.ACTION_UP -> {
                volumeDown = false
                main.removeCallbacks(longPress)
                if (!longFired) {
                    // Short press: behave like a normal volume key.
                    val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                    audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
                }
            }
        }
        return true
    }

    private fun onTalkKey() {
        longFired = true
        // Floating Saathi on and another app in front: talk right here (the overlay), no app switch.
        if (overlay?.onTalkKey() == true) return
        // A running task stops at once (Stop); then Saathi listens. In guide mode the key is how the
        // user talks to the guide: the session keeps going and the Guide screen listens.
        if (TaskRunner.isRunning && !TaskRunner.isGuiding) TaskRunner.stop(TaskRunner.StopReason.TALK_KEY)
        TaskRunner.captured = runCatching { captureForeground() }.getOrNull()
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_TALK, true)
        runCatching { startActivity(i) }
    }

    /** The app under Saathi when the talk key was held (null if it was Saathi itself). */
    private fun captureForeground(): TaskRunner.Captured? {
        val w = appWindow() ?: return null
        val root = w.root ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == packageName) return null
        val snap = Normalizer.normalize(listOf(raw(root, 0, intArrayOf(MAX_NODES))), pkg, keyboardVisible())
        val label = knownLabel(pkg) ?: w.title?.toString()?.takeIf { it.isNotBlank() && it.length < 40 } ?: appLabel(pkg)
        Log.i(TAG, "captured screen: ${snap.screen.elements.size} elements")
        return TaskRunner.Captured(snap.screen, label, System.currentTimeMillis())
    }

    fun appLabel(pkg: String): String = knownLabel(pkg) ?: runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("this app")

    private fun knownLabel(pkg: String): String? = when (pkg) {
        Apps.WHATSAPP -> "WhatsApp"
        Apps.GPAY -> "Google Pay"
        Apps.BLINKIT -> "Blinkit"
        Apps.SETTINGS_APP -> "Settings"
        else -> null
    }

    // -- reading the screen ----------------------------------------------------------------------

    private fun appWindow(): AccessibilityWindowInfo? {
        val apps = runCatching { windows }.getOrDefault(emptyList()).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused } ?: apps.firstOrNull()
    }

    private fun keyboardVisible(): Boolean =
        runCatching { windows }.getOrDefault(emptyList()).any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    /** The front app's root. Never Saathi's own overlay windows (bubble / panel): they are not an app. */
    private fun currentRoot(): AccessibilityNodeInfo? = appWindow()?.root
        ?: rootInActiveWindow?.takeIf { it.window?.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }

    // -- for Floating Saathi (read-only, no screen text leaves this class) --------------------------

    /** The package of the app in front (null if none readable). */
    fun frontPackageName(): String? = frontPackage()

    /**
     * One screenshot of the screen for "what does this picture say?" (Android 11+), with Saathi's
     * own bubble and panel hidden for it and the status bar cut off (notifications can show
     * message text). Null when not possible. The caller checks the app and the screen first.
     */
    suspend fun screenshot(): android.graphics.Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val take: suspend () -> android.graphics.Bitmap? = { capture()?.let(::withoutStatusBar) }
        return overlay?.hidden(take) ?: take()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun capture(): android.graphics.Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val bmp = runCatching {
                        android.graphics.Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    hb.close()
                    if (cont.isActive) cont.resume(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "screenshot failed: $errorCode")
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
    }

    private fun withoutStatusBar(b: android.graphics.Bitmap): android.graphics.Bitmap {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val top = (if (id > 0) resources.getDimensionPixelSize(id) else 0).coerceIn(0, b.height / 4)
        if (top == 0) return b
        return android.graphics.Bitmap.createBitmap(b, 0, top, b.width, b.height - top).also { if (it !== b) b.recycle() }
    }

    fun isKeyboardVisible(): Boolean = keyboardVisible()

    /**
     * The app in front shows a password field or is a PIN screen (the gate's R8 check): Floating
     * Saathi hides itself there. Reads flags and texts in memory only; nothing is logged or kept.
     */
    fun frontIsSensitive(): Boolean {
        val roots = appRoots()
        val pkg = roots.firstOrNull()?.packageName?.toString() ?: return false
        val els = mutableListOf<Element>()
        val queue = ArrayDeque(roots)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < SENSITIVE_NODES) {
            val n = queue.removeFirst()
            if (n.isPassword && n.isVisibleToUser) return true
            val text = if (n.isPassword) "" else Normalizer.clean(n.text?.toString())
            val desc = Normalizer.clean(n.contentDescription?.toString())
            if (text.isNotEmpty() || desc.isNotEmpty()) {
                els += Element(els.size, "", text, desc, "", false, n.isEditable, false, Bounds(0, 0, 0, 0), Point(0, 0))
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return DeviceBridge.gate.isPinScreen(Screen(pkg, els, "floating"))
    }

    /**
     * Every window of the app in front, left to right. On tablets in landscape WhatsApp shows the
     * chat list and the open chat as two side-by-side activities (two windows); the active one alone
     * can be just the "No chat selected" pane (seen on the owner's Tab S6 Lite, 13 Oct).
     */
    private fun appRoots(): List<AccessibilityNodeInfo> {
        val active = currentRoot() ?: return emptyList()
        val pkg = active.packageName?.toString() ?: return listOf(active)
        val roots = runCatching { windows }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { w -> w.root?.takeIf { it.packageName?.toString() == pkg }?.let { w to it } }
            .sortedBy { (w, _) -> Rect().also(w::getBoundsInScreen).left }
            .map { it.second }
        return roots.ifEmpty { listOf(active) }
    }

    /** Node -> RawNode. A password field's text is never read (hard rule 2). */
    private fun raw(n: AccessibilityNodeInfo, depth: Int, budget: IntArray): RawNode {
        val r = Rect()
        n.getBoundsInScreen(r)
        val pwd = n.isPassword
        val kids = mutableListOf<RawNode>()
        if (depth < MAX_DEPTH) {
            for (i in 0 until n.childCount) {
                if (budget[0]-- <= 0) break
                val c = n.getChild(i) ?: continue
                kids += raw(c, depth + 1, budget)
            }
        }
        if (n.isEditable && app.saathi.BuildConfigFlags.debuggable(this)) {
            // Debug builds only, lengths/flags only (never content): diagnose hint-vs-draft detection.
            val tx = n.text?.toString().orEmpty()
            val ht = n.hintText?.toString().orEmpty()
            Log.d(TAG, "edit id=${n.viewIdResourceName} textLen=${if (pwd) -1 else tx.length} hintLen=${ht.length} showingHint=${n.isShowingHintText} textEqHint=${tx.isNotEmpty() && tx == ht}")
        }
        return RawNode(
            className = n.className?.toString(),
            text = if (pwd) null else n.text?.toString(),
            desc = n.contentDescription?.toString(),
            hint = n.hintText?.toString(),
            resourceId = n.viewIdResourceName,
            bounds = Bounds(r.left, r.top, r.right, r.bottom),
            clickable = n.isClickable,
            editable = n.isEditable,
            password = pwd,
            visible = n.isVisibleToUser,
            showingHint = n.isShowingHintText,
            children = kids,
            handle = n,
        )
    }

    private fun read(): Snapshot? {
        val root = currentRoot() ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        val budget = intArrayOf(MAX_NODES)
        val snap = Normalizer.normalize(appRoots().map { raw(it, 0, budget) }, pkg, keyboardVisible())
        // Blinkit hides its search box from accessibility: surface the focused field (order apps only).
        if (pkg !in Apps.ORDER_APPS) return snap
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() ?: return snap
        return HiddenField.surface(snap, rawFocused(focused))
    }

    /** The focused input as a RawNode with zero bounds (it is typed into, never tapped). Never a password's text. */
    private fun rawFocused(n: AccessibilityNodeInfo): RawNode = RawNode(
        className = n.className?.toString(),
        text = if (n.isPassword) null else n.text?.toString(),
        desc = n.contentDescription?.toString(),
        hint = n.hintText?.toString(),
        resourceId = n.viewIdResourceName,
        bounds = Bounds(0, 0, 0, 0),
        editable = n.isEditable,
        password = n.isPassword,
        showingHint = n.isShowingHintText,
        handle = n,
    )

    // -- acting (raw; reachable only through DeviceBridge's gate) ----------------------------------

    private val actuator = object : Actuator {
        override suspend fun snapshot(): Screen? = read()?.also { last = it }?.screen

        override suspend fun perform(action: Action, screen: Screen): Boolean {
            val ok = runCatching { performRaw(action, screen) }.getOrDefault(false)
            Log.i(TAG, "action ${action.type}: ${if (ok) "ok" else "failed"}")
            return ok
        }

        override suspend fun clearText(element: Element, screen: Screen): Boolean {
            val node = handleFor(screen, element.index) ?: return false
            node.refresh()
            if (node.isPassword || !node.isEditable) return false
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        /** The field's text as the app holds it (line breaks kept). Read only; never a password field. */
        override suspend fun fieldText(element: Element, screen: Screen): String? {
            val node = handleFor(screen, element.index) ?: return null
            node.refresh()
            if (node.isPassword || !node.isEditable) return null
            if (node.isShowingHintText) return ""
            return node.text?.toString()
        }

        override suspend fun waitForChange(oldHash: String, timeoutMs: Long): Boolean {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                delay(250)
                val now = read() ?: continue // not stored: `last` stays the snapshot actions refer to
                if (now.screen.hash != oldHash) return true
            }
            return false
        }
    }

    /** Node handles are only valid for the exact snapshot they came from. */
    private fun handleFor(screen: Screen, index: Int): AccessibilityNodeInfo? {
        val snap = last ?: return null
        if (snap.screen !== screen) return null
        return snap.handles.getOrNull(index) as? AccessibilityNodeInfo
    }

    /**
     * The app actually in front right now. Saathi's own sheets come forward while a task waits for
     * the user, so an action planned on another app's screen must never land on Saathi's UI: only the
     * user answers Saathi's questions (13 Oct: a Blinkit item pick was registered with no human tap).
     */
    private fun frontPackage(): String? = runCatching { currentRoot()?.packageName?.toString() }.getOrNull()

    private suspend fun performRaw(action: Action, screen: Screen): Boolean {
        if (action !is OpenApp && action !is Search && action !is Back && action !is Home) {
            val front = frontPackage()
            if (front == packageName || front != screen.packageName) {
                Log.w(TAG, "refused ${action.type}: front app changed since the screen was read (own=${front == packageName})")
                return false
            }
        }
        if ((action is Back || action is Home) && frontPackage() == packageName) {
            Log.w(TAG, "refused ${action.type}: Saathi itself is in front")
            return false
        }
        return performRawChecked(action, screen)
    }

    private suspend fun performRawChecked(action: Action, screen: Screen): Boolean = when (action) {
        is Tap -> tap(screen, action.index)
        is TypeText -> type(screen, action)
        is Scroll -> scroll(action.direction)
        is Back -> performGlobalAction(GLOBAL_ACTION_BACK)
        is Home -> performGlobalAction(GLOBAL_ACTION_HOME)
        is OpenApp -> openApp(action.app)
        is Search -> search(action.query)
        else -> false // search / look / ask_user / done / fail are not device actions here
    }

    private suspend fun tap(screen: Screen, index: Int): Boolean {
        val node = handleFor(screen, index) ?: return false
        if (!node.refresh()) return false
        var target: AccessibilityNodeInfo? = node
        var depth = 0
        while (target != null && !target.isClickable && depth < 6) {
            target = target.parent
            depth++
        }
        if (target != null && target.isClickable) {
            if (target.packageName?.toString() == packageName) {
                Log.w(TAG, "refused click on Saathi's own UI")
                return false
            }
            if (target != node && !DeviceBridge.allowsParentTap(asElement(target), screen.packageName)) return false
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return tapAt(screen.elements[index].center)
    }

    private fun asElement(n: AccessibilityNodeInfo): Element {
        val r = Rect()
        n.getBoundsInScreen(r)
        return Element(
            index = 0,
            cls = n.className?.toString().orEmpty().substringAfterLast('.'),
            text = if (n.isPassword) "" else Normalizer.clean(n.text?.toString()),
            desc = Normalizer.clean(n.contentDescription?.toString()),
            rid = Normalizer.shortRid(n.viewIdResourceName),
            clickable = n.isClickable,
            editable = n.isEditable,
            password = n.isPassword,
            bounds = Bounds(r.left, r.top, r.right, r.bottom),
            center = Point(r.centerX(), r.centerY()),
        )
    }

    private fun type(screen: Screen, action: TypeText): Boolean {
        val node = action.index?.let { handleFor(screen, it) }
            ?: (if (action.index == null) currentRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) else null)
            ?: return false
        node.refresh()
        if (node.isPassword || !node.isEditable) return false // never type into secret fields (R7)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text) }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val target = action.index?.let { screen.elements.getOrNull(it) }
        if (ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && HiddenField.submitsWithEnter(screen.packageName, target)) {
            // Blinkit only shows results after Enter (device.py did the same). Never in WhatsApp: Enter can send.
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return ok
    }

    /** Blinkit search results via its deep link (the prototype's `am start -a VIEW -d <url> <blinkit>`). */
    private fun search(query: String): Boolean {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(Blinkit.searchUrl(query)))
            .setPackage(Apps.BLINKIT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(i) }.isSuccess
    }

    private suspend fun scroll(direction: Direction): Boolean {
        val root = currentRoot() ?: return false
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen++ < MAX_NODES) {
            val n = queue.removeFirst()
            if (n.isScrollable && n.isVisibleToUser) {
                val r = Rect()
                n.getBoundsInScreen(r)
                val area = r.width().toLong() * r.height()
                if (area > bestArea) { best = n; bestArea = area }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        val op = if (direction == Direction.DOWN) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (best?.performAction(op) == true) return true
        val r = Rect()
        (best ?: root).getBoundsInScreen(r)
        val x = r.centerX().toFloat()
        val top = r.top + r.height() * 0.3f
        val bottom = r.top + r.height() * 0.75f
        return if (direction == Direction.DOWN) swipe(x, bottom, x, top) else swipe(x, top, x, bottom)
    }

    private fun openApp(alias: String): Boolean {
        val pkg = Apps.APP_ALIASES[alias.lowercase().trim()] ?: return false
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(intent) }.isSuccess
    }

    private suspend fun tapAt(p: Point): Boolean {
        if (frontPackage() == packageName) {
            Log.w(TAG, "refused coordinate tap: Saathi itself is in front")
            return false
        }
        return throughOverlay(listOf(p)) { gesture(Path().apply { moveTo(p.x.toFloat(), p.y.toFloat()) }, 60) }
    }

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean =
        throughOverlay(listOf(Point(x1.toInt(), y1.toInt()), Point(x2.toInt(), y2.toInt()))) {
            gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 400)
        }

    /** An injected touch must never land on Saathi's own overlay: it passes through it to the app. */
    private suspend fun throughOverlay(path: List<Point>, block: suspend () -> Boolean): Boolean =
        overlay?.passThrough(path, block) ?: block()

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val sent = dispatchGesture(
            g,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            },
            main,
        )
        if (!sent && cont.isActive) cont.resume(false)
    }
}
