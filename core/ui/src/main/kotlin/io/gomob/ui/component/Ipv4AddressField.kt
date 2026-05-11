package io.gomob.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gomob.common.net.Ipv4AddressDraft
import io.gomob.designsystem.theme.Gomob

@Composable
fun Ipv4AddressField(
    label: String,
    value: Ipv4AddressDraft,
    onValueChange: (Ipv4AddressDraft) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        Text(label, style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
        ) {
            value.octets.forEachIndexed { index, octet ->
                OctetField(
                    value = octet,
                    placeholder = OCTET_PLACEHOLDERS[index],
                    isError = isError,
                    onValueChange = { raw -> onValueChange(value.updateOctet(index, raw)) },
                    modifier = Modifier.weight(1f),
                )
                if (index < 3) {
                    Text(".", style = Gomob.type.numInline, color = Gomob.colors.fg2)
                }
            }
        }
    }
}

@Composable
private fun OctetField(
    value: String,
    placeholder: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(Gomob.spacing.touchMin)
            .clip(Gomob.shapes.r2)
            .background(if (isError) Gomob.colors.dangerSoft else Gomob.colors.bg2)
            .padding(horizontal = Gomob.spacing.s8),
        contentAlignment = Alignment.Center,
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                style = Gomob.type.numInline,
                color = Gomob.colors.fg3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Gomob.type.numInline.copy(
                color = Gomob.colors.fg0,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(Gomob.colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val OCTET_PLACEHOLDERS = listOf("192", "168", "0", "1")
