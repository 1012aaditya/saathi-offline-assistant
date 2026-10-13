package app.saathi.ui.screens

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.saathi.agent.Command
import app.saathi.agent.CommandParser
import app.saathi.agent.PhotoSource
import app.saathi.agent.Requests
import app.saathi.data.Outcome
import app.saathi.feedback.Cue
import app.saathi.photo.Describe
import app.saathi.photo.PhotoEngine
import app.saathi.photo.PhotoLibrary
import app.saathi.photo.PhotoRequest
import app.saathi.photo.PhotoTexts
import app.saathi.photo.Vocab
import app.saathi.photo.toUprightBitmap
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.components.LargeTitle
import app.saathi.ui.components.NavRow
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.components.TextLink
import app.saathi.ui.theme.Clarity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * Photo help: "Take a photo" / "What's in front of me?" (back camera) and "Describe my last photo"
 * (the phone's newest picture). Both are described on the phone by the walk-mode YOLO models
 * (PhotoEngine) — offline, nothing uploaded, nothing written to disk unless the user says "Save it".
 * The words come from app.saathi.photo.Describe (pure, unit-tested).
 */

private const val TAG = "PhotoScreen"

/** Why the screen is waiting on a permission (shown as a card, spoken once). */
private enum class Notice { CameraWhy, CameraRefused, PhotosWhy, PhotosRefused }

/** Camera objects that must not trigger recomposition. */
private class CameraHolder {
    var provider: ProcessCameraProvider? = null
    var capture: ImageCapture? = null

    /** A spoken request is waiting to run as soon as the camera / permission is ready. */
    var autoPending = false
}

private val SAVE_WORDS = setOf("save", "सेव", "rakho", "rakh", "रखो", "रख")
private val PREVIOUS_WORDS = setOf("previous", "older", "earlier", "pichli", "pichhli", "पिछली", "पुरानी")
private val NEWER_WORDS = setOf("newer", "next", "latest", "newest")
private val TAKE_WORDS = setOf(
    "take", "describe", "another", "capture", "click", "photo", "picture", "snap", "khincho", "kheencho", "khicho",
    "खींचो", "फ़ोटो", "फोटो", "तस्वीर",
)
private val REPEAT_WORDS = setOf("repeat", "again", "dobara", "phir", "दोबारा", "फिर")
private val HOME_WORDS = setOf("home", "होम")

/** One long-lived thread for capture callbacks (never shut down, so a late CameraX callback is never rejected). */
private val captureExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor { Thread(it, "photo-capture") } }

private suspend fun cameraProvider(context: Context): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener(
        { runCatching { future.get() }.onSuccess { cont.resume(it) }.onFailure { cont.resumeWithException(it) } },
        ContextCompat.getMainExecutor(context),
    )
}

/** One still from the back camera, in memory only (never a file). */
private suspend fun takePicture(capture: ImageCapture, executor: Executor): Bitmap = suspendCancellableCoroutine { cont ->
    capture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val r = runCatching { image.toUprightBitmap() }
                image.close()
                r.onSuccess { if (cont.isActive) cont.resume(it) }.onFailure { if (cont.isActive) cont.resumeWithException(it) }
            }

            override fun onError(exception: ImageCaptureException) {
                if (cont.isActive) cont.resumeWithException(exception)
            }
        },
    )
}

