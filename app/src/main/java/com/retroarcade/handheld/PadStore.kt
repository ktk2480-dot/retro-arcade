package com.retroarcade.handheld

import android.content.SharedPreferences
import android.graphics.PointF

/**
 * The player's own changes to the control panel:
 *  - where controls were dragged to, kept separately for upright / sideways and
 *    for each button arrangement;
 *  - which game button each on-screen button presses, kept per game.
 */
class PadStore(private val prefs: SharedPreferences) {

    fun profileKey(landscape: Boolean, buttonCount: Int): String =
        "pos_" + (if (landscape) "L" else "P") + buttonCount

    /** Control id -> offset from its default place, in ConsoleGeometry.unit lengths. */
    fun loadOffsets(profileKey: String): HashMap<Int, PointF> {
        val out = HashMap<Int, PointF>()
        val raw = prefs.getString(profileKey, null) ?: return out
        for (part in raw.split(';')) {
            val fields = part.split(':')
            if (fields.size != 3) continue
            val id = fields[0].toIntOrNull() ?: continue
            val x = fields[1].toFloatOrNull() ?: continue
            val y = fields[2].toFloatOrNull() ?: continue
            out[id] = PointF(x, y)
        }
        return out
    }

    fun saveOffsets(profileKey: String, offsets: Map<Int, PointF>) {
        val raw = offsets.entries.joinToString(";") { "${it.key}:${it.value.x}:${it.value.y}" }
        prefs.edit().putString(profileKey, raw).apply()
    }

    fun clearOffsets(profileKey: String) {
        prefs.edit().remove(profileKey).apply()
    }

    /** Slot -> index of the game button it presses, or -1 for the arrangement's default. */
    fun loadKeyMap(gameKey: String, buttonCount: Int): IntArray {
        val map = IntArray(SLOTS) { -1 }
        val raw = prefs.getString(mapKey(gameKey, buttonCount), null) ?: return map
        val parts = raw.split(',')
        for (i in 0 until minOf(parts.size, SLOTS)) {
            map[i] = parts[i].toIntOrNull() ?: -1
        }
        return map
    }

    fun saveKeyMap(gameKey: String, buttonCount: Int, map: IntArray) {
        prefs.edit().putString(mapKey(gameKey, buttonCount), map.joinToString(",")).apply()
    }

    fun clearKeyMap(gameKey: String, buttonCount: Int) {
        prefs.edit().remove(mapKey(gameKey, buttonCount)).apply()
    }

    private fun mapKey(gameKey: String, buttonCount: Int) = "map_${gameKey}_$buttonCount"

    companion object {
        const val SLOTS = 6
    }
}
