package media.sovereign.sovemu.core

import media.sovereign.sovemu.core.graphql.type.Rendering

/** A game with only what a test cares about set (the generated model has no defaults). */
fun game(
    id: Int,
    title: String,
    genre: String? = null,
    players: String? = null,
    playCount: Int = 0,
    lastPlayedAt: String? = null,
    releaseDate: String? = null,
    developer: String? = null,
    system: String = "nes",
    fileTitle: String? = null,
    tags: List<String> = emptyList(),
): Game = Game(
    id = id, system = system, title = title, fileTitle = fileTitle, tags = tags, path = "$system/$title", size = 0.0,
    lastPlayedAt = lastPlayedAt, playCount = playCount, image = null, thumb = null, video = null, description = null,
    releaseDate = releaseDate, developer = developer, publisher = null, genre = genre, players = players, rating = null,
    discs = 1, autoSave = null,
)

fun system(
    id: String = "nes",
    playable: Boolean = true,
    needsGpu: Boolean = false,
    rendering: Rendering? = null,
    biosMissing: Boolean = false,
): SystemSummary = SystemSummary(
    id = id, name = id.uppercase(), short = id.uppercase(), manufacturer = "Nintendo", year = 1985, color = "#c0392b",
    gameCount = 1, playable = playable, needsGpu = needsGpu, rendering = rendering, biosMissing = biosMissing,
)

fun save(slot: Int, savedAt: Double = 1_700_000_000.0): SaveSlot = SaveSlot(slot, savedAt, "/media/games/1/saves/$slot")
