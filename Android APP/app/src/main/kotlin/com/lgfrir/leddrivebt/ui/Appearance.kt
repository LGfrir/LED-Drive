package com.lgfrir.leddrivebt.ui

import android.content.Context
import android.graphics.Typeface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lgfrir.leddrivebt.ui.theme.AppType
import com.lgfrir.leddrivebt.ui.theme.TypeFonts
import com.lgfrir.leddrivebt.ui.theme.MinTouchTarget
import com.lgfrir.leddrivebt.ui.theme.Radius
import com.lgfrir.leddrivebt.ui.theme.Space
import kotlin.math.roundToInt

data class Appearance(
    val dark: Boolean = true,
    /** 只改强调色，亚克力底板保持原色。 */
    val ink: String = INK_SEA,
    /** 五档字号各自一种字体，字号本身不改。 */
    val fontCaption: String = FONT_SANS,
    val fontBody: String = FONT_SANS,
    val fontStrong: String = FONT_SANS,
    val fontTitle: String = FONT_SANS,
    val fontHero: String = FONT_SANS,
    val blur: Int = 8,
    val scrim: Int = 48,
    val acrylic: Int = 28,
    val ioAcrylic: Int = 40,
    val film: Int = 36,
    val backgroundUri: String? = null,
    val keepLink: Boolean = true,
)

data class AppPalette(
    val dark: Boolean,
    val page: Color,
    val text: Color,
    val muted: Color,
    val ok: Color,
    val warn: Color,
    /** 卡片底色。壁纸场景下按 transparency 透出去。 */
    val surface: Color,
    /** 卡片内部的第二层（内容块）。 */
    val surfaceHigh: Color,
    /** 1dp 描边，只给 raised 卡片用。 */
    val hairline: Color,
    /** 强调色，与 MaterialTheme primary 保持一致。 */
    val accent: Color,
)

val DarkPalette = AppPalette(
    dark = true,
    page = Color(0xFF101218),
    text = Color(0xFFF4F6FB),
    muted = Color(0xFFD0D6E2),
    ok = Color(0xFF8FD9A8),
    warn = Color(0xFFFFB4B4),
    surface = Color(0xFF1A2030),
    surfaceHigh = Color(0xFF232B3D),
    hairline = Color(0x38FFFFFF),
    accent = Color(0xFF9ECAFF),
)

const val INK_SEA = "sea"
const val INK_SKY = "sky"
const val INK_INK = "ink"
const val INK_CREAM = "cream"
const val INK_ORANGE = "orange"
const val INK_PINK = "pink"
const val INK_PURPLE = "purple"
const val INK_GREEN = "green"
const val INK_RED = "red"

const val FONT_SANS = "sans"
const val FONT_SERIF = "serif"
const val FONT_MONO = "mono"
const val FONT_MEDIUM = "medium"
const val FONT_LIGHT = "light"
const val FONT_THIN = "thin"
const val FONT_BLACK = "black"
const val FONT_CONDENSED = "condensed"

val FONT_OPTIONS = listOf(
    FONT_SANS to "默认",
    FONT_MEDIUM to "中黑",
    FONT_LIGHT to "细体",
    FONT_THIN to "纤体",
    FONT_BLACK to "粗黑",
    FONT_CONDENSED to "窄体",
    FONT_SERIF to "宋体",
    FONT_MONO to "等宽",
)

fun inkAccent(ink: String, dark: Boolean): Color = when (ink) {
    INK_SKY -> if (dark) Color(0xFFC5E4FF) else Color(0xFF3D92FF)
    INK_INK -> if (dark) Color(0xFFD6D6D6) else Color(0xFF1A1D27)
    INK_CREAM -> if (dark) Color(0xFFE7D3A1) else Color(0xFF8A6A32)
    INK_ORANGE -> if (dark) Color(0xFFFFB074) else Color(0xFFC45A12)
    INK_PINK -> if (dark) Color(0xFFFFB0C8) else Color(0xFFC43B6E)
    INK_PURPLE -> if (dark) Color(0xFFD0B3FF) else Color(0xFF6B3FA0)
    INK_GREEN -> if (dark) Color(0xFF9ED9B0) else Color(0xFF1F7A45)
    INK_RED -> if (dark) Color(0xFFFFB4A8) else Color(0xFFB42318)
    else -> if (dark) Color(0xFF9ECAFF) else Color(0xFF1D4E89)
}

