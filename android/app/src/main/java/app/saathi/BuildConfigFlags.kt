package app.saathi

import android.content.Context
import android.content.pm.ApplicationInfo

/** True only for debug (debuggable) builds; used to keep diagnostics out of release builds. */
object BuildConfigFlags {
    fun debuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
