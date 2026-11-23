/**
 * @file disp_dash.c
 * @author LG_frir
 * @date 2026-11-17
 * @brief 单页面仪表盘实现：5x7 点阵 + 帧缓冲 + 脏区刷新（不依赖 LVGL）
 *
 * 设计原则：少而整齐。所有数值按固定列对齐，大间距分区，颜色只用
 * 青（结构）/ 白（数值）/ 灰（标签）/ 档位色（占用率）四类。
 *
 * 版面（160x128）：
 *   0..13    顶栏：左=本机蓝牙名，右=对端名 + 信号
 *   14..15   高亮分隔线
 *   22..35   时钟（居中，scale 2）
 *   39..45   日期 + 运行时长（居中）
 *   50..56   一行日志
 *   60       分隔线
 *   66..74   页头：页指示块 + IO 名称 + 手动/定时
 *   80..92   亮度：大号百分比 + 通栏条
 *   98..104  参数表第 1 行：LED / REFR
 *   108..114 参数表第 2 行：CPU / NEXT
 *   124..127 底部三色条
 */

#include "driver/display/disp_dash.h"

#include "driver/display/lcd.h"

#include "esp_check.h"
#include "esp_heap_caps.h"
#include "esp_log.h"

#include <stdio.h>
#include <string.h>

static const char *TAG = "DASH";

/* ========================= 配色（只用四类） ========================= */
#define C_BG      LCD_RGB565(6, 9, 16)
#define C_PANEL   LCD_RGB565(14, 22, 34)
#define C_TEXT    LCD_RGB565(232, 244, 255)   /* 数值 */
#define C_DIM     LCD_RGB565(108, 132, 156)   /* 标签 */
#define C_DIM2    LCD_RGB565(70, 90, 110)
#define C_ACCENT  LCD_RGB565(0, 220, 255)     /* 结构：标题/条/强调 */
#define C_ACCENT2 LCD_RGB565(0, 255, 170)
#define C_BLUE    LCD_RGB565(88, 150, 255)
#define C_AMBER   LCD_RGB565(255, 186, 64)
#define C_WARN    LCD_RGB565(255, 104, 76)
#define C_LINE    LCD_RGB565(30, 46, 62)
#define C_BAR_BG  LCD_RGB565(32, 48, 66)
#define C_ON_DARK LCD_RGB565(6, 9, 16)

/* ========================= 版面 ========================= */
#define FB_W LCD_H_RES
#define FB_H LCD_V_RES

#define H_HDR      14
#define Y_ACCENT   (H_HDR)
#define Y_CLOCK    22      /* scale 3 = 21px，22..42，上下各留 5~7px */
#define Y_LOG      48
#define Y_SEP      58
#define Y_HEAD     62
#define Y_HERO     73      /* 大号 CPU 百分比（scale 2）73..86 */
#define Y_HERO_BAR 91      /* 细进度条：和文字留 5px */
#define Y_R1       99
#define Y_R2       108
#define Y_HIST     116     /* 底部一行：TCPU + 百分比（暂时不画柱状图） */

#define IO_SWITCH_SEC  12
#define CPU_SWITCH_SEC 4

#define MARGIN     10      /* 左右内边距，内容整体往中间收 */

/* ========================= 5x7 点阵 ========================= */
#define FONT_W 5
#define FONT_H 7