private fun namedFamily(name: String): FontFamily =
    FontFamily(Typeface.create(name, Typeface.NORMAL))

private val fontFamilies = mapOf(
    FONT_SANS to FontFamily.SansSerif,
    FONT_SERIF to FontFamily.Serif,
    FONT_MONO to FontFamily.Monospace,
    FONT_MEDIUM to namedFamily("sans-serif-medium"),
    FONT_LIGHT to namedFamily("sans-serif-light"),
    FONT_THIN to namedFamily("sans-serif-thin"),
    FONT_BLACK to namedFamily("sans-serif-black"),
    FONT_CONDENSED to namedFamily("sans-serif-condensed"),
)

fun fontFamilyOf(id: String): FontFamily = fontFamilies[id] ?: FontFamily.SansSerif

fun Appearance.typeFonts(): TypeFonts = TypeFonts(
    caption = fontFamilyOf(fontCaption),
    body = fontFamilyOf(fontBody),
    strong = fontFamilyOf(fontStrong),
    title = fontFamilyOf(fontTitle),
    hero = fontFamilyOf(fontHero),
)

fun AppPalette.withInk(ink: String): AppPalette = copy(accent = inkAccent(ink, dark))

val LightPalette = AppPalette(
    dark = false,
    page = Color(0xFFEEF1F6),
    text = Color(0xFF1A1D27),
    muted = Color(0xFF4E5668),
    ok = Color(0xFF146C3A),
    warn = Color(0xFFB42318),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFF1F4FA),
    hairline = Color(0x1F000000),
    accent = Color(0xFF1D4E89),
)

val LocalPalette = compositionLocalOf { DarkPalette }
val LocalAcrylic = compositionLocalOf { 28 }
val LocalIoAcrylic = compositionLocalOf { 40 }
val LocalFilm = compositionLocalOf { 36 }

/** 透明度档位换算。数字越小越透，底板/卡片/内容三档共用这一条公式。 */
fun acrylicAlpha(strength: Int): Float =
    0.28f + strength.coerceIn(0, ACRYLIC_MAX) / 100f * 0.64f

@Composable
fun Modifier.acrylic(strength: Int): Modifier {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(Radius.medium)
    return this
        .clip(shape)
        .background(palette.surface.copy(alpha = acrylicAlpha(strength)))
        .border(1.dp, palette.hairline, shape)
}

@Composable
fun AcrylicGroup(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.x3)
            .surfaceCard(CardLevel.Raised)
            .padding(horizontal = Space.x3, vertical = Space.x2),
    ) {
        CompositionLocalProvider(LocalPlateStep provides PlateStep.Raised) {
            content()
        }
    }
}

@Composable
fun IoPlate(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.x4)
            .surfaceCard(CardLevel.Base)
            .padding(horizontal = Space.x3, vertical = Space.x3),
    ) {
        CompositionLocalProvider(LocalPlateStep provides PlateStep.Base) {
            content()
        }
    }
}

private fun onAccent(accent: Color): Color {
    val luma = accent.red * 0.3f + accent.green * 0.59f + accent.blue * 0.11f
    return if (luma > 0.62f) Color(0xFF102033) else Color.White
}

