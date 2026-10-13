package app.saathi.photo

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import app.saathi.agent.PhotoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** A spoken request for the photo screen ("take a photo" / "describe my last photo"), picked up when it opens. */
object PhotoRequest {
    private val _pending = MutableStateFlow<PhotoSource?>(null)
    val pending: StateFlow<PhotoSource?> = _pending.asStateFlow()

    fun open(source: PhotoSource) {
        _pending.value = source
    }

    /** Take (and clear) the pending request. */
    fun take(): PhotoSource? {
        val s = _pending.value
        _pending.value = null
        return s
    }
}

/**
 * The phone's photo library, read-only except for "Save it" (into Pictures/Saathi, only when asked).
 * Read access is asked for by the screen the first time the user wants their last photo; Saathi never
 * grants itself anything. Nothing is uploaded: the app has no INTERNET permission.
 */
object PhotoLibrary {
    private const val TAG = "PhotoLibrary"
    const val ALBUM = "Saathi"

    enum class Access { Full, Limited, None }

    /** The runtime permissions to ask for (Android 14+ also offers "only selected photos"). */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun access(context: Context): Access = when {
        Build.VERSION.SDK_INT >= 33 && granted(context, Manifest.permission.READ_MEDIA_IMAGES) -> Access.Full
        Build.VERSION.SDK_INT >= 34 && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.Limited
        Build.VERSION.SDK_INT < 33 && granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.Full
        else -> Access.None
    }

    /** One photo in the library. [index] 0 = newest. */
    data class Item(val uri: Uri, val takenMillis: Long?, val orientation: Int, val screenshot: Boolean, val index: Int)

    /** The [index]-th newest image (by when it arrived on the phone), or null if there isn't one. Call off the main thread. */
    fun item(context: Context, index: Int): Item? {
        if (index < 0) return null
        val cols = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        return runCatching {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null, order)?.use { c ->
                if (!c.moveToPosition(index)) return@use null
                val id = c.getLong(0)
                val taken = if (c.isNull(1)) 0L else c.getLong(1)
                val added = if (c.isNull(2)) 0L else c.getLong(2) * 1000
                val bucket = c.getString(4).orEmpty()
                val name = c.getString(5).orEmpty()
                Item(
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    takenMillis = (if (taken > 0) taken else added).takeIf { it > 0 },
                    orientation = if (c.isNull(3)) 0 else c.getInt(3),
                    screenshot = "screenshot" in bucket.lowercase() || "screenshot" in name.lowercase(),
                    index = index,
                )
            }
        }.onFailure { Log.w(TAG, "query failed", it) }.getOrNull()
    }

    /** Decode [item] upright, at most [maxSide] px on its long side. Call off the main thread. */
    fun load(context: Context, item: Item, maxSide: Int = 1920): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(context.contentResolver, item.uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // pixels are read back for the models
                val s = info.size
                val k = maxSide.toDouble() / max(s.width, s.height)
                if (k < 1) decoder.setTargetSize(max(1, (s.width * k).toInt()), max(1, (s.height * k).toInt()))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val b = context.contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
            if (item.orientation % 360 == 0) {
                b
            } else {
                val m = Matrix().apply { postRotate(item.orientation.toFloat()) }
                Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).also { if (it !== b) b.recycle() }
            }
        }
    }.onFailure { Log.w(TAG, "decode failed", it) }.getOrNull()

    /**
     * "Save it": write [photo] as a JPEG into Pictures/Saathi. Android 10+ only (needs no storage
     * permission there); returns null on older phones or on failure. Call off the main thread.
     */
    fun save(context: Context, photo: Bitmap): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = context.contentResolver
        val name = "Saathi_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) } == true
        }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        runCatching { resolver.update(uri, values, null, null) }
        return uri
    }
}