static const uint8_t k_font[][FONT_W] = {
    /* 0-9 */
    { 0x3E, 0x51, 0x49, 0x45, 0x3E },
    { 0x00, 0x42, 0x7F, 0x40, 0x00 },
    { 0x42, 0x61, 0x51, 0x49, 0x46 },
    { 0x21, 0x41, 0x45, 0x4B, 0x31 },
    { 0x18, 0x14, 0x12, 0x7F, 0x10 },
    { 0x27, 0x45, 0x45, 0x45, 0x39 },
    { 0x3C, 0x4A, 0x49, 0x49, 0x30 },
    { 0x01, 0x71, 0x09, 0x05, 0x03 },
    { 0x36, 0x49, 0x49, 0x49, 0x36 },
    { 0x06, 0x49, 0x49, 0x29, 0x1E },
    /* A-Z */
    { 0x7E, 0x11, 0x11, 0x11, 0x7E }, /* A */
    { 0x7F, 0x49, 0x49, 0x49, 0x36 }, /* B */
    { 0x3E, 0x41, 0x41, 0x41, 0x22 }, /* C */
    { 0x7F, 0x41, 0x41, 0x22, 0x1C }, /* D */
    { 0x7F, 0x49, 0x49, 0x49, 0x41 }, /* E */
    { 0x7F, 0x09, 0x09, 0x09, 0x01 }, /* F */
    { 0x3E, 0x41, 0x49, 0x49, 0x7A }, /* G */
    { 0x7F, 0x08, 0x08, 0x08, 0x7F }, /* H */
    { 0x00, 0x41, 0x7F, 0x41, 0x00 }, /* I */
    { 0x20, 0x40, 0x41, 0x3F, 0x01 }, /* J */
    { 0x7F, 0x08, 0x14, 0x22, 0x41 }, /* K */
    { 0x7F, 0x40, 0x40, 0x40, 0x40 }, /* L */
    { 0x7F, 0x02, 0x0C, 0x02, 0x7F }, /* M */
    { 0x7F, 0x04, 0x08, 0x10, 0x7F }, /* N */
    { 0x3E, 0x41, 0x41, 0x41, 0x3E }, /* O */
    { 0x7F, 0x09, 0x09, 0x09, 0x06 }, /* P */
    { 0x3E, 0x41, 0x51, 0x21, 0x5E }, /* Q */
    { 0x7F, 0x09, 0x19, 0x29, 0x46 }, /* R */
    { 0x46, 0x49, 0x49, 0x49, 0x31 }, /* S */
    { 0x01, 0x01, 0x7F, 0x01, 0x01 }, /* T */
    { 0x3F, 0x40, 0x40, 0x40, 0x3F }, /* U */
    { 0x1F, 0x20, 0x40, 0x20, 0x1F }, /* V */
    { 0x3F, 0x40, 0x38, 0x40, 0x3F }, /* W */
    { 0x63, 0x14, 0x08, 0x14, 0x63 }, /* X */
    { 0x07, 0x08, 0x70, 0x08, 0x07 }, /* Y */
    { 0x61, 0x51, 0x49, 0x45, 0x43 }, /* Z */
    /* 标点 */
    { 0x00, 0x00, 0x00, 0x00, 0x00 }, /* 空格 */
    { 0x00, 0x36, 0x00, 0x00, 0x00 }, /* : 单列细点，放第 1 列让左右间距对称 */
    { 0x60, 0x60, 0x00, 0x00, 0x00 }, /* . */
    { 0x08, 0x08, 0x08, 0x08, 0x08 }, /* - */
    { 0x23, 0x13, 0x08, 0x64, 0x62 }, /* % */
    { 0x20, 0x10, 0x08, 0x04, 0x02 }, /* / */
    { 0x00, 0x1C, 0x22, 0x41, 0x00 }, /* ( */
    { 0x00, 0x41, 0x22, 0x1C, 0x00 }, /* ) */
    { 0x14, 0x7F, 0x14, 0x7F, 0x14 }, /* # */
    { 0x08, 0x08, 0x3E, 0x08, 0x08 }, /* + */
    { 0x00, 0x00, 0x5F, 0x00, 0x00 }, /* ! */
    { 0x02, 0x01, 0x51, 0x09, 0x06 }, /* ? */
    { 0x00, 0x50, 0x30, 0x00, 0x00 }, /* , */
    { 0x08, 0x14, 0x22, 0x41, 0x00 }, /* < */
    { 0x41, 0x22, 0x14, 0x08, 0x00 }, /* > */
    { 0x14, 0x08, 0x3E, 0x08, 0x14 }, /* * */
    { 0x14, 0x14, 0x14, 0x14, 0x14 }, /* = */
    { 0x40, 0x40, 0x40, 0x40, 0x40 }, /* _ */
    { 0x00, 0x7F, 0x41, 0x41, 0x00 }, /* [ */
    { 0x00, 0x41, 0x41, 0x7F, 0x00 }, /* ] */
};

