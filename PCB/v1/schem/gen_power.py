# -*- coding: utf-8 -*-
"""
灯带自定义 v1 — 电源链原理图  (A3)
12V 输入 -> TPS54202 同步降压 5V -> AMS1117-3.3 -> 3V3 给 ESP32-S3

参数依据:
  TPS54202 : TI SLVSD26C 官方 5V/2A 参考设计 (Figure 7-1)
             R2/R3 = 100k/13.3k, R4/R5 = 511k/105k, L = 15uH, BOOT = 0.1uF
  AMS1117  : 永裕泰 AMS1117 Series Rev 2.0
"""
import sys
sys.path.insert(0, r"E:\.Agent\tmp\schem")
from schemdraw import Sheet

s = Sheet(w=420.0, h=297.0, paper="A3", title="12V→5V→3V3 电源链",
          rev="A", sheet="1/1", company="灯带自定义 v1")

RAIL = 248.0          # 12V 主路
GND1 = 216.0          # 一级地符号


def caption(x, y, t):
    s.text(x, y, t, 2.6, ha="left", va="bottom", weight="bold")


def note(x, y, lines, size=1.85, lh=3.2):
    for i, t in enumerate(lines):
        s.text(x, y - i * lh, t, size, ha="left", va="top")


def table(x, y, headers, rows, widths, row_h=3.1, size=1.62):
    """极简表：仅横线，无竖线。y = 表头顶部。"""
    xs = [x]
    for w in widths:
        xs.append(xs[-1] + w)
    tw = xs[-1] - x
    for j, h in enumerate(headers):
        s.text(xs[j] + 0.7, y - row_h / 2, h, size, ha="left", va="center", weight="bold")
    s.line((x, y - row_h), (x + tw, y - row_h), lw=0.6)
    for i, row in enumerate(rows):
        yc = y - row_h * (i + 1) - row_h / 2
        for j, cell in enumerate(row):
            s.text(xs[j] + 0.7, yc, cell, size, ha="left", va="center")
    s.line((x, y - row_h * (len(rows) + 1)), (x + tw, y - row_h * (len(rows) + 1)), lw=0.6)


# ══════════════════════════════ 1. 12V 输入与保护
caption(20, 266, "① 12V 输入与保护")

# pin1 落在 RAIL 上: y0 = y + box_h/2 + (n-1)*pitch/2 = y + 3.81
J1 = s.connector(20, RAIL - 3.81, n=2, ref="J1", val="12V IN XH2.54", orient="right")
s.wire(J1["2"], (34, J1["2"][1]), (34, 230))
s.gnd(34, 230)

s.wire(J1["1"], (35.65, RAIL))
s.fuse(42, RAIL, ref="F1", val="PTC 2A/30V")
s.wire((48.35, RAIL), (54, RAIL))
s.diode(58, RAIL, ref="D1", val="SS34")
s.wire((62, RAIL), (150, RAIL))
s.netlabel(66, RAIL, "VIN_12V", dy=1.5)

d2b, d2t = s.diode(78, 232, ref="D2", val="SMAJ15A", orient="v")
s.wire((78, RAIL), d2t)
s.wire(d2b, (78, GND1))
s.gnd(78, GND1)
s.junction(78, RAIL)

for x, ref, val, pol in [(94, "C1", "100uF/35V", True),
                         (110, "C2", "10uF/35V", False),
                         (126, "C3", "0.1uF", False)]:
    top, bot = s.capacitor(x, 232, ref=ref, val=val, orient="v", polarized=pol)
    s.wire((x, RAIL), top)
    s.wire(bot, (x, GND1))
    s.gnd(x, GND1)
    s.junction(x, RAIL)

# ══════════════════════════════ 2. EN 分压 (EN 绝对最大 7V)
en_x = 140.0
r1t, r1b = s.resistor(en_x, 240, ref="R1", val="470k", orient="v")
s.wire((en_x, RAIL), r1t)
s.junction(en_x, RAIL)
en_node = (en_x, 230.0)
s.wire(r1b, en_node)
r2t, r2b = s.resistor(en_x, 224, ref="R2", val="100k", orient="v")
s.wire(en_node, r2t)
s.wire(r2b, (en_x, GND1))
s.gnd(en_x, GND1)
s.junction(*en_node)

# ══════════════════════════════ 3. TPS54202 降压
caption(158, 274, "② TPS54202 同步降压   12V → 5V / 2A")

U1 = s.ic(158, 222, w=44, h=34, ref="U1", val="TPS54202", pitch=3.81,
          pins_left=[("VIN", "3"), ("EN", "5")],
          pins_right=[("BOOT", "6"), ("SW", "2"), ("FB", "4")],
          pins_bottom=[("GND", "1")])

