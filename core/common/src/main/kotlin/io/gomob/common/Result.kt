package io.gomob.common

/** 统一的领域结果类型。Loading/Success/Error 三态，避免业务层各自发明。 */
sealed interface Result<out T> {
    data object Loading : Result<Nothing>
    data class Success<T>(val data: T) : Result<T>
    data class Error(val throwable: Throwable) : Result<Nothing>
}
