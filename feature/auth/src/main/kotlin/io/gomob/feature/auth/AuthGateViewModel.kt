package io.gomob.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.auth.AuthRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@HiltViewModel
class AuthGateViewModel @Inject constructor(
    private val authRepo: AuthRepository,
) : ViewModel() {
    /** true = 已登录，false = 未登录。MainActivity 据此选择路由。 */
    val isLoggedIn: Flow<Boolean> = authRepo.isLoggedIn

    val sessionNotice: Flow<String?> = authRepo.sessionNotice

    fun clearSessionNotice() {
        viewModelScope.launch {
            authRepo.clearSessionNotice()
        }
    }
}