SW_Y = U1["2"][1]

s.wire((150, RAIL), (150, U1["3"][1]), U1["3"])
s.wire(en_node, (148, en_node[1]), (148, U1["5"][1]), U1["5"])
s.wire(U1["1"], (U1["1"][0], 212.0))
s.gnd(U1["1"][0], 212.0)

# BOOT 自举
s.wire(U1["6"], (212, U1["6"][1]), (212, 258), (214.8, 258))
s.capacitor(218, 258, ref="C4", val="0.1uF", orient="h")
s.wire((221.2, 258), (226, 258), (226, SW_Y))
s.junction(226, SW_Y)

# SW -> L1 -> +5V
s.wire(U1["2"], (240.65, SW_Y))
s.inductor(245, SW_Y, ref="L1", val="15uH")
s.wire((249.35, SW_Y), (330, SW_Y))
s.power(330, SW_Y, "+5V")

# 反馈分压
FBX = 270.0
r3t, r3b = s.resistor(FBX, SW_Y - 10, ref="R3", val="100k", orient="v")
s.wire((FBX, SW_Y), r3t)
s.junction(FBX, SW_Y)
fb_node = (FBX, SW_Y - 15)
s.wire(r3b, fb_node)
r4t, r4b = s.resistor(FBX, SW_Y - 21, ref="R4", val="13.3k", orient="v")
s.wire(fb_node, r4t)
s.wire(r4b, (FBX, SW_Y - 31))
s.gnd(FBX, SW_Y - 31)
s.junction(*fb_node)
s.wire(fb_node, (215, fb_node[1]), (215, U1["4"][1]), U1["4"])

# 输出电容
for x, ref, val in [(290, "C5", "22uF"), (304, "C6", "22uF"), (318, "C7", "0.1uF")]:
    top, bot = s.capacitor(x, SW_Y - 10, ref=ref, val=val, orient="v")
    s.wire((x, SW_Y), top)
    s.wire(bot, (x, GND1))
    s.gnd(x, GND1)
    s.junction(x, SW_Y)

# ══════════════════════════════ 4. AMS1117 3.3V
caption(30, 156, "③ AMS1117-3.3  LDO   5V → 3.3V / 1A")

U2 = s.ic(58, 110, w=34, h=26, ref="U2", val="AMS1117-3.3", pitch=3.81,
          pins_left=[("IN", "3"), ("GND", "1")],
          pins_right=[("OUT", "2"), ("TAB", "4")])

RAIL3 = U2["2"][1]

s.power(30, 146, "+5V")
s.wire((30, 146), (30, RAIL3), U2["3"])
s.junction(30, RAIL3)
c8a, c8b = s.capacitor(30, RAIL3 - 9, ref="C8", val="10uF", orient="v")
s.wire((30, RAIL3), c8a)
s.wire(c8b, (30, 106))
s.gnd(30, 106)

s.wire(U2["1"], (48, U2["1"][1]), (48, 115))
s.gnd(48, 115)

s.wire(U2["2"], (102, RAIL3))
s.wire(U2["4"], (99, U2["4"][1]), (99, RAIL3))
s.junction(99, RAIL3)
s.wire((102, RAIL3), (194.92, RAIL3))
s.netlabel(105, RAIL3, "+3V3", dy=1.5)

for x, ref, val in [(112, "C9", "22uF"), (126, "C10", "0.1uF")]:
    top, bot = s.capacitor(x, RAIL3 - 7, ref=ref, val=val, orient="v")
    s.wire((x, RAIL3), top)
    s.wire(bot, (x, 108))
    s.gnd(x, 108)
    s.junction(x, RAIL3)

# 电源指示灯
s.wire((132, RAIL3), (132, 108), (135, 108))
s.junction(132, RAIL3)
r5a, r5b = s.resistor(141, 108, ref="R5", val="1k", orient="h")
s.wire((135, 108), r5a)
s.wire(r5b, (150, 108))
s.diode(156, 108, ref="D3", val="LED-G", orient="h", led=True)
s.wire((160, 108), (164, 108), (164, 102))
s.gnd(164, 102)

# ══════════════════════════════ 5. 输出接口
caption(200, 156, "④ 输出接口")

J2 = s.connector(200, RAIL3 - 3.81, n=2, ref="J2", val="3V3 OUT XH2.54", orient="left")
s.wire(J2["2"], (190, J2["2"][1]), (190, 115))
s.gnd(190, 115)

