package com.vovoscan.core.common

/** Result of an operation: a value or an [AppError], never an exception. */
sealed interface AppResult<out T> {
    data class Ok<out T>(
        val value: T,
    ) : AppResult<T>

    data class Err(
        val error: AppError,
    ) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> =
    when (this) {
        is AppResult.Ok -> AppResult.Ok(transform(value))
        is AppResult.Err -> this
    }

inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> =
    when (this) {
        is AppResult.Ok -> transform(value)
        is AppResult.Err -> this
    }

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Ok)?.value
