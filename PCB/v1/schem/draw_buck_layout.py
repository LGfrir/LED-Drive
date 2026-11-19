# -*- coding: utf-8 -*-
"""12V→5V buck 段 俯视布局提案 v3"""
import sys
sys.path.insert(0, r"E:\.Agent\tmp\schem")
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle, FancyArrowPatch, Circle, Polygon
from matplotlib import font_manager

names = {f.name for f in font_manager.fontManager.ttflist}
FAM = next((n for n in ("Microsoft YaHei", "SimHei", "Noto Sans CJK SC") if n in names), "DejaVu Sans")
FP = font_manager.FontProperties(family=FAM)
def cn(s, w="normal"):
    return dict(fontproperties=FP, fontsize=s, fontweight=w)

C = {1: "#c62828", 2: "#ef6c00", 3: "#f9a825", 4: "#fbc02d", 5: "#1565c0",
     6: "#2e7d32", 7: "#1976d2", 8: "#558b2f", 9: "#6d4c41", 10: "#546e7a"}

P = [
    ("U1",  0.0,   0.0,  2.9, 1.6, 0,  "TPS54202"),
    ("C2", -3.9,   0.0,  1.6, 3.2, 1,  "10uF 输入"),
    ("C3", -3.9,   2.9,  1.6, 0.8, 4,  "0.1uF"),
    ("C4",  3.7,   0.0,  1.6, 0.8, 2,  "0.1uF BOOT"),
    ("L1", -1.6,  -6.9,  7.3, 6.8, 3,  "15uH 4A"),
    ("C5",  1.0, -13.2,  3.2, 1.6, 5,  "22uF"),
    ("C6",  5.6, -13.2,  3.2, 1.6, 5,  "22uF"),
    ("C7",  9.8, -13.2,  1.6, 0.8, 7,  "0.1uF"),
    ("R3", 11.6,  -8.6,  0.8, 1.6, 6,  "100k"),
    ("R4", 11.6, -11.8,  0.8, 1.6, 6,  "13.3k"),
    ("R1",  6.6,   3.0,  0.8, 1.6, 8,  "470k"),
    ("R2",  6.6,  -0.4,  0.8, 1.6, 8,  "100k"),
]

fig = plt.figure(figsize=(18.5, 8.4))
ax = fig.add_axes([0.015, 0.02, 0.63, 0.90])
lg = fig.add_axes([0.66, 0.02, 0.33, 0.90])
lg.set_xlim(0, 1); lg.set_ylim(0, 1); lg.axis("off")
ax.set_xlim(-9.8, 16.4); ax.set_ylim(-16.2, 6.8)
ax.set_aspect("equal"); ax.axis("off")

# 热环路
ax.add_patch(Polygon([(-4.78, 1.95), (-1.45, 1.95), (-1.45, -1.95), (-4.78, -1.95)],
                     closed=True, facecolor="#ffcdd2", alpha=0.55, edgecolor="#c62828",
                     linestyle="--", linewidth=1.1, zorder=0))
ax.text(-3.1, 2.18, "热环路", color="#c62828", ha="center", **cn(9, "bold"))

# 元件
for ref, cx, cy, w, h, pri, sub in P:
    col = C.get(pri, "#90a4ae")
    ax.add_patch(Rectangle((cx-w/2, cy-h/2), w, h, facecolor=col, alpha=0.28,
                           edgecolor=col, linewidth=2.2 if 1 <= pri <= 5 else 1.2, zorder=2))
    ax.text(cx, cy + (0.32 if h > 1.4 else 0.1), ref, ha="center", va="center",
            zorder=5, **cn(14 if ref == "U1" else 11, "bold"))
    if h > 1.4:
        ax.text(cx, cy - 0.72, sub, ha="center", va="center", color="#37474f", zorder=5, **cn(7.2))
    if pri:
        ax.add_patch(Circle((cx - w/2 - 0.5, cy + h/2 + 0.5), 0.55, facecolor=col,
                            edgecolor="white", linewidth=1.2, zorder=6))
        ax.text(cx - w/2 - 0.5, cy + h/2 + 0.48, str(pri), ha="center", va="center",
                color="white", zorder=7, **cn(9, "bold"))

def arrow(x1, y1, x2, y2, txt, col="#c62828", dx=0.0, dy=0.3):
    ax.add_patch(FancyArrowPatch((x1, y1), (x2, y2), arrowstyle="<->", mutation_scale=10,
                                 color=col, lw=1.3, zorder=8, shrinkA=0, shrinkB=0))
    ax.text((x1+x2)/2 + dx, (y1+y2)/2 + dy, txt, ha="center", va="bottom",
            color=col, zorder=9, **cn(8.5, "bold"))

arrow(-3.05, 0.45, -2.12, 0.45, "2mm")
arrow(2.12, 0.32, 2.85, 0.32, "3mm", dy=-0.78)
arrow(-1.6, -3.5, -1.6, -1.08, "3mm", dx=0.62)
arrow(5.6, -12.35, 5.6, -10.5, "3mm", dx=0.78)