static int glyph_index(char c)
{
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'z') {
        c = (char)(c - 'a' + 'A');
    }
    if (c >= 'A' && c <= 'Z') {
        return 10 + (c - 'A');
    }
    switch (c) {
    case ' ': return 36;
    case ':': return 37;
    case '.': return 38;
    case '-': return 39;
    case '%': return 40;
    case '/': return 41;
    case '(': return 42;
    case ')': return 43;
    case '#': return 44;
    case '+': return 45;
    case '!': return 46;
    case '?': return 47;
    case ',': return 48;
    case '<': return 49;
    case '>': return 50;
    case '*': return 51;
    case '=': return 52;
    case '_': return 53;
    case '[': return 54;
    case ']': return 55;
    default:  return 36;
    }
}

/* ========================= 帧缓冲 ========================= */
static uint16_t *s_fb = NULL;
static int d_y0 = 0, d_y1 = 0;

static void dirty_mark(int y, int h)
{
    if (y < 0) { h += y; y = 0; }
    if (y + h > FB_H) { h = FB_H - y; }
    if (h <= 0) {
        return;
    }
    if (d_y1 <= d_y0) {
        d_y0 = y;
        d_y1 = y + h;
        return;
    }
    if (y < d_y0) { d_y0 = y; }
    if (y + h > d_y1) { d_y1 = y + h; }
}

static void dirty_clear(void)
{
    d_y0 = d_y1 = 0;
}

/*
 * 关键：lcd_draw_bitmap() 只发一段【连续】内存，而帧缓冲每行是 160 像素。
 * 只能按整行宽推送、只裁剪行范围；传窄矩形会把下一行数据当成这一行剩下的
 * 像素发出去，屏上就是画面错位、字被切碎。
 */
static void fb_flush(void)
{
    if (s_fb == NULL || d_y1 <= d_y0) {
        dirty_clear();
        return;
    }
    lcd_draw_bitmap(0, d_y0, FB_W, d_y1, &s_fb[d_y0 * FB_W]);
    dirty_clear();
}

static void fb_rect(int x, int y, int w, int h, uint16_t color)
{
    if (s_fb == NULL) {
        return;
    }
    if (x < 0) { w += x; x = 0; }
    if (y < 0) { h += y; y = 0; }
    if (x + w > FB_W) { w = FB_W - x; }
    if (y + h > FB_H) { h = FB_H - y; }
    if (w <= 0 || h <= 0) {
        return;
    }
    for (int row = y; row < y + h; row++) {
        uint16_t *p = &s_fb[row * FB_W + x];
        for (int i = 0; i < w; i++) {
            p[i] = color;
        }
    }
    dirty_mark(y, h);
}

static void fb_fill(uint16_t color)
{
    fb_rect(0, 0, FB_W, FB_H, color);
}

/* 字符步进：标点比数字窄一格，避免冒号两侧空一大片 */
static int advance_for(char c, int scale)
{
    if (c == ':' || c == '.') {
        return (FONT_W - 1) * scale;      /* 4*scale */
    }
    return (FONT_W + 1) * scale;          /* 6*scale */
}

static int text_w(const char *s, int scale);

static void fb_text(int x, int y, const char *s, int scale, uint16_t fg, uint16_t bg)
{
    if (s_fb == NULL || s == NULL || scale < 1 || scale > 4) {
        return;
    }
    int len = (int)strlen(s);
    if (len <= 0) {
        return;
    }

    int w = text_w(s, scale);
    int h = FONT_H * scale;
    fb_rect(x, y, w, h, bg);

    int pen = 0;
    for (int i = 0; i < len; i++) {
        const uint8_t *g = k_font[glyph_index(s[i])];
        for (int col = 0; col < FONT_W; col++) {
            for (int row = 0; row < FONT_H; row++) {
                if ((g[col] & (1u << row)) == 0) {
                    continue;
                }
                fb_rect(x + pen + col * scale, y + row * scale, scale, scale, fg);
            }
        }
        pen += advance_for(s[i], scale);
    }
}

static int text_w(const char *s, int scale)
{
    int w = 0;

    if (s == NULL) {
        return 0;
    }
    for (; *s != '\0'; s++) {
        w += advance_for(*s, scale);
    }
    return (w > 0) ? (w - scale) : 0;
}

static void fb_text_c(int y, const char *s, int scale, uint16_t fg, uint16_t bg)
{
    fb_text((FB_W - text_w(s, scale)) / 2, y, s, scale, fg, bg);
}

