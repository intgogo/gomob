package io.gomob.feature.message

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

fun NavGraphBuilder.messageScreen() {
    composable(MESSAGE_ROUTE) { MessageRoute() }
}
