# -*- coding: utf-8 -*-
"""
schemdraw.py — 极简原理图绘制引擎（mm 坐标系，y 向上）

用途：不依赖嘉立创EDA，直接产出可看的 SVG / PDF 原理图。
设计成「先画图看，确认后再转网表导入 EDA」的工作流。

用法:
    from schemdraw import Sheet, mm
    s = Sheet(title="灯带控制板", rev="A")
    p = s.ic(60, 120, w=20, h=30, ref="U1", val="ESP32-C3",
             left=[("VCC","1"),("GND","2")], right=[("GPIO","3")])
    s.wire((p["1"][0]-8, p["1"][1]), p["1"])
    s.gnd(p["2"][0]-8, p["2"][1])
    s.save_svg("out.svg")
"""
import math
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle, Circle, Polygon
from matplotlib.lines import Line2D
from matplotlib import font_manager

# ---------------------------------------------------------------- 全局样式
MM = 1.0                      # 坐标单位就是 mm
WIRE_LW = 1.0                 # 导线线宽 (pt)
SYM_LW = 1.35                 # 器件轮廓线宽
PIN_LW = 1.1
INK = "#101010"
FAINT = "#8a8a8a"

FS_REF = 2.6                  # 位号字号 (mm)
FS_VAL = 2.3
FS_PIN = 1.8
FS_NET = 2.1
FS_TITLE = 3.4

GRID = 2.54                   # 0.1 inch，所有坐标应吸附到这个栅格


def snap(v, g=GRID):
    return round(v / g) * g


def _find_cjk_font():
    """挑一个能用中文的字体。"""
    want = ["Microsoft YaHei", "SimHei", "Noto Sans CJK SC", "Source Han Sans SC",
            "WenQuanYi Micro Hei", "SimSun", "DengXian"]
    avail = {f.name for f in font_manager.fontManager.ttflist}
    for n in want:
        if n in avail:
            return n
    return "DejaVu Sans"


