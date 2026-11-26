package media.sovereign.sovemu

/** `core`'s [media.sovereign.sovemu.core.attempt], where the app's code has always called it. */
inline fun <T> attempt(block: () -> T): Result<T> = media.sovereign.sovemu.core.attempt(block)
