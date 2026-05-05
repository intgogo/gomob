package io.gomob.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.prefs.ThemeMode
import io.gomob.data.prefs.ThemePreferenceStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 外观偏好 ViewModel —— 同时供 MainActivity 决定 darkTheme 与 ProfileAppearanceRoute 切换。
 *
 * 同一份 [ThemePreferenceStore] 单例,任意 VM 实例订阅同一 DataStore Flow,
 * 切换后 MainActivity 会立刻重组到目标主题。
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val store: ThemePreferenceStore,
) : ViewModel() {

    val mode: StateFlow<ThemeMode> = store.modeFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ThemeMode.Dark,
    )

    fun setMode(value: ThemeMode) {
        viewModelScope.launch { store.setMode(value) }
    }
}