class Sheet:
    """一张原理图纸。坐标单位 mm，原点左下。"""

    def __init__(self, w=297.0, h=210.0, title="", rev="A", sheet="1/1",
                 company="", paper="A4"):
        self.w, self.h = float(w), float(h)
        self.title, self.rev, self.sheet, self.company = title, rev, sheet, company
        self.paper = paper
        self._lines = []      # (pts, lw, color)
        self._polys = []      # (pts, lw, fill)
        self._circles = []    # (x,y,r,fill)
        self._texts = []      # (x,y,s,size,ha,va,rot,color,weight)
        self._junctions = []  # (x,y)

    # ---------------------------------------------------------- 基本图元
    def line(self, *pts, lw=WIRE_LW, color=INK):
        self._lines.append(([tuple(p) for p in pts], lw, color))

    def wire(self, *pts, lw=WIRE_LW):
        """折线导线。"""
        self._lines.append(([tuple(p) for p in pts], lw, INK))

    def rect(self, x, y, w, h, lw=SYM_LW, fill="none"):
        self._polys.append(([(x, y), (x + w, y), (x + w, y + h), (x, y + h)], lw, fill))

    def poly(self, pts, lw=SYM_LW, fill="none", closed=True):
        p = [tuple(q) for q in pts]
        if closed:
            p = p + [p[0]]
        self._polys.append((p, lw, fill))

    def circle(self, x, y, r, fill="white", lw=PIN_LW, edge=True):
        self._circles.append((x, y, r, fill, lw, edge))

    def dot(self, x, y, r=0.55):
        self._circles.append((x, y, r, INK, 0, False))

    def text(self, x, y, s, size=FS_REF, ha="center", va="center",
             rot=0, color=INK, weight="normal"):
        self._texts.append((x, y, s, size, ha, va, rot, color, weight))

    def junction(self, x, y):
        self._junctions.append((x, y))

    # ---------------------------------------------------------- 无源器件
    def resistor(self, x, y, ref="R", val="", orient="h", length=6.35, ref_dy=2.6):
        """IEC 矩形电阻。x,y = 中心。返回两端坐标。"""
        w = 2.0
        if orient == "h":
            x0, x1 = x - length / 2, x + length / 2
            self.rect(x0, y - w / 2, length, w)
            self.text(x, y + ref_dy + 1.1, ref, FS_REF)
            self.text(x, y + ref_dy - 1.2, val, FS_VAL)
            return (x0, y), (x1, y)
        else:
            y0, y1 = y - length / 2, y + length / 2
            self.rect(x - w / 2, y0, w, length)
            self.text(x + 4.2, y, ref, FS_REF, ha="left")
            self.text(x + 4.2, y - 2.1, val, FS_VAL, ha="left")
            return (x, y0), (x, y1)

    def capacitor(self, x, y, ref="C", val="", orient="h", gap=1.0, plate=3.0,
                  polarized=False):
        if orient == "h":
            self.line((x - gap / 2, y - plate / 2), (x - gap / 2, y + plate / 2), lw=SYM_LW)
            self.line((x + gap / 2, y - plate / 2), (x + gap / 2, y + plate / 2), lw=SYM_LW)
            self.line((x - 3.2, y), (x - gap / 2, y))
            self.line((x + gap / 2, y), (x + 3.2, y))
            self.text(x, y + 3.6, ref, FS_REF)
            self.text(x, y - 3.6, val, FS_VAL)
            if polarized:
                self.text(x - 1.9, y + 2.0, "+", 2.2)
            return (x - 3.2, y), (x + 3.2, y)
        else:
            self.line((x - plate / 2, y - gap / 2), (x + plate / 2, y - gap / 2), lw=SYM_LW)
            self.line((x - plate / 2, y + gap / 2), (x + plate / 2, y + gap / 2), lw=SYM_LW)
            self.line((x, y - 3.2), (x, y - gap / 2))
            self.line((x, y + gap / 2), (x, y + 3.2))
            self.text(x + 2.6, y + 1.2, ref, FS_REF, ha="left")
            self.text(x + 2.6, y - 1.4, val, FS_VAL, ha="left")
            if polarized:
                self.text(x - 2.0, y + 1.6, "+", 2.2)
            return (x, y - 3.2), (x, y + 3.2)

    def diode(self, x, y, ref="D", val="", orient="h", lw=SYM_LW, led=False):
        """二极管/LED 三角符号，阳极在左（水平时）。返回 (anode, cathode)。"""
        a, b, h = 1.4, 1.4, 2.0
        if orient == "h":
            self.poly([(x - a, y - h), (x - a, y + h), (x + a, y)], lw=lw, fill="none")
            self.line((x + a, y - h), (x + a, y + h), lw=lw)
            self.line((x - a - 2.6, y), (x - a, y))
            self.line((x + a, y), (x + a + 2.6, y))
            self.text(x, y + 3.8, ref, FS_REF)
            self.text(x, y - 3.8, val, FS_VAL)
            if led:
                for dx in (-0.7, 0.5):
                    self.line((x + dx, y + 2.2), (x + dx + 1.5, y + 3.7), lw=0.8)
                    self.poly([(x + dx + 1.5, y + 3.7), (x + dx + 0.9, y + 3.5),
                               (x + dx + 1.35, y + 3.05)], lw=0.6, fill=INK)
            return (x - a - 2.6, y), (x + a + 2.6, y)
        else:
            self.poly([(x - h, y - a), (x + h, y - a), (x, y + a)], lw=lw, fill="none")
            self.line((x - h, y + a), (x + h, y + a), lw=lw)
            self.line((x, y - a - 2.6), (x, y - a))
            self.line((x, y + a), (x, y + a + 2.6))
            self.text(x + 3.4, y, ref, FS_REF, ha="left")
            self.text(x + 3.4, y - 2.1, val, FS_VAL, ha="left")
            return (x, y - a - 2.6), (x, y + a + 2.6)

    def inductor(self, x, y, ref="L", val="", orient="h", length=6.35):
        """电感：一串半圆。"""
        n = 4
        r = length / (2 * n)
        if orient == "h":
            xs = x - length / 2
            for i in range(n):
                th = [math.pi * k / 24 for k in range(25)]
                pts = [(xs + r + r * math.cos(t), y + r * math.sin(t)) for t in th]
                self.poly(pts, lw=SYM_LW, fill="none")
                xs += 2 * r
            self.line((x - length / 2 - 2.6, y), (x - length / 2, y))
            self.line((x + length / 2, y), (x + length / 2 + 2.6, y))
            self.text(x, y + 3.6, ref, FS_REF)
            self.text(x, y - 3.6, val, FS_VAL)
            return (x - length / 2 - 2.6, y), (x + length / 2 + 2.6, y)
        else:
            ys = y - length / 2
            for i in range(n):
                th = [math.pi * k / 24 for k in range(25)]
                pts = [(x + r * math.sin(t), ys + r + r * math.cos(t)) for t in th]
                self.poly(pts, lw=SYM_LW, fill="none")
                ys += 2 * r
            self.line((x, y - length / 2 - 2.6), (x, y - length / 2))
            self.line((x, y + length / 2), (x, y + length / 2 + 2.6))
            self.text(x + 4.0, y, ref, FS_REF, ha="left")
            self.text(x + 4.0, y - 2.1, val, FS_VAL, ha="left")
            return (x, y - length / 2 - 2.6), (x, y + length / 2 + 2.6)

    def fuse(self, x, y, ref="F", val="", orient="h", length=6.35):
        """保险丝：矩形 + 穿心线。"""
        if orient == "h":
            self.rect(x - length / 2, y - 1.0, length, 2.0)
            self.line((x - length / 2 - 2.6, y), (x + length / 2 + 2.6, y), lw=PIN_LW)
            self.text(x, y + 3.4, ref, FS_REF)
            self.text(x, y - 3.4, val, FS_VAL)
            return (x - length / 2 - 2.6, y), (x + length / 2 + 2.6, y)
        else:
            self.rect(x - 1.0, y - length / 2, 2.0, length)
            self.line((x, y - length / 2 - 2.6), (x, y + length / 2 + 2.6), lw=PIN_LW)
            self.text(x + 3.6, y, ref, FS_REF, ha="left")
            self.text(x + 3.6, y - 2.1, val, FS_VAL, ha="left")
            return (x, y - length / 2 - 2.6), (x, y + length / 2 + 2.6)

    # ---------------------------------------------------------- 电源/地
    def gnd(self, x, y, kind="gnd"):
        """接地符号：pin 点在 (x,y)，向下画。"""
        self.line((x, y), (x, y - 2.4))
        if kind == "gnd":
            for i, wdt in enumerate((3.4, 2.2, 1.0)):
                yy = y - 2.4 - i * 1.1
                self.line((x - wdt / 2, yy), (x + wdt / 2, yy), lw=SYM_LW)
            self.text(x, y - 6.6, "GND", FS_NET, va="top")
        elif kind == "agnd":
            pts = [(x - 1.7, y - 2.4), (x + 1.7, y - 2.4), (x, y - 4.6)]
            self.poly(pts, lw=SYM_LW, fill="none")
            self.text(x, y - 5.6, "AGND", FS_NET, va="top")
        elif kind == "earth":
            self.line((x - 1.8, y - 3.4), (x + 1.8, y - 3.4), lw=SYM_LW)
            self.line((x - 1.1, y - 3.4), (x - 1.6, y - 4.6), lw=SYM_LW)
            self.line((x + 1.1, y - 3.4), (x + 1.6, y - 4.6), lw=SYM_LW)
            self.line((x - 1.6, y - 4.6), (x + 1.6, y - 4.6), lw=SYM_LW)

    def power(self, x, y, name="+5V", up=True):
        """电源符号：pin 点 (x,y)，向上箭头。"""
        d = 1 if up else -1
        self.line((x, y), (x, y + d * 2.4))
        self.poly([(x, y + d * 2.4), (x - 1.1, y + d * 1.2), (x + 1.1, y + d * 1.2)],
                  lw=SYM_LW, fill="none")
        self.text(x, y + d * 4.4, name, FS_NET, va="bottom" if up else "top")

    def netlabel(self, x, y, name, dx=0.0, dy=1.6, ha="left"):
        """网络标签。"""
        self.text(x + dx, y + dy, name, FS_NET, ha=ha, va="bottom")

    # ---------------------------------------------------------- 集成电路
    def ic(self, x, y, w, h, ref="U", val="", pins_left=(), pins_right=(),
           pins_top=(), pins_bottom=(), pitch=2.54, stub=3.81,
           ref_pos="top", hide_pin_no=False, name_size=2.0, no_size=1.7):
        """
        矩形 IC / 模块。x,y = 左下单角点。
        pins_left/right: [(name, number), ...] 自上而下
        pins_top/bottom: [(name, number), ...] 自左而右
        返回 {number: (x, y)} 绝对引脚端点坐标。
        """
        self.rect(x, y, w, h, lw=SYM_LW)
        out = {}

        def side(pins, s):
            n = len(pins)
            if n == 0:
                return
            span = (n - 1) * pitch
            if s in ("left", "right"):
                y0 = y + h / 2 + span / 2
                for i, (nm, num) in enumerate(pins):
                    yy = y0 - i * pitch
                    if s == "left":
                        px0, px1 = x - stub, x
                        self.line((px0, yy), (px1, yy), lw=PIN_LW)
                        self.text(x + 1.0, yy, nm, name_size, ha="left")
                        if not hide_pin_no:
                            self.text(x - 0.9, yy + 0.9, num, no_size, ha="right", va="bottom", color="#555555")
                        out[num] = (px0, yy)
                    else:
                        px0, px1 = x + w, x + w + stub
                        self.line((px0, yy), (px1, yy), lw=PIN_LW)
                        self.text(x + w - 1.0, yy, nm, name_size, ha="right")
                        if not hide_pin_no:
                            self.text(x + w + 0.9, yy + 0.9, num, no_size, ha="left", va="bottom", color="#555555")
                        out[num] = (px1, yy)
            else:
                x0 = x + w / 2 - span / 2
                for i, (nm, num) in enumerate(pins):
                    xx = x0 + i * pitch
                    if s == "top":
                        self.line((xx, y + h), (xx, y + h + stub), lw=PIN_LW)
                        self.text(xx, y + h - 1.4, nm, name_size, ha="center", va="top")
                        if not hide_pin_no:
                            self.text(xx - 0.9, y + h + 0.9, num, no_size, ha="right", va="bottom", color="#555555")
                        out[num] = (xx, y + h + stub)
                    else:
                        self.line((xx, y), (xx, y - stub), lw=PIN_LW)
                        self.text(xx, y + 1.4, nm, name_size, ha="center", va="bottom")
                        if not hide_pin_no:
                            self.text(xx - 0.9, y - 0.9, num, no_size, ha="right", va="top", color="#555555")
                        out[num] = (xx, y - stub)

        side(pins_left, "left")
        side(pins_right, "right")
        side(pins_top, "top")
        side(pins_bottom, "bottom")

        # 位号/型号
        if ref_pos == "top":
            self.text(x, y + h + stub + 3.2, ref, FS_REF, weight="bold")
            self.text(x, y + h + stub + 0.9, val, FS_VAL)
        else:
            self.text(x + 0.8, y + 0.8, ref, FS_REF, ha="left", va="bottom", weight="bold")
            self.text(x + 0.8, y + h - 0.8, val, FS_VAL, ha="left", va="top")
        return out

    def connector(self, x, y, n=3, ref="J", val="", orient="right",
                  labels=(), pitch=2.54, pin_len=5.08, box_w=5.0, box_h=None):
        """
        接插件：一列方框（每针一个小方框）+ 引脚。返回 {引脚名: (x,y)}。
        orient: 引脚朝向 'right' / 'left'
        """
        if box_h is None:
            box_h = (n - 1) * pitch + 2.54
        self.rect(x, y, box_w, box_h, lw=SYM_LW)
        out = {}
        y0 = y + box_h / 2 + (n - 1) * pitch / 2
        for i in range(n):
            yy = y0 - i * pitch
            if orient == "right":
                sx, ex = x + box_w, x + box_w + pin_len
            else:
                sx, ex = x - pin_len, x
            self.line((sx, yy), (ex, yy), lw=PIN_LW)
            self.circle(sx + (0.9 if orient == "right" else -0.9), yy, 0.45, fill=INK, lw=0)
            out[str(i + 1)] = (ex if orient == "right" else sx, yy)
            self.text(x + box_w / 2, yy, str(i + 1), 1.6)
        self.text(x + box_w / 2, y + box_h + 2.0, ref, FS_REF, weight="bold")
        self.text(x + box_w / 2, y + box_h + 4.2, val, FS_VAL)
        for i, lab in enumerate(labels):
            if i < n:
                yy = y0 - i * pitch
                self.text(x + box_w + (pin_len + 1.2), yy + 0.8, lab, FS_PIN, ha="left", va="bottom",
                          color="#444444")
        return out

    # ---------------------------------------------------------- 图框与标题栏
    def _frame(self):
        m = 10.0
        self.rect(m, m, self.w - 2 * m, self.h - 2 * m, lw=1.0)
        # 标题栏
        tw, th = 110.0, 22.0
        bx, by = self.w - m - tw, m
        self.rect(bx, by, tw, th, lw=1.0)
        self.line((bx, by + th - 7.0), (bx + tw, by + th - 7.0), lw=0.7)
        self.line((bx, by + th - 14.0), (bx + tw, by + th - 14.0), lw=0.7)
        self.line((bx + tw * 0.62, by), (bx + tw * 0.62, by + th), lw=0.7)
        self.text(bx + 2, by + th - 3.6, self.title, 3.0, ha="left", weight="bold")
        self.text(bx + 2, by + th - 11.0, "Drawn by: DSH agent", 2.1, ha="left", color="#333333")
        self.text(bx + 2, by + 4.0, "Sheet " + self.sheet, 2.1, ha="left")
        self.text(bx + tw * 0.62 + 2, by + 4.0, "Rev " + self.rev, 2.1, ha="left")
        self.text(bx + tw * 0.62 + 2, by + th - 11.0, self.paper, 2.1, ha="left", color="#333333")
        self.text(bx + tw * 0.62 + 2, by + th - 3.6, self.company, 2.1, ha="left", color="#333333")

    # ---------------------------------------------------------- 输出
    def _draw(self, ax):
        self._frame()
        for pts, lw, color in self._lines:
            xs = [p[0] for p in pts]
            ys = [p[1] for p in pts]
            ax.plot(xs, ys, color=color, lw=lw, solid_capstyle="round",
                    solid_joinstyle="round", zorder=3)
        for pts, lw, fill in self._polys:
            xs = [p[0] for p in pts]
            ys = [p[1] for p in pts]
            ax.plot(xs, ys, color=INK, lw=lw, solid_capstyle="round", zorder=4,
                    solid_joinstyle="round")
            if fill != "none":
                ax.fill(xs, ys, color=fill, zorder=3)
        for x, y, r, fill, lw, edge in self._circles:
            ax.add_patch(Circle((x, y), r, facecolor=fill, edgecolor=INK if edge else fill,
                                lw=lw if edge else 0, zorder=5))
        for (x, y, s, size, ha, va, rot, color, weight) in self._texts:
            ax.text(x, y, s, fontsize=size * 72 / 25.4, ha=ha, va=va, rotation=rot,
                    rotation_mode="anchor", color=color, fontweight=weight,
                    fontproperties=_FP, zorder=6)

    def render(self, path, dpi=300):
        fig_w, fig_h = self.w / 25.4, self.h / 25.4
        fig = plt.figure(figsize=(fig_w, fig_h))
        ax = fig.add_axes([0, 0, 1, 1])
        ax.set_xlim(0, self.w)
        ax.set_ylim(0, self.h)
        ax.set_aspect("equal")
        ax.axis("off")
        self._draw(ax)
        fig.savefig(path, dpi=dpi, facecolor="white")
        plt.close(fig)
        return path

    def save_svg(self, path):
        return self.render(path, dpi=300)

    def save_pdf(self, path):
        return self.render(path, dpi=300)

    def save_png(self, path, dpi=200):
        return self.render(path, dpi=dpi)


_FP = font_manager.FontProperties(family=[_find_cjk_font()])