fun AppPalette.scheme() = if (dark) {
    darkColorScheme(
        primary = accent,
        onPrimary = onAccent(accent),
        primaryContainer = accent.copy(alpha = 0.28f),
        onPrimaryContainer = Color.White,
        secondaryContainer = accent.copy(alpha = 0.34f),
        onSecondaryContainer = Color.White,
        background = page,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceHigh,
        onSurfaceVariant = muted,
        outline = hairline,
        outlineVariant = hairline,
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = onAccent(accent),
        primaryContainer = accent.copy(alpha = 0.16f),
        onPrimaryContainer = accent,
        secondaryContainer = accent.copy(alpha = 0.18f),
        onSecondaryContainer = accent,
        background = page,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = Color(0xFFE4EAF3),
        onSurfaceVariant = muted,
        outline = Color(0xFF8A93A5),
        outlineVariant = Color(0xFFCFD6E2),
    )
}

class AppearanceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("leddrive_appearance", Context.MODE_PRIVATE)

    fun load(): Appearance = Appearance(
        dark = prefs.getBoolean(KEY_DARK, true),
        ink = prefs.getString(KEY_INK, INK_SEA) ?: INK_SEA,
        fontCaption = prefs.getString(KEY_FONT_CAPTION, FONT_SANS) ?: FONT_SANS,
        fontBody = prefs.getString(KEY_FONT_BODY, FONT_SANS) ?: FONT_SANS,
        fontStrong = prefs.getString(KEY_FONT_STRONG, FONT_SANS) ?: FONT_SANS,
        fontTitle = prefs.getString(KEY_FONT_TITLE, FONT_SANS) ?: FONT_SANS,
        fontHero = prefs.getString(KEY_FONT_HERO, FONT_SANS) ?: FONT_SANS,
        blur = prefs.getInt(KEY_BLUR, 8).coerceIn(0, BLUR_MAX),
        scrim = prefs.getInt(KEY_SCRIM, 48).coerceIn(0, SCRIM_MAX),
        acrylic = prefs.getInt(KEY_ACRYLIC, 28).coerceIn(0, ACRYLIC_MAX),
        ioAcrylic = prefs.getInt(KEY_IO_ACRYLIC, 40).coerceIn(0, ACRYLIC_MAX),
        film = prefs.getInt(KEY_FILM, 36).coerceIn(0, ACRYLIC_MAX),
        backgroundUri = prefs.getString(KEY_URI, null),
        keepLink = prefs.getBoolean(KEY_KEEP, true),
    )

    fun save(value: Appearance) {
        prefs.edit().apply {
            putBoolean(KEY_DARK, value.dark)
            putString(KEY_INK, value.ink)
            putString(KEY_FONT_CAPTION, value.fontCaption)
            putString(KEY_FONT_BODY, value.fontBody)
            putString(KEY_FONT_STRONG, value.fontStrong)
            putString(KEY_FONT_TITLE, value.fontTitle)
            putString(KEY_FONT_HERO, value.fontHero)
            putInt(KEY_BLUR, value.blur.coerceIn(0, BLUR_MAX))
            putInt(KEY_SCRIM, value.scrim.coerceIn(0, SCRIM_MAX))
            putInt(KEY_ACRYLIC, value.acrylic.coerceIn(0, ACRYLIC_MAX))
            putInt(KEY_IO_ACRYLIC, value.ioAcrylic.coerceIn(0, ACRYLIC_MAX))
            putInt(KEY_FILM, value.film.coerceIn(0, ACRYLIC_MAX))
            if (value.backgroundUri == null) remove(KEY_URI) else putString(KEY_URI, value.backgroundUri)
            putBoolean(KEY_KEEP, value.keepLink)
        }.apply()
    }

    private companion object {
        const val KEY_DARK = "dark"
        const val KEY_INK = "ink"
        const val KEY_FONT_CAPTION = "font_caption"
        const val KEY_FONT_BODY = "font_body"
        const val KEY_FONT_STRONG = "font_strong"
        const val KEY_FONT_TITLE = "font_title"
        const val KEY_FONT_HERO = "font_hero"
        const val KEY_BLUR = "blur"
        const val KEY_SCRIM = "scrim"
        const val KEY_ACRYLIC = "acrylic"
        const val KEY_IO_ACRYLIC = "io_acrylic"
        const val KEY_FILM = "film"
        const val KEY_URI = "background_uri"
        const val KEY_KEEP = "keep_link"
    }
}

