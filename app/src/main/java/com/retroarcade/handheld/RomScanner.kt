package com.retroarcade.handheld

import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** How well one zip fits one core's expectation of that ROM set. */
class CoreMatch(
    val core: Core,
    val game: DbGame,
    /** Required files that are in neither the zip nor its parent zip. */
    val missing: Int,
    /** Share of the set's data that was found, 0..1 (weighted by file size). */
    val score: Float,
    val biosMissing: Boolean,
    val parentMissing: Boolean,
) {
    val playable: Boolean get() = missing == 0 && !biosMissing
    val percent: Int get() = (score * 100f).toInt()
}

class RomItem(
    val file: File,
    /** Set name: the file name without its extension. The cores identify games by it. */
    val name: String,
    /** Best core first. Empty when neither core knows this set name. */
    val matches: List<CoreMatch>,
    /** False for 7z archives, whose contents are not checked. */
    val verifiable: Boolean,
) {
    val best: CoreMatch? get() = matches.firstOrNull()
    val known: Boolean get() = matches.isNotEmpty()
    val title: String get() = best?.game?.title ?: file.name

    /** 0 = ready to play, 1 = worth a try, 2 = wrong ROM set, 3 = unknown file. */
    val rank: Int
        get() {
            val b = best ?: return 3
            return when {
                !verifiable -> 1
                b.playable -> 0
                b.missing == 0 -> 1
                else -> 2
            }
        }

    fun matchFor(core: Core): CoreMatch? = matches.firstOrNull { it.core == core }
}

class ScanResult(val items: List<RomItem>, val biosSets: List<String>)

object RomScanner {

    /**
     * FBNeo's list also names tiny programmable-logic dumps that it does not need
     * in order to run a game, so their absence is not counted against a set.
     */
    private const val FBNEO_OPTIONAL_BELOW = 1024

    fun scan(dir: File, db: GameDb): ScanResult {
        val files = dir.listFiles() ?: return ScanResult(emptyList(), emptyList())

        // Set name -> archive. A zip wins over a 7z of the same name.
        val byName = HashMap<String, File>()
        for (f in files) {
            if (!f.isFile) continue
            val lower = f.name.lowercase(Locale.ROOT)
            val isZip = lower.endsWith(".zip")
            if (!isZip && !lower.endsWith(".7z")) continue
            val key = lower.substringBeforeLast('.')
            if (isZip || !byName.containsKey(key)) byName[key] = f
        }

        val crcCache = HashMap<String, Set<Int>>()
        fun crcsOf(name: String): Set<Int> {
            if (name.isEmpty()) return emptySet()
            crcCache[name]?.let { return it }
            val file = byName[name]
            val crcs = if (file != null && isZip(file)) readCrcs(file) else emptySet()
            crcCache[name] = crcs
            return crcs
        }

        val items = ArrayList<RomItem>()
        val biosSets = ArrayList<String>()

        for ((name, file) in byName) {
            val candidates = Core.values().mapNotNull { core -> db.find(core, name)?.let { core to it } }
            if (candidates.isNotEmpty() && candidates.all { it.second.isBios }) {
                biosSets.add(name)
                continue
            }

            if (!isZip(file)) {
                // Only FBNeo reads 7z, and the contents cannot be checked from here.
                val game = db.find(Core.FBNEO, name)
                val matches = if (game != null && !game.isBios) {
                    listOf(CoreMatch(Core.FBNEO, game, 0, 1f, false, false))
                } else {
                    emptyList()
                }
                items.add(RomItem(file, name, matches, false))
                continue
            }

            val matches = ArrayList<CoreMatch>()
            for ((core, game) in candidates) {
                if (game.isBios) continue
                val own = crcsOf(name)
                val parent = crcsOf(game.parent)
                var missing = 0
                var foundBytes = 0L
                var totalBytes = 0L
                for (i in game.crcs.indices) {
                    val size = game.sizes[i].toLong() and 0xFFFFFFFFL
                    totalBytes += size
                    val crc = game.crcs[i]
                    if (own.contains(crc) || parent.contains(crc)) {
                        foundBytes += size
                    } else if (!(core == Core.FBNEO && size < FBNEO_OPTIONAL_BELOW)) {
                        missing++
                    }
                }
                val score = if (totalBytes > 0L) foundBytes.toFloat() / totalBytes.toFloat() else 1f
                val biosMissing = game.bios.isNotEmpty() && !byName.containsKey(game.bios)
                val parentMissing = missing > 0 && game.parent.isNotEmpty() && !byName.containsKey(game.parent)
                matches.add(CoreMatch(core, game, missing, score, biosMissing, parentMissing))
            }
            matches.sortWith(
                compareBy<CoreMatch> { if (it.playable) 0 else 1 }
                    .thenBy { if (it.missing == 0) 0 else 1 }
                    .thenByDescending { it.score }
                    .thenBy { it.core.ordinal }
            )
            items.add(RomItem(file, name, matches, true))
        }

        items.sortWith(compareBy<RomItem> { it.rank }.thenBy { it.title.lowercase(Locale.ROOT) })
        biosSets.sort()
        return ScanResult(items, biosSets)
    }

    private fun isZip(file: File) = file.name.lowercase(Locale.ROOT).endsWith(".zip")

    /** CRC-32 of every file in the zip, read from its directory (nothing is unpacked). */
    private fun readCrcs(file: File): Set<Int> {
        val out = HashSet<Int>()
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) out.add(entry.crc.toInt())
                }
            }
        } catch (_: Exception) {
            // Unreadable or damaged archive: treated as empty, which shows up as a mismatch.
        }
        return out
    }
}
