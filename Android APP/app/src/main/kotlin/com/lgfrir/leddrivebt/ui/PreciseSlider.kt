package com.lgfrir.leddrivebt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lgfrir.leddrivebt.ui.theme.AppType
import com.lgfrir.leddrivebt.ui.theme.Space
import kotlin.math.roundToInt

@Composable
fun PreciseSlider(
    label: String,
    value: Int,
    sliderMax: Int,
    hardMax: Int,
    suffix: String,
    onChange: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) {
        if (!focused) text = value.toString()
    }
    val limit = hardMax.coerceAtLeast(1)
    /*
     * 滑条只走常用区间（sliderMax），文本框仍可以输入到硬上限（hardMax）。
     * 之前两者都按 hardMax 算，速度 50 在 0–1000 的滑条上只有 5% 位置，看起来像没生效。
     */
    val sliderLimit = sliderMax.coerceIn(1, limit)
    Column(Modifier.fillMaxWidth().padding(top = Space.x3)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = palette.text, style = AppType.strong, modifier = Modifier.weight(1f))
            BasicTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(5)
                    text = digits
                    val parsed = digits.toIntOrNull() ?: return@BasicTextField
                    onChange(parsed.coerceIn(0, limit))
                },
                singleLine = true,
                textStyle = AppType.strong.copy(
                    color = palette.text,
                    textAlign = TextAlign.End,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cursorBrush = SolidColor(palette.text),
                modifier = Modifier
                    .width(72.dp)
                    .onFocusChanged { state ->
                        focused = state.isFocused
                        if (!state.isFocused) {
                            val parsed = text.toIntOrNull()?.coerceIn(0, limit) ?: value
                            text = parsed.toString()
                            onChange(parsed)
                        }
                    },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { inner() }
                },
            )
            if (suffix.isNotEmpty()) {
                Text(
                    suffix,
                    color = palette.muted,
                    style = AppType.strong,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = Space.x1),
                )
            }
        }
        Slider(
            value = value.toFloat().coerceIn(0f, sliderLimit.toFloat()),
            onValueChange = { onChange(it.roundToInt().coerceIn(0, limit)) },
            valueRange = 0f..sliderLimit.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (sliderLimit >= limit) {
                "范围 0–$limit$suffix"
            } else {
                "滑条 0–$sliderLimit$suffix，最多可输入 $limit$suffix"
            },
            color = palette.muted,
            style = AppType.caption,
        )
    }
}