/**
 * 读文件 → 限采样 → 解码 → 缩到长边 1920。这条链路只走一次，
 * 之后调模糊只对这一张做 blur，不再重新读文件、重新解码。
 */
fun decodeBaseBackdrop(context: Context, uri: String): Bitmap? {
    val parsed = Uri.parse(uri)
    val resolver = context.applicationContext.contentResolver
    val bytes = resolver.openInputStream(parsed)?.use { it.readBytes() } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > 3200) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    ) ?: return null
    val fitted = fitBitmap(decoded, 1920)
    if (fitted !== decoded) decoded.recycle()
    return fitted
}

/**
 * 对缓存好的底图做模糊。
 * [scale] > 1 时先按 1/scale 缩小再模糊，用于拖动滑块时的低分辨率预览；
 * 松手后再用 scale = 1 出终图。
 */
fun blurBackdrop(base: Bitmap, amount: Int, scale: Int = 1): ImageBitmap {
    val scaled = scale > 1
    val source = if (scaled) {
        Bitmap.createScaledBitmap(
            base,
            (base.width / scale).coerceAtLeast(1),
            (base.height / scale).coerceAtLeast(1),
            true,
        )
    } else {
        base
    }
    val out = soften(source, amount)
    // 只回收自己创建的缩放副本；base 要留着给下一次模糊用。
    if (scaled && out !== source) source.recycle()
    return out.asImageBitmap()
}

fun holdBackground(context: Context, uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    context.applicationContext.contentResolver.takePersistableUriPermission(uri, flags)
}

fun releaseBackground(context: Context, uri: String) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    runCatching {
        context.applicationContext.contentResolver.releasePersistableUriPermission(Uri.parse(uri), flags)
    }
}

private fun fitBitmap(source: Bitmap, longest: Int): Bitmap {
    val edge = maxOf(source.width, source.height)
    if (edge <= longest) return source
    val scale = longest.toFloat() / edge
    return Bitmap.createScaledBitmap(
        source,
        (source.width * scale).toInt().coerceAtLeast(1),
        (source.height * scale).toInt().coerceAtLeast(1),
        true,
    )
}

private fun soften(source: Bitmap, amount: Int): Bitmap {
    if (amount <= 0) return source
    // 最长边约 1080。滑块大约每加 3，模糊半径加 1，拉满大约 8 个像素。
    val radius = ((amount * 2 + 2) / 5).coerceIn(1, 8)
    return boxBlur(source, radius)
}

private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
    val width = source.width
    val height = source.height
    val src = IntArray(width * height)
    source.getPixels(src, 0, width, 0, 0, width, height)
    val tmp = IntArray(src.size)
    blurAxis(src, tmp, width, height, radius, horizontal = true)
    blurAxis(tmp, src, width, height, radius, horizontal = false)
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(src, 0, width, 0, 0, width, height)
    return out
}

private fun blurAxis(
    src: IntArray,
    dst: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean,
) {
    val span = radius * 2 + 1
    if (horizontal) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (k in -radius..radius) {
                    val color = src[row + (x + k).coerceIn(0, width - 1)]
                    a += color ushr 24
                    r += (color shr 16) and 0xFF
                    g += (color shr 8) and 0xFF
                    b += color and 0xFF
                }
                dst[row + x] = (a / span shl 24) or (r / span shl 16) or (g / span shl 8) or (b / span)
            }
        }
    } else {
        for (x in 0 until width) {
            for (y in 0 until height) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (k in -radius..radius) {
                    val color = src[(y + k).coerceIn(0, height - 1) * width + x]
                    a += color ushr 24
                    r += (color shr 16) and 0xFF
                    g += (color shr 8) and 0xFF
                    b += color and 0xFF
                }
                dst[y * width + x] = (a / span shl 24) or (r / span shl 16) or (g / span shl 8) or (b / span)
            }
        }
    }
}

