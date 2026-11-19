package media.sovereign.sovemu

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspend calls: failures become a [Result], but cancellation is rethrown, so
 * work stops when its screen goes away (plain runCatching would swallow it and carry on).
 */
inline fun <T> attempt(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
