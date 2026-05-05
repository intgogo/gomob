package io.gomob.feature.auth

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.auth.AuthRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

@HiltViewModel
class AuthGateViewModel @Inject constructor(
    authRepo: AuthRepository,
) : ViewModel() {
    /** true = 已登录，false = 未登录。MainActivity 据此选择路由。 */
    val isLoggedIn: Flow<Boolean> = authRepo.isLoggedIn
}