const val BLUR_MAX = 20
const val SCRIM_MAX = 80
const val ACRYLIC_MAX = 100

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearancePanel(
    appearance: Appearance,
    imageReady: Boolean,
    imageFailed: Boolean,
    onDark: (Boolean) -> Unit,
    onInk: (String) -> Unit,
    onFont: (String, String) -> Unit,
    onBlur: (Int) -> Unit,
    onBlurPreview: (Int) -> Unit,
    onScrim: (Int) -> Unit,
    onAcrylic: (Int) -> Unit,
    onIoAcrylic: (Int) -> Unit,
    onFilm: (Int) -> Unit,
    onPick: (Uri) -> Unit,
    onClear: () -> Unit,
) {
    val palette = LocalPalette.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(uri)
    }
    AcrylicGroup {
        StrengthSlider("底板", appearance.ioAcrylic, ACRYLIC_MAX, onIoAcrylic, live = true)
        StrengthSlider("卡片", appearance.acrylic, ACRYLIC_MAX, onAcrylic, live = true)
        StrengthSlider("内容", appearance.film, ACRYLIC_MAX, onFilm, live = true)
        Text(
            "数字越小越透。从外到内是底板、卡片、内容。",
            color = palette.muted,
            style = AppType.caption,
            modifier = Modifier.padding(top = Space.x1),
        )
        Column(
            Modifier
                .padding(top = Space.x3)
                .fillMaxWidth()
                .acrylic(appearance.ioAcrylic)
                .padding(Space.x3),
        ) {
            Text("底板", color = palette.muted, style = AppType.caption)
            Column(
                Modifier
                    .padding(top = Space.x2)
                    .fillMaxWidth()
                    .acrylic(appearance.acrylic)
                    .padding(Space.x3),
            ) {
                Text("卡片", color = palette.text, style = AppType.caption)
                Text(
                    "内容",
                    color = palette.text,
                    style = AppType.caption,
                    modifier = Modifier
                        .padding(top = Space.x2)
                        .fillMaxWidth()
                        .acrylic(appearance.film)
                        .padding(Space.x3),
                )
            }
        }
    }
    AcrylicGroup {
        Text("主题", color = palette.text, style = AppType.strong)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x3), modifier = Modifier.padding(top = Space.x1)) {
            Choice("浅色", !appearance.dark) { onDark(false) }
            Choice("深色", appearance.dark) { onDark(true) }
        }
        Text("主题色", color = palette.muted, style = AppType.caption, modifier = Modifier.padding(top = Space.x3))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.x2),
            verticalArrangement = Arrangement.spacedBy(Space.x2),
            modifier = Modifier.padding(top = Space.x2),
        ) {
            Choice("海蓝", appearance.ink == INK_SEA) { onInk(INK_SEA) }
            Choice("晴空", appearance.ink == INK_SKY) { onInk(INK_SKY) }
            Choice("墨色", appearance.ink == INK_INK) { onInk(INK_INK) }
            Choice("米金", appearance.ink == INK_CREAM) { onInk(INK_CREAM) }
            Choice("暖橙", appearance.ink == INK_ORANGE) { onInk(INK_ORANGE) }
            Choice("桃粉", appearance.ink == INK_PINK) { onInk(INK_PINK) }
            Choice("藤紫", appearance.ink == INK_PURPLE) { onInk(INK_PURPLE) }
            Choice("松绿", appearance.ink == INK_GREEN) { onInk(INK_GREEN) }
            Choice("朱红", appearance.ink == INK_RED) { onInk(INK_RED) }
        }
        Text(
            "只改按钮和选中色。亚克力白板保持原来的颜色。",
            color = palette.muted,
            style = AppType.caption,
            modifier = Modifier.padding(top = Space.x1),
        )
        Text("字体", color = palette.text, style = AppType.strong, modifier = Modifier.padding(top = Space.x4))
        Text(
            "字号不变。同一档字号共用一种字体。",
            color = palette.muted,
            style = AppType.caption,
            modifier = Modifier.padding(top = Space.x1),
        )
        FontRole("辅助", "caption", appearance.fontCaption, onFont)
        FontRole("正文", "body", appearance.fontBody, onFont)
        FontRole("强调", "strong", appearance.fontStrong, onFont)
        FontRole("标题", "title", appearance.fontTitle, onFont)
        FontRole("主数字", "hero", appearance.fontHero, onFont)
    }
    AcrylicGroup {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
            OutlinedButton(
                onClick = { picker.launch(arrayOf("image/*")) },
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) { Text("选择图片") }
            if (appearance.backgroundUri != null) {
                OutlinedButton(onClick = onClear, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text("清除") }
            }
        }
        if (imageFailed) {
            Text("图片无法打开。", color = palette.warn, style = AppType.body, modifier = Modifier.padding(top = Space.x2))
        } else if (appearance.backgroundUri != null && !imageReady) {
            Text("正在处理", color = palette.muted, style = AppType.body, modifier = Modifier.padding(top = Space.x2))
        }
        if (appearance.backgroundUri != null) {
            StrengthSlider("模糊", appearance.blur, BLUR_MAX, onBlur, onPreview = onBlurPreview)
            StrengthSlider("遮罩", appearance.scrim, SCRIM_MAX, onScrim)
            Text(
                "遮罩为当前主题底色。模糊 0 为原图。",
                color = palette.muted,
                style = AppType.caption,
                modifier = Modifier.padding(top = Space.x1),
            )
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .heightIn(min = MinTouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) palette.accent else palette.muted,
            style = if (selected) AppType.strong else AppType.body,
        )
    }
}

