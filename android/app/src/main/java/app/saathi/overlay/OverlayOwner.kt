package app.saathi.overlay

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * What Compose needs to live in a window that isn't an activity's (the overlay windows the
 * accessibility service adds): a lifecycle, a saved-state registry (rememberSaveable) and an
 * activity-result registry (the shared listeners ask for the microphone through it).
 *
 * An overlay can't show Android's permission dialog, so a permission request here is answered
 * "not granted" at once: the shared listener then shows "Saathi needs the microphone…" and the user
 * allows it from Saathi itself. Saathi never grants anything on the user's behalf.
 */
internal class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ActivityResultRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            Log.i("SaathiFloating", "a permission was asked from the overlay: answered not granted (allow it in Saathi)")
            Handler(Looper.getMainLooper()).post { dispatchResult(requestCode, false) }
        }
    }

    fun create() {
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (registry.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) registry.currentState = Lifecycle.State.DESTROYED
    }
}