J3 = s.connector(200, 97.19, n=2, ref="J3", val="5V OUT XH2.54", orient="left")
s.power(180, J3["1"][1], "+5V")
s.wire((180, J3["1"][1]), J3["1"])
s.wire(J3["2"], (190, J3["2"][1]), (190, 92))
s.gnd(190, 92)

# ══════════════════════════════ 6. 说明
caption(20, 196, "设计说明 / NOTES")
note(20, 192, [
    "1. U1 = TPS54202：4.5~28V 输入、2A 输出、同步整流、内部补偿，无需外置续流二极管，也无需外置补偿网络。",
    "2. EN(5) 脚绝对最大值只有 7V。12V 必须经 R1/R2 分压后再接，直连 VIN 会击穿该脚。",
    "3. 输出电压 = 0.596V × (1 + R3/R4) = 0.596 × (1 + 100k/13.3k) ≈ 5.08V（TI 参考设计原值）。",
    "4. EN 分压 R1=470k / R2=100k：12V 时 EN≈2.10V（阈值 1.28V 以上）；28V 时 EN≈4.91V，仍在 5.5V 推荐值内。",
    "5. L1 需饱和电流 ≥2A 的功率电感；C1 电解耐压 ≥35V（TVS 钳位约 24V，25V 余量不足），C2 陶瓷耐压 ≥35V。",
    "6. 地平面建议：输入电容地、芯片地、输出电容地单点汇合，FB 取样线远离 SW 节点走线。",
])

caption(228, 196, "待确认 / TO CONFIRM")
note(228, 192, [
    "A. ESP32-S3 是外挂模块还是画在本板上？当前按「外挂」处理，用 J2 接线端子供电。",
    "B. 连接器已统一为 XH2.54-2P 卧贴（贴片），J1/J2/J3 同一料号 C5142098；如需拧螺丝端子需换回插件。",
    "C. 5V 支路按 2A 设计。若还要带灯带大电流，需加大 L1 / C5 / C6 并核对 XH 端子 3A 额定。",
    "D. AMS1117 静态电流 5mA、压差 1.2V。在意待机功耗可换 ME6211C33（SOT-23-5，40uA）。",
])

# ══════════════════════════════ 7. BOM（已标准化，LCSC 料号）
caption(20, 88, "物料清单 / BOM  ——  已标准化为立创商城料号（全部 SMT 可贴）")

HDR = ["位号", "规格", "LCSC", "封装"]
W = [10, 27, 20, 20]
left = [
    ["J1", "XH2.54-2P 卧贴", "C5142098", "SMD"],
    ["F1", "PTC 2A / 30V", "C960026", "1812"],
    ["D1", "SS34  40V/3A", "C8678", "SMA"],
    ["D2", "SMAJ15A  单向 TVS", "C86819", "SMA"],
    ["C1", "100uF / 35V", "C2836437", "D6.3x7.7"],
    ["C2", "10uF / 35V X5R", "C6119957", "1206"],
    ["C3", "0.1uF / 50V", "C187860", "0603"],
    ["R1", "470k  1%", "C23178", "0603"],
    ["R2", "100k  1%", "C25803", "0603"],
    ["U1", "TPS54202", "C191884", "SOT-23-6"],
    ["C4", "0.1uF / 50V", "C187860", "0603"],
    ["L1", "15uH  4A", "C408452", "7x6.6"],
    ["R3", "100k  1%", "C25803", "0603"],
]
right = [
    ["R4", "13.3k  1%", "C2933140", "0603"],
    ["C5", "22uF / 16V", "C90146", "1206"],
    ["C6", "22uF / 16V", "C90146", "1206"],
    ["C7", "0.1uF / 50V", "C187860", "0603"],
    ["U2", "AMS1117-3.3", "C6186", "SOT-223"],
    ["C8", "10uF / 16V", "C6119886", "0805"],
    ["C9", "22uF / 16V", "C90146", "1206"],
    ["C10", "0.1uF / 50V", "C187860", "0603"],
    ["R5", "1k  1%", "C21190", "0603"],
    ["D3", "绿色 LED", "C2986048", "0603"],
    ["J2", "XH2.54-2P 卧贴", "C5142098", "SMD"],
    ["J3", "XH2.54-2P 卧贴", "C5142098", "SMD"],
]
table(20, 85, HDR, left, W)
table(112, 85, HDR, right, W)

s.save_svg(r"E:\.Agent\tmp\schem\v1_power.svg")
s.save_pdf(r"E:\.Agent\tmp\schem\v1_power.pdf")
s.save_png(r"E:\.Agent\tmp\schem\v1_power.png", dpi=200)
print("ok")

