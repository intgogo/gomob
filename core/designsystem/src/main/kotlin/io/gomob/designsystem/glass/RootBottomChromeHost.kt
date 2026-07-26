package io.gomob.designsystem.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/** root 页向 Shell 注册底部 chrome 上半区，Shell 统一执行一次 Haze 模糊。 */
@Stable
class RootBottomChromeController {
    private val contentState = mutableStateOf<(@Composable () -> Unit)?>(null)
    val content: (@Composable () -> Unit)?
        get() = contentState.value

    private var owner: Any? = null

    internal fun attach(owner: Any, content: @Composable () -> Unit) {
        this.owner = owner
        contentState.value = content
    }

    internal fun detach(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            contentState.value = null
        }
    }
}

val LocalRootBottomChromeController = staticCompositionLocalOf<RootBottomChromeController?> { null }

@Composable
fun RootBottomChromeSlot(content: @Composable () -> Unit) {
    val controller = LocalRootBottomChromeController.current
    val currentContent by rememberUpdatedState(content)
    val owner = remember { Any() }
    DisposableEffect(controller, owner) {
        controller?.attach(owner) { currentContent() }
        onDispose { controller?.detach(owner) }
    }
}
