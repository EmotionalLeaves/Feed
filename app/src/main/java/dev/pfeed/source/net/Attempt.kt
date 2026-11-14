package dev.pfeed.source.net

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] that lets cancellation through. Plain runCatching also catches the
 * CancellationException a timeout uses to stop work, so a "10 s" search budget waited for every
 * swallowed request to finish anyway (measured: 32 s searches). Use this in coroutine code.
 */
inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