@Composable
fun PhotoScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val c = Clarity.colors
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val still = ScreenParts.rememberStill()
    val lang = Requests.lang(deps)
    val texts = PhotoTexts(lang)

    val engine = remember { PhotoEngine(context) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    // The spoken request that opened this screen decides the mode and runs at once.
    val first = remember { PhotoRequest.take() }
    val holder = remember { CameraHolder().also { it.autoPending = first != null } }
    var mode by remember { mutableStateOf(first ?: PhotoSource.CAMERA) }
    var autoKick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf<String?>(null) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var saved by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var cameraOk by remember { mutableStateOf(ScreenParts.hasPermission(context, Manifest.permission.CAMERA)) }
    var captureReady by remember { mutableStateOf(false) }
    var retryLast by remember { mutableIntStateOf(0) }
    val speaking by deps.voice.speaking.collectAsState()

    fun say(text: String) = deps.voice.speak(text)

    fun finish(text: String, ok: Boolean) {
        description = text
        deps.feedback.cue(if (ok) Cue.Done else Cue.Cancelled)
        deps.voice.speak(text)
    }

    fun refuse(n: Notice, text: String) {
        notice = n
        deps.feedback.cue(Cue.Cancelled)
        say(text)
    }

    // ---- permissions: asked only when the user wants this, with the reason said first ----------

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cameraOk = ok
        if (ok) {
            notice = null
            holder.autoPending = true // they asked for a photo: take it once the camera is up
            autoKick++
        } else {
            refuse(Notice.CameraRefused, texts.cameraRefused)
        }
    }
    val photosLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (PhotoLibrary.access(context) != PhotoLibrary.Access.None) {
            notice = null
            retryLast++
        } else {
            refuse(Notice.PhotosRefused, texts.photosRefused)
        }
    }

    fun askForCamera() {
        notice = Notice.CameraWhy
        scope.launch {
            say(texts.cameraWhy)
            ScreenParts.awaitSpeechEnd(deps.voice)
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun askForPhotos() {
        notice = Notice.PhotosWhy
        scope.launch {
            say(texts.photosWhy)
            ScreenParts.awaitSpeechEnd(deps.voice)
            photosLauncher.launch(PhotoLibrary.permissions())
        }
    }

    // ---- actions --------------------------------------------------------------------------------

    fun describeLive() {
        if (busy) return
        if (!cameraOk) return askForCamera()
        val ic = holder.capture
        if (ic == null || !captureReady) return say(texts.cameraStarting)
        busy = true
        deps.voice.stopSpeaking()
        deps.feedback.cue(Cue.Tick) // shutter: soft click + light tap
        deps.feedback.tick()
        scope.launch {
            try {
                val photo = takePicture(ic, captureExecutor)
                shot = photo
                saved = false
                val a = engine.analyse(photo)
                finish(Describe.describe(a.found, a.quality, lang, live = true), a.found.isNotEmpty())
                deps.state.record("Described what's in front", Outcome.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "live photo failed", t)
                finish(if (!engine.ready()) texts.modelsFailed else texts.captureFailed, false)
            } finally {
                busy = false
            }
        }
    }

    fun describeLast(i: Int) {
        if (busy) return
        val access = PhotoLibrary.access(context)
        if (access == PhotoLibrary.Access.None) return askForPhotos()
        busy = true
        deps.voice.stopSpeaking()
        deps.feedback.cue(Cue.Tick)
        scope.launch {
            try {
                val item = withContext(Dispatchers.IO) { PhotoLibrary.item(context, i) }
                if (item == null) {
                    finish(if (i == 0) texts.noPhotos else texts.oldest, false)
                    return@launch
                }
                val photo = withContext(Dispatchers.IO) { PhotoLibrary.load(context, item) }
                index = i
                if (photo == null) {
                    finish(texts.photoUnreadable, false)
                    return@launch
                }
                shot = photo
                val a = engine.analyse(photo)
                val head = Describe.photoHeader(
                    i, item.takenMillis, System.currentTimeMillis(), ZoneId.systemDefault(), lang,
                    screenshot = item.screenshot, limited = access == PhotoLibrary.Access.Limited,
                )
                finish(head + " " + Describe.describe(a.found, a.quality, lang, live = false), a.found.isNotEmpty())
                deps.state.record("Described a photo", Outcome.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "last photo failed", t)
                finish(if (!engine.ready()) texts.modelsFailed else texts.photoUnreadable, false)
            } finally {
                busy = false
            }
        }
    }

    fun repeat() {
        deps.feedback.tick()
        say(description ?: texts.nothingYet)
    }

    fun save() {
        val photo = shot
        when {
            mode != PhotoSource.CAMERA || photo == null -> say(texts.takeFirst)
            saved -> say(texts.alreadySaved)
            Build.VERSION.SDK_INT < 29 -> say(texts.saveNeedsAndroid10)
            else -> scope.launch {
                val uri = withContext(Dispatchers.IO) { PhotoLibrary.save(context, photo) }
                if (uri != null) {
                    saved = true
                    deps.feedback.cue(Cue.Done)
                    say(texts.saved)
                } else {
                    deps.feedback.cue(Cue.Cancelled)
                    say(texts.saveFailed)
                }
            }
        }
    }

    fun switchTo(source: PhotoSource) {
        PhotoRequest.open(source) // picked up below, like a spoken request
    }

    fun home() {
        deps.voice.stopSpeaking()
        nav.home()
    }

    // ---- effects --------------------------------------------------------------------------------

    // A request made while this screen is open ("describe my last photo" said here): switch in place.
    val pending by PhotoRequest.pending.collectAsState()
    LaunchedEffect(pending) {
        val s = PhotoRequest.take() ?: return@LaunchedEffect
        mode = s
        index = 0
        description = null
        notice = null
        shot = null // never offer to "save" a gallery photo as a new one
        saved = false
        holder.autoPending = true
        autoKick++
    }

    // Back camera on while in camera mode (and allowed); off otherwise. Bound to this screen's lifecycle.
    LaunchedEffect(mode, cameraOk) {
        if (mode == PhotoSource.CAMERA && cameraOk) {
            try {
                val provider = cameraProvider(context)
                holder.provider = provider
                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build(),
                    )
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                            .build(),
                    )
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                holder.capture = capture
                captureReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "camera failed", t)
                captureReady = false
                finish(texts.captureFailed, false)
            }
        } else {
            runCatching { holder.provider?.unbindAll() }
            holder.capture = null
            captureReady = false
        }
    }

    // Run the spoken request once its mode is ready (camera bound / permission asked).
    LaunchedEffect(autoKick, mode, captureReady, cameraOk) {
        if (!holder.autoPending) return@LaunchedEffect
        when (mode) {
            PhotoSource.CAMERA -> {
                if (!cameraOk) {
                    holder.autoPending = false
                    askForCamera()
                    return@LaunchedEffect
                }
                if (!captureReady) return@LaunchedEffect // wait for the camera
                holder.autoPending = false
                say(texts.pointCamera)
                ScreenParts.awaitSpeechEnd(deps.voice)
                delay(600) // a moment to hold the phone up
                describeLive()
            }
            PhotoSource.LAST_PHOTO -> {
                holder.autoPending = false
                describeLast(0)
            }
        }
    }

    LaunchedEffect(retryLast) { if (retryLast > 0) describeLast(index) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { holder.provider?.unbindAll() }
            engine.close()
            deps.voice.stopSpeaking()
        }
    }

    // ---- voice ----------------------------------------------------------------------------------

    val listener = ScreenParts.rememberScreenListener { text ->
        val w = ScreenParts.words(text)
        val cmd = CommandParser.parse(text)
        when {
            w.any { it in SAVE_WORDS } -> save()
            w.any { it in PREVIOUS_WORDS } ->
                if (mode == PhotoSource.LAST_PHOTO) describeLast(index + 1) else switchTo(PhotoSource.LAST_PHOTO)
            mode == PhotoSource.LAST_PHOTO && w.any { it in NEWER_WORDS } ->
                if (index == 0) say(texts.newest) else describeLast(index - 1)
            cmd is Command.Photo -> when {
                cmd.source != mode -> switchTo(cmd.source)
                mode == PhotoSource.CAMERA -> describeLive()
                else -> describeLast(0)
            }
            mode == PhotoSource.CAMERA && w.any { it in TAKE_WORDS } -> describeLive()
            w.any { it in REPEAT_WORDS } -> repeat()
            w.any { it in HOME_WORDS } -> home()
            else -> ScreenParts.handleAsRequest(text, deps, nav)
        }
    }

    // ---- screen ---------------------------------------------------------------------------------

    val camera = mode == PhotoSource.CAMERA
    val status = when {
        listener.active -> "Listening"
        busy -> "Looking at the photo"
        description != null -> "Description ready"
        camera -> if (captureReady) "Camera ready" else "Camera starting"
        else -> "Your photos"
    }

    SaathiScreen(
        status = status,
        youCanSay = if (camera) listOf("Describe", "Save it", "Go home") else listOf("Previous photo", "Repeat", "Go home"),
        onTalk = { listener.toggle() },
        listening = listener.active,
        navRow = {
            NavRow("Home", { home() }, trailing = {
                if (camera) {
                    TextLink("Last photo", { switchTo(PhotoSource.LAST_PHOTO) }, contentDescription = "Describe your last photo instead")
                } else {
                    TextLink("Camera", { switchTo(PhotoSource.CAMERA) }, contentDescription = "Use the camera instead")
                }
            })
        },
        bottom = {
            ScreenParts.ListenHint(listener)
            if (camera) {
                PrimaryButton(
                    if (description == null) "Take photo" else "Take another",
                    { describeLive() },
                    height = 120.dp,
                    description = "Take photo. Saathi describes what the back camera sees.",
                )
            } else {
                PrimaryButton(
                    "Previous photo",
                    { describeLast(if (description == null) 0 else index + 1) },
                    description = "Previous photo. Describes the next older photo.",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Repeat", { repeat() }, modifier = Modifier.weight(1f), description = "Repeat. Hear the description again.")
                if (camera && shot != null) {
                    SecondaryButton(
                        if (saved) "Saved" else "Save photo",
                        { save() },
                        modifier = Modifier.weight(1f),
                        description = if (saved) "Saved to your photos" else "Save this photo to your phone's photos, in the Saathi album",
                    )
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextLink("Home", { home() }) }
        },
    ) {
        LargeTitle(if (camera) "What's in front" else "Your last photo", modifier = ScreenParts.rise(0, still))

        // The picture: live preview, or the photo being described. Decorative for TalkBack (the words carry it).
        Box(Modifier.fillMaxWidth().then(ScreenParts.rise(1, still)), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .heightIn(max = 440.dp)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF0B0B0D))
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                val photo = shot
                when {
                    camera && cameraOk -> AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    !camera && photo != null -> Image(
                        photo.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (camera) Icons.Outlined.PhotoCamera else Icons.Outlined.PhotoLibrary,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(if (camera) "Camera off" else "No photo yet", style = Clarity.type.headline, color = Color.White)
                    }
                }
                if (busy) {
                    Text(
                        "Looking…",
                        style = Clarity.type.headline,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // The description. Polite live region so TalkBack users hear it too.
        Column(
            ScreenParts.rise(2, still)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(c.group)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val shown = description
            Text(
                when {
                    busy -> "Looking…"
                    shown != null -> shown
                    camera -> "Point the back camera and press Take photo."
                    else -> "Press Previous photo or say “Describe my last photo”."
                },
                style = Clarity.type.body.copy(fontWeight = FontWeight.SemiBold),
                color = c.label,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (description != null) {
                HorizontalDivider(thickness = 1.dp, color = c.separator)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScreenParts.LevelBars(active = speaking, still = still, color = c.accentText, barWidth = 4.dp, maxHeight = 22.dp)
                    Text(
                        if (speaking) "Reading it to you…" else "Read aloud. Say “Repeat” to hear it again.",
                        style = Clarity.type.footnote,
                        color = c.secondary,
                    )
                }
            }
        }

        notice?.let { n ->
            val text = when (n) {
                Notice.CameraWhy -> texts.cameraWhy
                Notice.CameraRefused -> texts.cameraRefused
                Notice.PhotosWhy -> texts.photosWhy
                Notice.PhotosRefused -> texts.photosRefused
            }
            ScreenParts.NoteBox(text, extra = {
                Box(Modifier.padding(top = 12.dp)) {
                    when (n) {
                        Notice.CameraRefused, Notice.PhotosRefused -> SecondaryButton(
                            "Open phone settings",
                            { ScreenParts.openAppSettings(context) },
                            description = "Open Saathi's settings to allow it",
                        )
                        Notice.CameraWhy -> SecondaryButton("Allow camera", { cameraLauncher.launch(Manifest.permission.CAMERA) })
                        Notice.PhotosWhy -> SecondaryButton("Allow photos", { photosLauncher.launch(PhotoLibrary.permissions()) })
                    }
                }
            })
        }

        PhotoNotes(camera)
    }
}

/** Honest limits, always on screen. */
@Composable
private fun PhotoNotes(camera: Boolean) {
    val c = Clarity.colors
    val lines = listOf(
        "Recognises about ${Vocab.KINDS} kinds of common objects, roughly left, middle or right and near or far. " +
            "It can't read text, recognise faces, colours or money, or tell you whether it's safe to move.",
        if (camera) {
            "Works offline. Photos are checked on this phone and never sent. Nothing is saved unless you say “Save it”."
        } else {
            "Your photos are read on this phone only. Nothing is uploaded."
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { line ->
            Row(
                Modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = c.label, modifier = Modifier.size(22.dp))
                Text(line, style = Clarity.type.footnote, color = c.label)
            }
        }
    }
}
