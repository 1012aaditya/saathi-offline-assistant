package app.saathi.overlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import app.saathi.MainActivity
import app.saathi.agent.Command
import app.saathi.agent.CommandParser
import app.saathi.agent.Destination
import app.saathi.agent.Flows
import app.saathi.agent.Lang
import app.saathi.agent.PhoneBook
import app.saathi.agent.PhotoSource
import app.saathi.agent.Requests
import app.saathi.agent.SaathiAccessibilityService
import app.saathi.agent.TaskRunner
import app.saathi.agent.Texts
import app.saathi.brain.LaptopBrain
import app.saathi.brain.LaptopVision
import app.saathi.feedback.Cue
import app.saathi.photo.Describe
import app.saathi.photo.PhotoEngine
import app.saathi.photo.PhotoLibrary
import app.saathi.photo.toUprightBitmap
import app.saathi.ui.Deps
import app.saathi.ui.HandOff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The bubble's home panel (owner, 14 Oct: "use everything from the bubble"): what the user says
 * there, or the big buttons, go to the right place without opening Saathi:
 *  - "help me here" / "guide me" → guide mode on the app in front (or "guide me in Blinkit": opened once);
 *  - "walk mode" → [FloatingWalk]; "stop" → stops walking / the task;
 *  - "what's in front" / "describe my last photo" → camera / gallery, described (laptop vision in demo
 *    builds, else the on-phone object list);
 *  - messages, replies, orders → the same scripted tasks, run from the panel (TaskRunner floating);
 *  - anything that needs Saathi's own screens (approving a new app, Settings …) → [HandOff].
 * Every action still goes through the same parser, flows and safety gate as Saathi's screens.
 */