@Composable
private fun FontRole(
    label: String,
    role: String,
    selected: String,
    onFont: (String, String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    val current = FONT_OPTIONS.firstOrNull { it.first == selected }?.second ?: "默认"
    val shape = RoundedCornerShape(Radius.medium)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            color = palette.muted,
            style = AppType.caption,
            modifier = Modifier.width(56.dp).heightIn(min = MinTouchTarget).wrapContentHeight(),
        )
        Column(Modifier.weight(1f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .clip(shape)
                    .border(1.dp, palette.hairline, shape)
                    .clickable { open = !open }
                    .padding(horizontal = Space.x3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    current,
                    color = palette.text,
                    style = AppType.body.copy(fontFamily = fontFamilyOf(selected)),
                    maxLines = 1,
                )
                ChevronDown(palette.text)
            }
            if (open) {
                PenetratingMenu(FONT_OPTIONS.size) {
                    FONT_OPTIONS.forEach { (id, name) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = MinTouchTarget)
                                .clickable {
                                    open = false
                                    onFont(role, id)
                                }
                                .padding(horizontal = Space.x3),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                name,
                                color = if (id == selected) palette.accent else palette.text,
                                style = AppType.body.copy(fontFamily = fontFamilyOf(id)),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StrengthSlider(
    label: String,
    value: Int,
    max: Int,
    onFinish: (Int) -> Unit,
    live: Boolean = false,
    onPreview: ((Int) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    var dragging by remember { mutableIntStateOf(value) }
    var moving by remember { mutableStateOf(false) }
    LaunchedEffect(value, moving) {
        if (!moving) dragging = value
    }
    val shown = if (moving) dragging else value
    Row(
        Modifier.fillMaxWidth().padding(top = Space.x2),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = palette.text, style = AppType.strong)
        Text("$shown", color = palette.muted, style = AppType.body)
    }
    Slider(
        value = shown.toFloat(),
        onValueChange = {
            moving = true
            val next = it.roundToInt().coerceIn(0, max)
            dragging = next
            if (live) onFinish(next)
            onPreview?.invoke(next)
        },
        onValueChangeFinished = {
            moving = false
            onFinish(dragging)
        },
        valueRange = 0f..max.toFloat(),
        modifier = Modifier.fillMaxWidth(),
    )
}