# 功率路径
ax.annotate("", xy=(-4.85, 0), xytext=(-8.6, 0),
            arrowprops=dict(arrowstyle="-|>", color="#8d6e63", lw=3))
ax.text(-6.7, -1.15, "12V 来自 D1 / D2 / C1", color="#6d4c41", ha="center", **cn(8.5))
ax.plot([-1.6, -1.6], [-3.5, -1.1], color="#b71c1c", lw=3, zorder=1)

ax.plot([-2.6, 15.9], [-10.4, -10.4], color="#1565c0", lw=3, zorder=1)
ax.annotate("", xy=(15.9, -10.4), xytext=(14.6, -10.4),
            arrowprops=dict(arrowstyle="-|>", color="#1565c0", lw=3))
ax.text(15.6, -9.6, "5V 输出", color="#1565c0", ha="right", **cn(9, "bold"))
for x in (1.0, 5.6, 9.8):
    ax.plot([x, x], [-10.4, -12.4], color="#1565c0", lw=1.4, zorder=1)

# FB 分压
ax.plot([11.6, 11.6], [-10.4, -9.4], color="#1565c0", lw=1.6, zorder=1)
ax.plot([11.6, 11.6], [-10.15, -11.0], color="#2e7d32", lw=1.6, zorder=1)
ax.plot([11.6, 10.3], [-9.9, -9.9], color="#2e7d32", lw=1.2, zorder=1)
ax.text(10.2, -9.9, "FB 取样点\n(C5/C6 正极)", color="#2e7d32", va="center",
        ha="right", **cn(7.2))

ax.text(-9.4, 5.9, "实际负载约 0.25A：热与电感饱和均有余量\n布局唯一目标 = 环路小、噪声不串",
        color="#37474f", va="top", **cn(8.5))

# ══════ 右侧
lg.text(0.0, 0.965, "靠近优先级", color="#1a1a1a", **cn(13, "bold"))
LEG = [
    (1, "C2  输入 10uF", "热环路，焊盘贴 pin3 / pin1"),
    (2, "C4  BOOT 0.1uF", "pin6 与 pin2 对角，绕但必须短"),
    (3, "L1  15uH", "SW 走线短、铜皮窄"),
    (4, "C3  0.1uF", "与 C2 同组，输入高频旁路"),
    (5, "C5 / C6  22uF", "贴 L1 输出端"),
    (7, "C7  0.1uF", "与 C5/C6 同组"),
    (6, "R3 / R4  分压", "取样点在 C5/C6 正极，远离 SW"),
    (8, "R1 / R2  EN", "高阻节点，走线别拉长"),
    (9, "C1  100uF", "靠 12V 分叉点，不用贴 IC"),
    (10, "D1 / D2 / F1", "靠 XT60 输入口"),
]
y = 0.895
for n, a, b in LEG:
    lg.add_patch(Circle((0.022, y), 0.016, facecolor=C.get(n, "#90a4ae"),
                        edgecolor="white", lw=1.1, transform=lg.transAxes))
    lg.text(0.022, y - 0.002, str(n), ha="center", va="center", color="white",
            transform=lg.transAxes, **cn(7.5, "bold"))
    lg.text(0.055, y + 0.011, a, va="center", transform=lg.transAxes, **cn(9.5, "bold"))
    lg.text(0.055, y - 0.019, b, va="center", color="#455a64", transform=lg.transAxes, **cn(8.2))
    y -= 0.071

lg.text(0.0, 0.175, "U1 引脚（SOT-23-6 俯视）", color="#1a1a1a",
        transform=lg.transAxes, **cn(11, "bold"))
bx, by, bw, bh = 0.10, 0.035, 0.30, 0.10
lg.add_patch(Rectangle((bx, by), bw, bh, transform=lg.transAxes,
                       facecolor="#eceff1", edgecolor="#455a64", lw=1.4))
for i, t in enumerate(["1 GND", "2 SW", "3 VIN"]):
    yy = by + bh - (i + 0.5) * bh / 3
    lg.text(bx - 0.01, yy, t, ha="right", va="center", transform=lg.transAxes, **cn(8.2))
    lg.plot([bx - 0.008, bx], [yy, yy], color="#455a64", lw=0.8, transform=lg.transAxes)
for i, t in enumerate(["6 BOOT", "5 EN", "4 FB"]):
    yy = by + bh - (i + 0.5) * bh / 3
    lg.text(bx + bw + 0.01, yy, t, ha="left", va="center", transform=lg.transAxes, **cn(8.2))
    lg.plot([bx + bw, bx + bw + 0.008], [yy, yy], color="#455a64", lw=0.8, transform=lg.transAxes)
lg.text(bx + bw / 2, by + bh / 2, "U1", ha="center", va="center",
        transform=lg.transAxes, **cn(9, "bold"))

fig.suptitle("12V → 5V（TPS54202）布局提案  ·  主体按封装真实尺寸  ·  圆圈数字 = 必须靠近的优先级",
             **cn(15, "bold"), y=0.975)
plt.savefig(r"E:\.Agent\tmp\schem\buck_layout.png", dpi=170, facecolor="white")
plt.savefig(r"E:\.Agent\tmp\schem\buck_layout.svg", facecolor="white")
print("saved")
