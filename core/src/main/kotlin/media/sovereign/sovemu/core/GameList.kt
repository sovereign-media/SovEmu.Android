package media.sovereign.sovemu.core

import java.text.Collator
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

// A system's game list: searching, filtering, sorting and the A–Z jump, after SovEmu.Web's
// src/lib/games.ts, so the screen only draws what these return.

enum class GameSort(val label: String) {
    TITLE("Title"),
    RECENT("Recently played"),
    YEAR("Release year"),
    PLAYS("Most played"),
}

enum class PlayedFilter { PLAYED, UNPLAYED }

data class GameFilters(
    val played: PlayedFilter? = null,
    /** Only games for at least two players. */
    val multiplayer: Boolean = false,
    /** Only games in any of these genres. */
    val genres: Set<String> = emptySet(),
) {
    /** How many filters are on, for the Filters chip. */
    val count: Int get() = (if (played != null) 1 else 0) + (if (multiplayer) 1 else 0) + genres.size
}

/** A game's genres: the metadata lists them comma-separated ("Platform, Action"). */
fun genresOf(genre: String?): List<String> = genre.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

private val DIGITS = Regex("\\d+")
private val SPACES = Regex("\\s+")

/** The most players a game takes, from "1-2", "2", "1 to 4" …, or null when unknown. */
fun maxPlayers(players: String?): Int? = DIGITS.findAll(players.orEmpty()).mapNotNull { it.value.toIntOrNull() }.maxOrNull()

private val MARKS = Regex("\\p{Mn}+")

/** Folds case and accents, so "pokemon" finds "Pokémon". */
fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

/** The letters the jump strip offers: "#" then A–Z. */
val LETTERS: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

/** The letter a title files under: A–Z, or "#" for digits and symbols. Articles are not skipped. */
fun letterOf(title: String): String {
    val c = fold(title).firstOrNull()?.uppercaseChar() ?: return "#"
    return if (c in 'A'..'Z') c.toString() else "#"
}

/**
 * One system's games, indexed once: each game's folded search text, genres, players, year and
 * letter are worked out here rather than on every keystroke or sort.
 */
class GameIndex(val games: List<Game>) {
    private class Entry(val game: Game, val text: String, val genres: List<String>, val players: Int?, val year: Int, val played: Long, val letter: Int)

    // In title order, worked out once (collation is the slow part): every other sort is stable,
    // so its ties stay in title order too.
    private val entries = games.map { g ->
        Entry(
            game = g,
            text = fold(listOfNotNull(g.title, g.fileTitle, g.genre, g.developer, g.publisher).plus(g.tags).joinToString(" ")),
            genres = genresOf(g.genre),
            players = maxPlayers(g.players),
            year = g.year?.toIntOrNull() ?: 0,
            played = playedAt(g.lastPlayedAt),
            letter = LETTERS.indexOf(letterOf(g.title)),
        )
    }.sortedWith { a, b -> titleOrder.compare(a.game.title, b.game.title) }

    /** Every genre with its count, most common first: empty until game information is fetched. */
    val genreCounts: List<Pair<String, Int>> = entries.flatMap { it.genres }.groupingBy { it }.eachCount().toList()
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    /** Whether any game says how many players it takes, so the players filter can do something. */
    val knowsPlayers: Boolean = entries.any { it.players != null }

    /** The games matching every word of [query] and every filter, sorted by [sort] (ties by title). */
    fun query(query: String, filters: GameFilters, sort: GameSort): List<Game> {
        val words = fold(query).split(SPACES).filter { it.isNotEmpty() }
        val kept = entries.filter { e ->
            when {
                filters.played == PlayedFilter.PLAYED && e.game.playCount == 0 -> false
                filters.played == PlayedFilter.UNPLAYED && e.game.playCount > 0 -> false
                filters.multiplayer && (e.players ?: 1) < 2 -> false
                filters.genres.isNotEmpty() && filters.genres.none { it in e.genres } -> false
                else -> words.all { it in e.text }
            }
        }
        val sorted = when (sort) {
            GameSort.TITLE -> kept
            GameSort.RECENT -> kept.sortedByDescending { it.played }
            GameSort.YEAR -> kept.sortedBy { if (it.year == 0) 9999 else it.year }
            GameSort.PLAYS -> kept.sortedByDescending { it.game.playCount }
        }
        return sorted.map { it.game }
    }

    companion object {
        /** Case- and accent-insensitive, digits by value ("Mega Man 2" before "Mega Man 10"). */
        val titleOrder: Comparator<String> = Comparator { a, b -> natural(a, b) }

        private val collator: Collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.PRIMARY }
        private val CHUNK = Regex("\\d+|\\D+")

