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

data class RegisterUiState(
    val realName: String = "",
    val employeeId: String = "",
    val stationHint: String = "",
    val username: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
)

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val authRepo: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RegisterUiState())
    val state: StateFlow<RegisterUiState> = _state.asStateFlow()

    fun setRealName(v: String) = _state.update { it.copy(realName = v, errorMessage = null) }
    fun setEmployeeId(v: String) = _state.update { it.copy(employeeId = v, errorMessage = null) }
    fun setStationHint(v: String) = _state.update { it.copy(stationHint = v, errorMessage = null) }
    fun setUsername(v: String) = _state.update { it.copy(username = v, errorMessage = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, errorMessage = null) }
    fun setConfirmPassword(v: String) =
        _state.update { it.copy(confirmPassword = v, errorMessage = null) }

    fun submit() {
        val s = _state.value
        when {
            s.realName.isBlank() || s.employeeId.isBlank() ||
                s.username.isBlank() || s.password.isBlank() -> {
                _state.update { it.copy(errorMessage = "请填写必填字段") }
                return
            }
            s.password.length < 6 -> {
                _state.update { it.copy(errorMessage = "密码至少 6 位") }
                return
            }
            s.password != s.confirmPassword -> {
                _state.update { it.copy(errorMessage = "两次密码不一致") }
                return
            }
        }
        _state.update { it.copy(loading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val msg = authRepo.register(
                    username = s.username.trim(),
                    password = s.password,
                    realName = s.realName.trim(),
                    employeeId = s.employeeId.trim(),
                    stationNameHint = s.stationHint.trim(),
                    note = null,
                )
                _state.update { it.copy(loading = false, successMessage = msg) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, errorMessage = e.message) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, errorMessage = "网络异常: ${e.message ?: "未知"}")
                }
            }
        }
    }
}
