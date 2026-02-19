package de.adv990de.obd2.obd.model

sealed class ObdResult<out T> {
    data class Success<T>(val data: T) : ObdResult<T>()
    data class Error(val exception: Exception) : ObdResult<Nothing>()
}

fun <T> ObdResult<T>.getOrNull(): T? = (this as? ObdResult.Success)?.data