        private fun natural(a: String, b: String): Int {
            val x = CHUNK.findAll(a).map { it.value }.toList()
            val y = CHUNK.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val p = x[i]
                val q = y[i]
                val c = if (p[0].isDigit() && q[0].isDigit()) {
                    p.toBigInteger().compareTo(q.toBigInteger())
                } else {
                    collator.compare(p, q)
                }
                if (c != 0) return c
            }
            return x.size - y.size
        }

        /** `lastPlayedAt` ("2026-11-19 16:05:56", UTC) as epoch seconds, 0 when never. */
        fun playedAt(s: String?): Long =
            runCatching { LocalDateTime.parse(s!!.replace(' ', 'T')).toEpochSecond(ZoneOffset.UTC) }.getOrDefault(0L)
    }
}

/** The jump strip's stops: only letters some shown game files under, "#" first. */
fun jumpStops(games: List<Game>): List<String> {
    val present = games.mapTo(HashSet()) { letterOf(it.title) }
    return LETTERS.filter { it in present }
}

/** Where the jump strip goes for [letter]: the first game under it, else the next letter with one, else the last game; -1 when empty. */
fun jumpTo(games: List<Game>, letter: String): Int {
    if (games.isEmpty()) return -1
    val wanted = LETTERS.indexOf(letter)
    val at = games.indexOfFirst { LETTERS.indexOf(letterOf(it.title)) >= wanted }
    return if (at >= 0) at else games.size - 1
}

/** "3 hours ago", from a `lastPlayedAt` or a Unix time, against [now] (epoch seconds). */
fun formatAgo(epochSeconds: Long, now: Long): String {
    if (epochSeconds <= 0) return "never"
    val s = (now - epochSeconds).coerceAtLeast(0)
    fun plural(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
    return when {
        s < 60 -> "just now"
        s < 3600 -> plural(s / 60, "minute")
        s < 86_400 -> plural(s / 3600, "hour")
        s < 30 * 86_400 -> plural(s / 86_400, "day")
        s < 365 * 86_400 -> plural(s / (30 * 86_400), "month")
        else -> plural(s / (365 * 86_400), "year")
    }
}

private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/** A release date as people say it: "March 11, 1995", "March 1995" or "1995" (from `YYYY[-MM[-DD]]`). */
fun formatReleaseDate(date: String?): String? {
    val parts = date?.split('-')?.takeIf { it.isNotEmpty() && it[0].length == 4 } ?: return null
    val month = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 }?.let { MONTHS[it - 1] }
    val day = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..31 }
    return when {
        month != null && day != null -> "$month $day, ${parts[0]}"
        month != null -> "$month ${parts[0]}"
        else -> parts[0]
    }
}

/** "1 player", "1-2 players", "Up to 4 players" (from the metadata's "1", "1-2", "1 to 4"…); null when unknown. */
fun formatPlayers(players: String?): String? {
    val most = maxPlayers(players) ?: return null
    return when {
        most == 1 -> "1 player"
        players!!.trim().matches(Regex("\\d+\\s*-\\s*\\d+")) -> "${players.replace(" ", "")} players"
        players.trim() == most.toString() -> "$most players"
        else -> "Up to $most players"
    }
}

/** "1.2 GB", "820 KB". */
fun formatSize(bytes: Double): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (i == 0 || v >= 100) "${v.toLong()} ${units[i]}" else String.format(Locale.ROOT, "%.1f %s", v, units[i])
}

// --- Save slots -------------------------------------------------------------------------------

/** The auto save's slot: written when a game ends, loaded by Continue. */
const val AUTO_SLOT = 0

/** The slots a player saves to by hand, as on the web client. */
val SAVE_SLOTS = listOf(1, 2, 3, 4)

/** One row of a save or load picker: the slot, its save if there is one, and whether it can be picked. */
data class SlotRow(val slot: Int, val label: String, val save: SaveSlot?, val enabled: Boolean)

/**
 * The rows for [numbers] given the game's [saves]: a save picker offers every slot (an empty one
 * is written to), a load picker only the ones with a save ([emptyEnabled] false).
 */
fun slotRows(saves: List<SaveSlot>, numbers: List<Int>, emptyEnabled: Boolean): List<SlotRow> =
    numbers.map { n ->
        val save = saves.firstOrNull { it.slot == n }
        SlotRow(n, if (n == AUTO_SLOT) "Auto save" else "Slot $n", save, save != null || emptyEnabled)
    }