internal class FloatingHome(
    private val service: SaathiAccessibilityService,
    private val deps: Deps,
    private val walk: FloatingWalk,
    private val scope: CoroutineScope,
    /** Start a guide session on the app in front (the overlay's own START press). */
    private val helpHere: () -> Unit,
) {
    private companion object {
        const val TAG = "SaathiFloatingHome"
        const val CAMERA_TIMEOUT_MS = 6_000L
    }

    private val _line = MutableStateFlow<String?>(null)

    /** Saathi's last words on the home / walk panel (shown big; also spoken). */
    val line: StateFlow<String?> = _line.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** Looking at a picture (camera / photo): the panel shows "Looking…". */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var lookJob: Job? = null

    private fun lang(): Lang = Requests.lang(deps)

    fun say(text: String) {
        _line.value = text
        deps.voice.speak(text)
    }

    fun clear() {
        _line.value = null
    }

    // -- what was said ----------------------------------------------------------------------------------

    fun handle(text: String) {
        val t = Texts(lang())
        val people = PhoneBook.names(service)
        val command = CommandParser.parse(text, TaskRunner.session, deps.state.trustedContacts.toList() + people)
        Log.i(TAG, "home panel: ${command::class.simpleName}") // never the words
        when (command) {
            is Command.Stop -> stopAll()
            is Command.Help -> say(app.saathi.agent.FloatingTexts.HOME_HINT)
            is Command.Guide -> if (command.app == null) {
                helpHere()
            } else {
                startTask(command, t) // opens the app once; the session then moves to this panel
            }
            is Command.Navigate -> when (command.target) {
                Destination.WALK -> startWalk()
                else -> handOff(text)
            }
            is Command.Photo -> when (command.source) {
                PhotoSource.CAMERA -> look()
                else -> describeLastPhoto()
            }
            is Command.LaunchApp -> handOff(text) // the approval sheet is on Saathi's screen
            is Command.Unsupported -> say("Sorry, I didn't get that. ${app.saathi.agent.FloatingTexts.HOME_HINT}")
            else -> startTask(command, t)
        }
    }

    private fun startTask(command: Command, t: Texts) {
        Flows.missing(command, t)?.let { question ->
            if (command is Command.Reply && command.contact != null) TaskRunner.session.pendingReplyTo = command.contact
            say(question)
            return
        }
        Requests.levelRefusal(command, deps, t)?.let { say(it); return }
        if (walk.walking.value) walk.stop()
        deps.voice.stopSpeaking()
        clear()
        val ok = TaskRunner.start(service, command, lang(), deps.state, floating = command !is Command.Guide) { line -> deps.voice.speak(line) }
        if (!ok) say(if (TaskRunner.isRunning) "Saathi is busy with another task. Say stop first." else t.serviceOff)
    }

    private fun handOff(text: String) {
        say("Opening Saathi for that.")
        HandOff.post(text)
        runCatching {
            service.startActivity(
                Intent(service, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    /** The "Read messages" button: who sent new WhatsApp messages (the same task as saying it). */
    fun readMessages() = startTask(Command.UnreadSummary("read my new messages"), Texts(lang()))

    /** The "Help me here" button. */
    fun helpHereNow() = helpHere()

    fun stopAll() {
        lookJob?.cancel()
        _busy.value = false
        when {
            walk.walking.value -> walk.stop()
            TaskRunner.isRunning -> TaskRunner.stop()
            else -> say("Okay.")
        }
    }

    // -- walk -------------------------------------------------------------------------------------------

    fun startWalk() {
        if (TaskRunner.isRunning) {
            say("Saathi is busy with a task. Say stop first, then walk.")
            return
        }
        clear()
        walk.start()
    }

    // -- looking ----------------------------------------------------------------------------------------

    /** "What's in front?": one picture from the back camera (or the walk camera), described. */
    fun look() {
        if (_busy.value) return
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            say("I need the camera for this. Open Saathi's Photo screen once and allow it.")
            return
        }
        lookJob = scope.launch {
            _busy.value = true
            try {
                deps.feedback.cue(Cue.Tick)
                say(if (lang() == Lang.HI) "देख रहा हूँ।" else "Looking.")
                val pic = withContext(Dispatchers.IO) { walk.frame() } ?: oneShot()
                if (pic == null) {
                    say("I couldn't use the camera just now. Try again.")
                    return@launch
                }
                say(describe(pic, LaptopVision.Kind.SCENE, live = true))
                deps.feedback.cue(Cue.Done)
            } finally {
                _busy.value = false
            }
        }
    }

    /** "Describe my last photo": the newest photo, described (never a screenshot to the laptop). */
    fun describeLastPhoto() {
        if (_busy.value) return
        if (PhotoLibrary.access(service) == PhotoLibrary.Access.None) {
            say("I need your photos for this. Open Saathi's Photo screen once and allow it.")
            return
        }
        lookJob = scope.launch {
            _busy.value = true
            try {
                val item = withContext(Dispatchers.IO) { PhotoLibrary.item(service, 0) }
                val photo = item?.let { withContext(Dispatchers.IO) { PhotoLibrary.load(service, it) } }
                if (item == null || photo == null) {
                    say("I couldn't find a photo.")
                    return@launch
                }
                say(if (lang() == Lang.HI) "देख रहा हूँ।" else "Looking.")
                val head = Describe.photoHeader(
                    0, item.takenMillis, System.currentTimeMillis(), java.time.ZoneId.systemDefault(), lang(),
                    screenshot = item.screenshot, limited = PhotoLibrary.access(service) == PhotoLibrary.Access.Limited,
                )
                say(head + " " + describe(photo, LaptopVision.Kind.PHOTO, live = false, mayUseLaptop = !item.screenshot))
                deps.feedback.cue(Cue.Done)
            } finally {
                _busy.value = false
            }
        }
    }

    /** The laptop's vision model in demo builds (it may be wrong: said so), else the on-phone object list. */
    private suspend fun describe(pic: Bitmap, kind: LaptopVision.Kind, live: Boolean, mayUseLaptop: Boolean = true): String {
        if (mayUseLaptop && LaptopBrain.usable(service)) {
            LaptopVision.describe(pic, kind, lang())?.let { return it + if (lang() == Lang.HI) " यह पूरी तरह सही न भी हो।" else " It may not be exact." }
        }
        return PhotoEngine(service).use { engine ->
            val a = engine.analyse(pic)
            Describe.describe(a.found, a.quality, lang(), live = live)
        }
    }

    /** One picture from the back camera, without a preview (the bubble is over another app). */
    private suspend fun oneShot(): Bitmap? = withContext(Dispatchers.Main) {
        val owner = OverlayOwner().also { it.create() }
        try {
            val provider = suspendCancellableCoroutine<ProcessCameraProvider?> { cont ->
                val f = ProcessCameraProvider.getInstance(service)
                f.addListener({ if (cont.isActive) cont.resume(runCatching { f.get() }.getOrNull()) }, ContextCompat.getMainExecutor(service))
            } ?: return@withContext null
            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            runCatching { provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, capture) }
                .onFailure { Log.w(TAG, "camera from the bubble failed: ${it::class.simpleName}"); return@withContext null }
            try {
                withTimeoutOrNull(CAMERA_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Bitmap?> { cont ->
                        capture.takePicture(
                            ContextCompat.getMainExecutor(service),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val b = runCatching { image.toUprightBitmap() }.getOrNull()
                                    image.close()
                                    if (cont.isActive) cont.resume(b)
                                }

                                override fun onError(e: ImageCaptureException) {
                                    Log.w(TAG, "capture failed: ${e.imageCaptureError}")
                                    if (cont.isActive) cont.resume(null)
                                }
                            },
                        )
                    }
                }
            } finally {
                runCatching { provider.unbind(capture) }
            }
        } finally {
            owner.destroy()
        }
    }
}
