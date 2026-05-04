package io.gomob.scan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import io.gomob.designsystem.theme.GomobTheme
import io.gomob.designsystem.theme.SurfaceDeep

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GomobTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SurfaceDeep),
                ) {
                    AppRoot()
                }
            }
        }
    }
}
