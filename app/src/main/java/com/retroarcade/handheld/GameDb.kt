package com.retroarcade.handheld

import android.content.Context
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** One ROM set as a core knows it. Built by tools/build_gamedb.py. */
class DbGame(
    val name: String,
    val title: String,
    /** Parent set whose zip supplies shared files, or "". */
    val parent: String,
    /** BIOS set that must sit next to the game (for example "neogeo"), or "". */
    val bios: String,
    private val flags: Int,
    /** Number of action buttons, or [GameDb.BUTTONS_UNKNOWN]. */
    val buttons: Int,
    /** The cabinet's stick: one of the GameDb.STICK_ values. */
    val stick: Int,
    val crcs: IntArray,
    val sizes: IntArray,
) {
    val isVertical: Boolean get() = (flags and 1) != 0
    val isBios: Boolean get() = (flags and 2) != 0
    val isPreliminary: Boolean get() = (flags and 4) != 0
}

class GameDb private constructor(private val cores: Map<Core, Map<String, DbGame>>) {

    fun find(core: Core, name: String): DbGame? = cores[core]?.get(name)

    companion object {
        const val BUTTONS_UNKNOWN = 255

        const val STICK_UNKNOWN = 0
        const val STICK_8WAY = 1
        const val STICK_4WAY = 2
        const val STICK_ANALOG = 3

        @Volatile
        private var instance: GameDb? = null

        fun get(context: Context): GameDb {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val loaded = load(context)
                instance = loaded
                return loaded
            }
        }

        private fun load(context: Context): GameDb {
            val bytes = context.assets.open("gamedb.bin").use { it.readBytes() }
            val buf = ByteBuffer.wrap(bytes)
            val magic = ByteArray(4)
            buf.get(magic)
            if (String(magic, StandardCharsets.US_ASCII) != "RADB") {
                throw IllegalStateException("gamedb.bin is not a game database")
            }
            val version = buf.getInt()
            if (version != 2) throw IllegalStateException("unsupported game database version $version")
            val coreCount = buf.getInt()
            val result = HashMap<Core, Map<String, DbGame>>()
            for (c in 0 until coreCount) {
                val core = Core.byId(readString(buf))
                val gameCount = buf.getInt()
                val games = HashMap<String, DbGame>(gameCount * 2)
                for (g in 0 until gameCount) {
                    val name = readString(buf)
                    val title = readString(buf)
                    val parent = readString(buf)
                    val bios = readString(buf)
                    val flags = buf.get().toInt() and 0xFF
                    val buttons = buf.get().toInt() and 0xFF
                    val stick = buf.get().toInt() and 0xFF
                    val romCount = buf.getShort().toInt() and 0xFFFF
                    val crcs = IntArray(romCount)
                    val sizes = IntArray(romCount)
                    for (i in 0 until romCount) {
                        crcs[i] = buf.getInt()
                        sizes[i] = buf.getInt()
                    }
                    games[name] = DbGame(name, title, parent, bios, flags, buttons, stick, crcs, sizes)
                }
                if (core != null) result[core] = games
            }
            return GameDb(result)
        }

        private fun readString(buf: ByteBuffer): String {
            val length = buf.getShort().toInt() and 0xFFFF
            val bytes = ByteArray(length)
            buf.get(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }
    }
}
