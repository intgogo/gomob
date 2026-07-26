package io.gomob.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.prefs.ThemePreferenceStore
import io.gomob.designsystem.theme.ColorScheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 浅色主题 ViewModel —— 供 MainActivity 与「主题设置」页共享色彩主题。
 *
 * 同一份 [ThemePreferenceStore] 单例,任意 VM 实例订阅同一 DataStore Flow,
 * 切换后 MainActivity 会立刻重组到目标主题。
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val store: ThemePreferenceStore,
) : ViewModel() {

    /** 色彩主题 — String → enum 在 VM 层做映射，DataStore 只持久化 key 字符串 */
    val colorScheme: StateFlow<ColorScheme> = store.colorSchemeKeyFlow
        .map { ColorScheme.fromKey(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ColorScheme.Mint,
        )

    fun setColorScheme(value: ColorScheme) {
        viewModelScope.launch { store.setColorSchemeKey(value.key) }
    }

    /** 恢复默认 — 浅色薄荷青绿。 */
    fun resetToDefault() {
        viewModelScope.launch {
            store.setColorSchemeKey(ColorScheme.Mint.key)
        }
    }
}
