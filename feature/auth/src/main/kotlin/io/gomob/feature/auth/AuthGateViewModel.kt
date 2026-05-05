package io.gomob.feature.auth

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.auth.AuthRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@HiltViewModel
class AuthGateViewModel @Inject constructor(
    authRepo: AuthRepository,
) : ViewModel() {
    /** true = 已登录，false = 未登录。MainActivity 据此选择路由。 */
    // TEMP DEV BYPASS — 验证 mob3d 7 屏视觉时不走服务端登录, 看完恢复成 authRepo.isLoggedIn
    val isLoggedIn: Flow<Boolean> = flowOf(true)
    @Suppress("unused") private val _real = authRepo.isLoggedIn
}
