package com.retroarcade.handheld

import android.content.Context
import java.io.File

/**
 * The two bundled arcade cores. Each one only runs ROM sets built for its own
 * version, which is why the game list checks every zip against both.
 */
enum class Core(
    val id: String,
    val label: String,
    private val library: String,
) {
    FBNEO("fbneo", "FBNeo", "libfbneo_libretro_android.so"),
    MAME2003PLUS("mame2003_plus", "MAME 2003-Plus", "libmame2003_plus_libretro_android.so");

    private fun libraryFile(context: Context) =
        File(context.applicationInfo.nativeLibraryDir, library)

    /** False when the APK was built without this core (its download failed at build time). */
    fun isBundled(context: Context): Boolean = libraryFile(context).exists()

    fun libraryPath(context: Context): String {
        val file = libraryFile(context)
        return if (file.exists()) file.absolutePath else library
    }

    companion object {
        fun byId(id: String?): Core? = values().firstOrNull { it.id == id }
    }
}

/**
 * Small cross-process note about the last game start. The game runs in its own
 * process, so a file is the simplest thing both sides can read.
 */
object RunMarker {
    const val LOADING = "loading"
    const val RUNNING = "running"

    private fun file(context: Context) = File(context.filesDir, "last_run.state")

    fun set(context: Context, state: String) {
        try {
            file(context).writeText(state)
        } catch (_: Exception) {
        }
    }

    fun read(context: Context): String? = try {
        val f = file(context)
        if (f.exists()) f.readText().trim() else null
    } catch (_: Exception) {
        null
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (_: Exception) {
        }
    }
}