static void fb_text_r(int y, const char *s, int scale, uint16_t fg, uint16_t bg)
{
    fb_text(FB_W - text_w(s, scale) - MARGIN, y, s, scale, fg, bg);
}

/* 取前 n 个字符 */
static void trunc_copy(char *dst, const char *src, int n)
{
    if (dst == NULL) {
        return;
    }
    if (src == NULL) {
        dst[0] = '\0';
        return;
    }
    int i = 0;
    while (src[i] != '\0' && i < n) {
        dst[i] = src[i];
        i++;
    }
    dst[i] = '\0';
}

/* HUD 上角括符（只在事件后出现） */
static void hud_corners(void)
{
    fb_rect(0, 0, 7, 2, C_ACCENT);
    fb_rect(0, 0, 2, 7, C_ACCENT);
    fb_rect(FB_W - 7, 0, 7, 2, C_ACCENT);
    fb_rect(FB_W - 2, 0, 2, 7, C_ACCENT);
}

/* ========================= 状态 ========================= */
static dash_status_t s_st;
static bool          s_st_valid = false;
static bool          s_ota_active = false;

static dash_io_t s_ios[DASH_IO_MAX];
static uint8_t   s_io_count = 0;

static dash_ble_t s_ble;

static uint8_t  s_page = 0;
static bool     s_panel_dirty = true;

static dash_io_t s_rendered_io;
static uint8_t   s_rendered_page = 0xFF;

static uint8_t  s_cpu_idx = 0;
static bool     s_cpu_dirty = true;
static uint8_t  s_drawn_cpu_idx = 0xFF;
static uint32_t s_drawn_power_mw = 0xFFFFFFFFu;
static uint32_t s_drawn_next = 0xFFFFFFFFu;

static char     s_log_msg[26];
static bool     s_log_has = false;

static bool     s_brackets_on = false;
static uint32_t s_flash_until = 0;
static uint32_t s_tick = 0;

static char     s_hdr_name[DASH_NAME_MAX];
static char     s_hdr_peer[DASH_NAME_MAX];
static bool     s_hdr_conn = false;

static uint8_t page_count(void)
{
    return (s_io_count > 0) ? s_io_count : 1;
}

