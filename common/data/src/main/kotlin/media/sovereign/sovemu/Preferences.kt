package media.sovereign.sovemu

import media.sovereign.sovemu.core.GameSort

// The app's own choices, kept in [Settings] beside the sign-in: set from the account menu and
// the game list, read by the screens and the player.

/** Minutes without input before the screensaver (5 by default; 0 turns it off). */
var Settings.screensaverMinutes: Int
    get() = prefs.getInt("screensaver", 5)
    set(value) = prefs.edit().putInt("screensaver", value).apply()

/** Background music in the menus (on by default). */
var Settings.music: Boolean
    get() = prefs.getBoolean("music", true)
    set(value) = prefs.edit().putBoolean("music", value).apply()

/** The stats overlay over a game (off by default). */
var Settings.stats: Boolean
    get() = prefs.getBoolean("stats", false)
    set(value) = prefs.edit().putBoolean("stats", value).apply()

/** The game list's sort, kept across launches like the web client's. */
var Settings.sort: GameSort
    get() = runCatching { GameSort.valueOf(prefs.getString("sort", null)!!) }
        .getOrDefault(GameSort.TITLE)
    set(value) = prefs.edit().putString("sort", value.name).apply()
