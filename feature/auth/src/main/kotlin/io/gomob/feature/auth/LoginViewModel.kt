package io.gomob.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.auth.AuthRepository
import io.gomob.network.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val username: String = "shenhm",
    val password: String = "",
    val rememberMe: Boolean = true,
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val loggedIn: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepo: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun setUsername(v: String) = _state.update { it.copy(username = v, errorMessage = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, errorMessage = null) }
    fun setRemember(v: Boolean) = _state.update { it.copy(rememberMe = v) }

    fun submit() {
        val s = _state.value
        if (s.username.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(errorMessage = "请输入账号和密码") }
            return
        }
        _state.update { it.copy(loading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                authRepo.login(s.username.trim(), s.password)
                _state.update { it.copy(loading = false, loggedIn = true) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, errorMessage = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, errorMessage = "网络异常: ${e.message ?: "未知"}") }
            }
        }
    }
}