esp_err_t dash_init(void)
{
    if (s_fb == NULL) {
        s_fb = heap_caps_malloc(FB_W * FB_H * sizeof(uint16_t),
                                MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
        ESP_RETURN_ON_FALSE(s_fb != NULL, ESP_ERR_NO_MEM, TAG, "no memory for frame buffer");
    }
    s_log_has = false;
    s_st_valid = false;
    s_rendered_page = 0xFF;
    s_panel_dirty = true;
    s_cpu_dirty = true;
    s_brackets_on = false;
    s_flash_until = 0;
    s_tick = 0;
    s_hdr_name[0] = '\0';
    s_hdr_peer[0] = '\0';
    ESP_LOGI(TAG, "dashboard ready, frame buffer %d KB", (int)(FB_W * FB_H * 2 / 1024));
    return ESP_OK;
}

/* ========================= 顶栏 ========================= */
static void draw_header(void)
{
    char name[DASH_NAME_MAX];
    char peer[DASH_NAME_MAX];
    char rssi[8];

    fb_rect(0, 0, FB_W, H_HDR, C_PANEL);

    trunc_copy(name, s_ble.device_name != NULL ? s_ble.device_name : "LEDDRIVE", 10);
    fb_text(MARGIN, 4, name, 1, C_ACCENT, C_PANEL);

    if (s_ble.connected) {
        trunc_copy(peer, s_ble.peer_name != NULL ? s_ble.peer_name : "PHONE", 10);
        snprintf(rssi, sizeof(rssi), "%d", (int)s_ble.rssi);

        uint16_t rc = (s_ble.rssi >= -55) ? C_ACCENT2 : ((s_ble.rssi >= -70) ? C_ACCENT : C_AMBER);
        int x = FB_W - 6 - text_w(rssi, 1);
        fb_text(x, 4, rssi, 1, rc, C_PANEL);
        x -= text_w(peer, 1) + 6;
        fb_text(x, 4, peer, 1, C_TEXT, C_PANEL);
        fb_rect(x - 8, 5, 4, 4, C_ACCENT2);     /* 连接状态小点 */
    } else {
        fb_text_r(4, "NO LINK", 1, C_DIM, C_PANEL);
    }

    fb_rect(0, Y_ACCENT, FB_W, 1, C_ACCENT);
    fb_rect(0, Y_ACCENT + 1, FB_W, 1, C_LINE);

    if (s_brackets_on) {
        hud_corners();
    }

    trunc_copy(s_hdr_name, s_ble.device_name != NULL ? s_ble.device_name : "LEDDRIVE", 10);
    trunc_copy(s_hdr_peer, s_ble.connected ? (s_ble.peer_name ? s_ble.peer_name : "PHONE") : "",
               10);
    s_hdr_conn = s_ble.connected;
}

/* 底部：总 CPU 占用，暂时不画柱状图 */
static uint8_t total_cpu(void)
{
    uint32_t sum = 0;

    for (int i = 0; i < s_io_count; i++) {
        sum += s_ios[i].cpu_pct;
    }
    sum += s_ble.cpu_pct;

    return (uint8_t)((sum > 100u) ? 100u : sum);
}

static uint16_t cpu_tier_color(uint8_t pct)
{
    if (pct >= 70) {
        return C_WARN;
    }
    if (pct >= 40) {
        return C_AMBER;
    }
    return C_ACCENT2;
}

static void draw_history(void)
{
    char buf[16];
    uint8_t t = total_cpu();

    fb_rect(0, Y_HIST - 1, FB_W, FB_H - (Y_HIST - 1), C_BG);
    fb_text(MARGIN, Y_HIST + 4, "TCPU", 1, C_DIM, C_BG);
    snprintf(buf, sizeof(buf), "%u%%", (unsigned)t);
    fb_text(MARGIN + 28, Y_HIST + 4, buf, 1, cpu_tier_color(t), C_BG);
}

/* ========================= 时钟 / 日期 / 日志 ========================= */
static void draw_clock(const dash_status_t *st)
{
    char buf[16];

    fb_rect(0, Y_CLOCK, FB_W, 22, C_BG);

    if (!st->time_valid) {
        fb_text_c(Y_CLOCK, "--:--:--", 3, C_DIM, C_BG);
        return;
    }
    snprintf(buf, sizeof(buf), "%02u:%02u:%02u", (unsigned)st->hour, (unsigned)st->minute,
             (unsigned)st->second);
    fb_text_c(Y_CLOCK, buf, 3, C_TEXT, C_BG);
}

static void draw_log(void)
{
    char buf[26];

    fb_rect(0, Y_LOG, FB_W, 9, C_BG);
    fb_text(MARGIN, Y_LOG, ">", 1, C_ACCENT2, C_BG);

    if (!s_log_has) {
        fb_text(MARGIN + 10, Y_LOG, "SYSTEM READY", 1, C_DIM, C_BG);
        return;
    }
    strncpy(buf, s_log_msg, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';
    buf[23] = '\0';
    fb_text(MARGIN + 10, Y_LOG, buf, 1, C_TEXT, C_BG);
}

/* ========================= 轮播面板 ========================= */
/* 表格行：左列标签/数值 + 右列标签/数值（两列拉开，上下对齐） */
static void table_row(int y, const char *l1, const char *v1, uint16_t c1,
                      const char *l2, const char *v2, uint16_t c2)
{
    fb_text(MARGIN, y, l1, 1, C_DIM, C_BG);
    fb_text(MARGIN + 36, y, v1, 1, c1, C_BG);
    fb_text(94, y, l2, 1, C_DIM, C_BG);
    fb_text(120, y, v2, 1, c2, C_BG);
}

/* 轮播面板：页头 + 大号 CPU 占用（细条）+ 四行参数表 */
static void draw_panel(void)
{
    char buf[40];
    char v[12];
    const dash_io_t *io;
    const dash_io_t *cur_io;
    float current_a;
    uint8_t cur_pct;
    uint16_t cur_col;
    uint8_t bri_pct;
    uint16_t bri_col;
    uint16_t seg_total = 0;

    if (s_io_count == 0) {
        return;
    }

    io = &s_ios[s_page];
    cur_io = &s_ios[(s_cpu_idx < s_io_count) ? s_cpu_idx : 0];

    /*
     * 单颗全白 6.25mA @12V，即 75mW：
     *   实际电流 = power_mw / 12000
     *   进度条满格 = led_count × 6.25mA = led_count × 75mW
     */
    current_a = (float)cur_io->power_mw / 12000.0f;
    {
        uint32_t max_mw = (uint32_t)cur_io->led_count * 75u;
        if (max_mw > 0) {
            uint32_t pct = (cur_io->power_mw * 100u + max_mw / 2u) / max_mw;
            cur_pct = (uint8_t)((pct > 100u) ? 100u : pct);
        } else {
            cur_pct = 0;
        }
    }
    cur_col = (cur_pct >= 85) ? C_WARN : ((cur_pct >= 55) ? C_AMBER : C_ACCENT2);

    bri_pct = (uint8_t)(io->brightness * 100u / 255u);
    bri_col = (bri_pct < 30) ? C_BLUE : ((bri_pct < 70) ? C_ACCENT : C_ACCENT2);

    for (int i = 0; i < s_io_count; i++) {
        seg_total += s_ios[i].seg_count;
    }

    fb_rect(0, Y_HEAD - 1, FB_W, (Y_HIST - 1) - (Y_HEAD - 1), C_BG);

    /* 页头：指示块 + IO 名称 + 手动/定时 */
    for (int i = 0; i < page_count() && i < DASH_IO_MAX; i++) {
        bool cur = (i == (int)s_page);
        fb_rect(MARGIN + i * 8, Y_HEAD + (cur ? 0 : 1), cur ? 5 : 4, cur ? 7 : 5,
                cur ? C_ACCENT : C_LINE);
    }

    snprintf(buf, sizeof(buf), "IO%u  %s", (unsigned)io->id, (io->name != NULL) ? io->name : "");
    fb_text(28, Y_HEAD, buf, 1, C_ACCENT, C_BG);

    {
        int tag_x = FB_W - 36;
        fb_rect(tag_x, Y_HEAD - 1, 31, 9, io->time_mode ? C_ACCENT2 : C_BLUE);
        fb_text(tag_x + 5, Y_HEAD, io->time_mode ? "TIM" : "MAN", 1, C_ON_DARK,
                io->time_mode ? C_ACCENT2 : C_BLUE);
    }

    /* 大号单路电流 */
    fb_text(MARGIN, Y_HERO + 4, "CURR", 1, C_DIM, C_BG);
    snprintf(buf, sizeof(buf), "%.2fA", current_a);
    fb_text(MARGIN + 32, Y_HERO, buf, 2, cur_col, C_BG);
    fb_text_r(Y_HERO + 4, cur_io->dma ? "DMA" : "48S", 1,
              cur_io->dma ? C_ACCENT2 : C_WARN, C_BG);

    /* 细进度条 */
    {
        int bar_x = MARGIN, bar_w = FB_W - 2 * MARGIN, bar_h = 5, bar_y = Y_HERO_BAR;
        fb_rect(bar_x, bar_y, bar_w, bar_h, C_BAR_BG);
        int fill = (bar_w - 2) * cur_pct / 100;
        if (fill > 0) {
            fb_rect(bar_x + 1, bar_y + 1, fill, bar_h - 2, cur_col);
        }
    }

    /* 四个信息项，分两行铺开 */
    snprintf(v, sizeof(v), "%u", (unsigned)io->led_count);
    snprintf(buf, sizeof(buf), "%uMS", (unsigned)io->refresh_ms);
    table_row(Y_R1, "LED", v, C_TEXT, "REFR", buf, C_TEXT);

    snprintf(v, sizeof(v), "%u%%", (unsigned)bri_pct);
    if (s_st.next_event_sec > 0) {
        uint32_t sec = s_st.next_event_sec % 86400u;
        snprintf(buf, sizeof(buf), "%02u:%02u", (unsigned)(sec / 3600u),
                 (unsigned)((sec % 3600u) / 60u));
        table_row(Y_R2, "BRI", v, bri_col, "NEXT", buf, C_AMBER);
    } else {
        table_row(Y_R2, "BRI", v, bri_col, "NEXT", "--:--", C_DIM2);
    }

    s_rendered_io = *io;
    s_rendered_page = s_page;
    s_panel_dirty = false;
    s_drawn_cpu_idx = s_cpu_idx;
    s_drawn_power_mw = cur_io->power_mw;
    s_drawn_next = s_st.next_event_sec;
}

/* ========================= 对外接口 ========================= */
void dash_flash_activity(void)
{
    s_flash_until = s_tick + DASH_FLASH_SEC;
    if (!s_brackets_on) {
        s_brackets_on = true;
        draw_header();
        fb_flush();
    }
}

void dash_log_push(const char *msg)
{
    if (s_fb == NULL || msg == NULL || msg[0] == '\0') {
        return;
    }
    strncpy(s_log_msg, msg, sizeof(s_log_msg) - 1);
    s_log_msg[sizeof(s_log_msg) - 1] = '\0';
    s_log_has = true;

    draw_log();
    fb_flush();
}

void dash_set_ble(const dash_ble_t *ble)
{
    char name[DASH_NAME_MAX], peer[DASH_NAME_MAX];

    if (ble == NULL) {
        return;
    }
    s_ble = *ble;

    trunc_copy(name, s_ble.device_name != NULL ? s_ble.device_name : "LEDDRIVE", 10);
    trunc_copy(peer, s_ble.connected ? (s_ble.peer_name ? s_ble.peer_name : "PHONE") : "", 10);

    if (s_st_valid &&
        (strcmp(name, s_hdr_name) != 0 || strcmp(peer, s_hdr_peer) != 0 ||
         s_ble.connected != s_hdr_conn)) {
        draw_header();
    }
}

void dash_set_status(const dash_status_t *st)
{
    if (s_fb == NULL || st == NULL) {
        return;
    }

    if (!s_st_valid) {
        fb_fill(C_BG);
        draw_header();
        draw_clock(st);
        draw_log();
        fb_rect(0, Y_SEP, FB_W, 1, C_LINE);
        draw_panel();
        draw_history();
        s_st = *st;
        s_st_valid = true;
        fb_flush();
        return;
    }

    if (s_st.time_valid != st->time_valid || s_st.hour != st->hour ||
        s_st.minute != st->minute || s_st.second != st->second) {
        draw_clock(st);
    }

    s_st = *st;
    fb_flush();
}

void dash_set_ios(const dash_io_t *ios, uint8_t count)
{
    if (ios == NULL || count == 0) {
        s_io_count = 0;
        s_panel_dirty = true;
        return;
    }
    if (count > DASH_IO_MAX) {
        count = DASH_IO_MAX;
    }
    s_io_count = count;
    memcpy(s_ios, ios, count * sizeof(dash_io_t));
    if (s_page >= s_io_count) {
        s_page = 0;
    }
    if (s_cpu_idx >= s_io_count) {
        s_cpu_idx = 0;
    }
    s_panel_dirty = true;
}

uint8_t dash_current_page(void)
{
    return s_page;
}

static bool io_same(const dash_io_t *a, const dash_io_t *b)
{
    if (a->id != b->id || a->time_mode != b->time_mode || a->brightness != b->brightness ||
        a->led_count != b->led_count || a->refresh_ms != b->refresh_ms ||
        a->cpu_pct != b->cpu_pct || a->frame_us != b->frame_us || a->fps != b->fps ||
        a->dma != b->dma) {
        return false;
    }
    if (a->name == NULL || b->name == NULL) {
        return a->name == b->name;
    }
    return strcmp(a->name, b->name) == 0;
}

void dash_tick(void)
{
    if (s_fb == NULL) {
        return;
    }

    /* 括符倒计时 */
    if (s_brackets_on && s_tick >= s_flash_until) {
        s_brackets_on = false;
        draw_header();
    }

    /* 换页 */
    if ((s_tick % IO_SWITCH_SEC) == 0 && s_tick > 0) {
        s_page = (uint8_t)((s_page + 1) % page_count());
        s_panel_dirty = true;
    }

    /* 参数表里 CPU 按 IO 轮询 */
    if ((s_tick % CPU_SWITCH_SEC) == 0 && s_tick > 0) {
        s_cpu_idx = (uint8_t)((s_cpu_idx + 1) % page_count());
        s_cpu_dirty = true;
    }

    if (s_panel_dirty || s_rendered_page != s_page || s_io_count == 0 ||
        !io_same(&s_rendered_io, &s_ios[s_page]) || s_cpu_dirty ||
        s_drawn_cpu_idx != s_cpu_idx || s_drawn_power_mw != s_ios[s_cpu_idx].power_mw ||
        s_drawn_next != s_st.next_event_sec) {
        draw_panel();
        s_cpu_dirty = false;
    }

    /* 底部 TCPU：暂时只显示百分比，不画柱状图 */
    draw_history();

    s_tick++;
    fb_flush();
}

/* ========================= 字模自检 ========================= */
void dash_glyph_test(int page)
{
    const int per_row = 10;
    const int scale = 2;
    const int step = (FONT_W + 1) * scale + 2;
    const int font_count = (int)(sizeof(k_font) / sizeof(k_font[0]));

    if (s_fb == NULL) {
        return;
    }

    fb_fill(C_BG);

    fb_rect(0, 0, FB_W, H_HDR, C_PANEL);
    {
        char t[16];
        snprintf(t, sizeof(t), "GLYPH %d", page);
        fb_text(8, 4, t, 1, C_ACCENT, C_PANEL);
    }
    fb_rect(0, Y_ACCENT, FB_W, 1, C_ACCENT);

    int first = page * 28;
    for (int i = 0; i < 28; i++) {
        int gi = first + i;
        if (gi >= font_count) {
            break;
        }
        int row = i / per_row;
        int col = i % per_row;
        int x = 5 + col * step;
        int y = 22 + row * 17;

        const uint8_t *g = k_font[gi];
        for (int c = 0; c < FONT_W; c++) {
            for (int r = 0; r < FONT_H; r++) {
                if (g[c] & (1u << r)) {
                    fb_rect(x + c * scale, y + r * scale, scale, scale, C_TEXT);
                }
            }
        }
    }

    s_st_valid = false;
    s_rendered_page = 0xFF;
    s_panel_dirty = true;
    s_cpu_dirty = true;

    fb_flush();
    ESP_LOGI(TAG, "glyph page drawn: %d", page);
}

/* ========================= OTA 专用刷新 =========================
 * OTA 期间由 dash_data 以 10Hz 调用，只画进度，不跑普通仪表盘逻辑。
 * 全屏 160x128 = 40KB，SPI3 40MHz 一帧约 8ms，10Hz 只占 8% 带宽。
 */
void dash_ota_begin(void)
{
    s_ota_active = true;
    fb_fill(C_BG);
    fb_flush();
}

void dash_ota_update(uint8_t progress, const char *phase, const char *detail)
{
    if (s_fb == NULL) {
        return;
    }
    if (!s_ota_active) {
        s_ota_active = true;
    }
    if (progress > 100) {
        progress = 100;
    }

    char pct[8];
    char safe_detail[28];
    snprintf(pct, sizeof(pct), "%u%%", (unsigned)progress);
    trunc_copy(safe_detail, detail != NULL ? detail : "", (int)sizeof(safe_detail) - 1);

    fb_fill(C_BG);
    fb_rect(0, 0, FB_W, 2, C_ACCENT);
    fb_text(MARGIN, 8, "OTA UPDATE", 1, C_ACCENT, C_BG);
    fb_text_r(8, "DO NOT OFF", 1, C_DIM, C_BG);

    fb_text_c(26, pct, 3, C_TEXT, C_BG);
    fb_text_c(58, phase != NULL ? phase : "OTA", 1, C_ACCENT2, C_BG);
    fb_text_c(70, safe_detail, 1, C_DIM, C_BG);

    const int bar_x = MARGIN;
    const int bar_y = 90;
    const int bar_w = FB_W - 2 * MARGIN;
    const int bar_h = 14;
    fb_rect(bar_x, bar_y, bar_w, bar_h, C_BAR_BG);
    int fill = (bar_w - 4) * progress / 100;
    if (fill > 0) {
        fb_rect(bar_x + 2, bar_y + 2, fill, bar_h - 4, C_ACCENT2);
    }
    fb_text_c(112, "KEEP POWER / WAIT", 1, C_DIM, C_BG);
    fb_flush();
}

void dash_ota_end(void)
{
    s_ota_active = false;
    s_st_valid = false;
    s_rendered_page = 0xFF;
    s_panel_dirty = true;
    s_cpu_dirty = true;
}
