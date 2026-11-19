package com.lgfrir.leddrivebt.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 全站设计变量。界面里不再写 12.dp / 14.sp 这类魔法数，一律从这里取。
 * 只有这里的值可以改；改一处，全站跟着走。
 */

/** 间距标尺，只保留这六档。 */
object Space {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x6 = 24.dp
    val x8 = 32.dp
}

/** 圆角三档。卡片默认 medium，整块底板用 large。 */
object Radius {
    val small = 10.dp
    val medium = 14.dp
    val large = 20.dp
}

/** 动效时长（毫秒）。 */
object Motion {
    const val press = 120
    const val enter = 180
    const val sameLevel = 120
    const val crossfade = 300
}

/**
 * 五档字号各自用一种字体。字号本身不变，只换字形。
 */
data class TypeFonts(
    val caption: FontFamily = FontFamily.Default,
    val body: FontFamily = FontFamily.Default,
    val strong: FontFamily = FontFamily.Default,
    val title: FontFamily = FontFamily.Default,
    val hero: FontFamily = FontFamily.Default,
)

/** 五档样式只在换字体时生成一次，滚动和拖滑条时复用同一份。 */
data class TypeScale(
    val caption: TextStyle,
    val body: TextStyle,
    val strong: TextStyle,
    val title: TextStyle,
    val hero: TextStyle,
)

fun typeScaleOf(fonts: TypeFonts): TypeScale = TypeScale(
    caption = TextStyle(fontFamily = fonts.caption, fontSize = 11.sp, lineHeight = 15.sp),
    body = TextStyle(fontFamily = fonts.body, fontSize = 13.sp, lineHeight = 18.sp),
    strong = TextStyle(
        fontFamily = fonts.strong,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    title = TextStyle(
        fontFamily = fonts.title,
        fontSize = 20.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.Medium,
    ),
    hero = TextStyle(
        fontFamily = fonts.hero,
        fontSize = 28.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Medium,
    ),
)

val LocalTypeScale = compositionLocalOf { typeScaleOf(TypeFonts()) }

/**
 * 字阶：11 辅助 / 13 正文 / 15 强调 / 20 页标题 / 28 主指标，行高 1.35。
 * 字号定死。同一档字号共用 [LocalTypeScale] 里的那一种字体。
 */
object AppType {
    val caption: TextStyle
        @Composable get() = LocalTypeScale.current.caption
    val body: TextStyle
        @Composable get() = LocalTypeScale.current.body
    val strong: TextStyle
        @Composable get() = LocalTypeScale.current.strong
    val title: TextStyle
        @Composable get() = LocalTypeScale.current.title
    val hero: TextStyle
        @Composable get() = LocalTypeScale.current.hero
}

/** 可点区域下限，不够 44dp 的控件一律撑到这个尺寸。 */
val MinTouchTarget = 44.dp
