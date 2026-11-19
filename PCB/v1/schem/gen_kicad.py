# -*- coding: utf-8 -*-
"""
生成 EasyEDA 专业版可导入的 KiCad 7 原理图 (.kicad_sch)

设计决策:
  - 连线用「短线 + 网络标签」而非长走线 —— 连接关系 100% 确定, 不会因为坐标误差断网
  - 只定义 7 个自绘符号, 不依赖任何外部符号库
  - 引脚编号与 电源链网表_protel2.txt 完全一致

KiCad 符号空间是 y 向上, 原理图空间 y 向下。放置时:
    原理图坐标 = (X + px, Y - py)
"""
import math
import uuid as _uuid

PROJECT = "灯带自定义v1"


def uid(seed):
    """确定性 uuid, 便于重复生成时 diff。"""
    return str(_uuid.UUID(bytes=__import__('hashlib').md5(seed.encode()).digest()))


# ═══════════════════════════════ 符号库
# 每个符号: pins = [(number, name, px, py, angle, length)]
SYMBOLS = {}

SYMBOLS['R'] = dict(
    body_rows=8,
    graphics=['(rectangle (start -1.016 -2.54) (end 1.016 2.54) (stroke (width 0.254) (type default)) (fill (type none)))'],
    pins=[('1', '~', 0, 3.81, 270, 1.27), ('2', '~', 0, -3.81, 90, 1.27)],
)
SYMBOLS['L'] = dict(
    body_rows=8,
    graphics=['(rectangle (start -1.27 -2.54) (end 1.27 2.54) (stroke (width 0.254) (type default)) (fill (type none)))'],
    pins=[('1', '~', 0, 3.81, 270, 1.27), ('2', '~', 0, -3.81, 90, 1.27)],
)
SYMBOLS['C'] = dict(
    body_rows=8,
    graphics=[
        '(polyline (pts (xy -1.524 0.508) (xy 1.524 0.508)) (stroke (width 0.3048) (type default)) (fill (type none)))',
        '(polyline (pts (xy -1.524 -0.508) (xy 1.524 -0.508)) (stroke (width 0.3048) (type default)) (fill (type none)))',
    ],
    pins=[('1', '~', 0, 3.81, 270, 3.302), ('2', '~', 0, -3.81, 90, 3.302)],
)
SYMBOLS['D'] = dict(
    body_rows=8,
    graphics=[
        '(polyline (pts (xy -1.27 1.27) (xy 1.27 1.27)) (stroke (width 0.254) (type default)) (fill (type none)))',
        '(polyline (pts (xy -1.27 -1.27) (xy 1.27 -1.27) (xy 0 1.27) (xy -1.27 -1.27)) '
        '(stroke (width 0.254) (type default)) (fill (type none)))',
    ],
    pins=[('1', 'A', 0, 3.81, 270, 2.54), ('2', 'K', 0, -3.81, 90, 2.54)],
)
SYMBOLS['CONN2'] = dict(
    body_rows=10,
    graphics=['(rectangle (start -1.27 -2.54) (end 1.27 2.54) (stroke (width 0.254) (type default)) (fill (type none)))'],
    pins=[('1', '1', 0, 3.81, 270, 1.27), ('2', '2', 0, -3.81, 90, 1.27)],
)
SYMBOLS['TPS54202'] = dict(
    body_rows=22,
    graphics=['(rectangle (start -10.16 -11.43) (end 10.16 11.43) (stroke (width 0.254) (type default)) (fill (type none)))'],
    pins=[
        ('3', 'VIN', -12.7, 7.62, 0, 2.54),
        ('5', 'EN', -12.7, 0.0, 0, 2.54),
        ('6', 'BOOT', 12.7, 7.62, 180, 2.54),
        ('2', 'SW', 12.7, 0.0, 180, 2.54),
        ('4', 'FB', 12.7, -7.62, 180, 2.54),
        ('1', 'GND', 0.0, -13.97, 90, 2.54),
    ],
)
SYMBOLS['AMS1117'] = dict(
    body_rows=16,
    graphics=['(rectangle (start -7.62 -7.62) (end 7.62 7.62) (stroke (width 0.254) (type default)) (fill (type none)))'],
    pins=[
        ('3', 'IN', -10.16, 3.81, 0, 2.54),
        ('1', 'GND', -10.16, -3.81, 0, 2.54),
        ('2', 'OUT', 10.16, 3.81, 180, 2.54),
        ('4', 'TAB', 10.16, -3.81, 180, 2.54),
    ],
)

# ═══════════════════════════════ 元件表
# (ref, sym, value)
COMPONENTS = [
    ('J1', 'CONN2', 'KF301-2P 12V IN'),
    ('F1', 'R', 'PTC 2A'),
    ('D1', 'D', 'SS34'),
    ('D2', 'D', 'SMAJ15A'),
    ('C1', 'C', '100uF/35V'),
    ('C2', 'C', '10uF/35V'),
    ('C3', 'C', '0.1uF'),
    ('R1', 'R', '511k'),
    ('R2', 'R', '105k'),
    ('U1', 'TPS54202', 'TPS54202'),
    ('C4', 'C', '0.1uF'),
    ('L1', 'L', '15uH'),
    ('R3', 'R', '100k'),
    ('R4', 'R', '13.3k'),
    ('C5', 'C', '22uF'),
    ('C6', 'C', '22uF'),
    ('C7', 'C', '0.1uF'),
    ('U2', 'AMS1117', 'AMS1117-3.3'),
    ('C8', 'C', '10uF'),
    ('C9', 'C', '22uF'),
    ('C10', 'C', '0.1uF'),
    ('R5', 'R', '1k'),
    ('D3', 'D', 'LED-G'),
    ('J2', 'CONN2', 'KF301-2P 3V3'),
    ('J3', 'CONN2', 'KF301-2P 5V'),
]

# ═══════════════════════════════ 网表 (与 protel2 文件一致)
NETS = {
    '+12V_IN': [('J1', '1'), ('F1', '1')],
    'VIN_F':   [('F1', '2'), ('D1', '1')],
    'VIN_12V': [('D1', '2'), ('D2', '1'), ('C1', '1'), ('C2', '1'), ('C3', '1'), ('R1', '1'), ('U1', '3')],
    'GND':     [('J1', '2'), ('D2', '2'), ('C1', '2'), ('C2', '2'), ('C3', '2'), ('R2', '2'),
                ('U1', '1'), ('R4', '2'), ('C5', '2'), ('C6', '2'), ('C7', '2'), ('U2', '1'),
                ('C8', '2'), ('C9', '2'), ('C10', '2'), ('D3', '2'), ('J2', '2'), ('J3', '2')],
    'EN_DIV':  [('R1', '2'), ('R2', '1'), ('U1', '5')],
    'BOOT':    [('C4', '1'), ('U1', '6')],
    'SW':      [('U1', '2'), ('C4', '2'), ('L1', '1')],
    '+5V':     [('L1', '2'), ('R3', '1'), ('C5', '1'), ('C6', '1'), ('C7', '1'), ('U2', '3'),
                ('C8', '1'), ('J3', '1')],
    'FB':      [('R3', '2'), ('R4', '1'), ('U1', '4')],
    '+3V3':    [('U2', '2'), ('U2', '4'), ('C9', '1'), ('C10', '1'), ('R5', '1'), ('J2', '1')],
    'LED_A':   [('R5', '2'), ('D3', '1')],
}

# pin -> net 反查
PIN2NET = {}
for net, pins in NETS.items():
    for ref, pin in pins:
        assert (ref, pin) not in PIN2NET, f'重复: {ref}-{pin}'
        PIN2NET[(ref, pin)] = net

# ═══════════════════════════════ 版图坐标
COLS = [55.0, 125.0, 195.0, 265.0, 335.0]
ROWS = [60.0, 108.0, 156.0, 204.0, 252.0]
STUB = 3.81

positions = {}
for i, (ref, sym, val) in enumerate(COMPONENTS):
    row, col = divmod(i, len(COLS))
    positions[ref] = (COLS[col], ROWS[row])


def pin_point(ref, pnum):
    """返回引脚连接点的原理图坐标 + 外伸方向"""
    sym_name = next(s for r, s, v in COMPONENTS if r == ref)
    sym = SYMBOLS[sym_name]
    X, Y = positions[ref]
    for (num, name, px, py, ang, ln) in sym['pins']:
        if num == pnum:
            sx, sy = X + px, Y - py          # y 翻转
            # angle 指向元件本体；外伸方向 = 反方向，且 y 分量再翻转一次
            d = {0: (-1, 0), 90: (0, 1), 180: (1, 0), 270: (0, -1)}[ang]
            return (sx, sy), d
    raise KeyError(f'{ref} 没有引脚 {pnum}')


def fnum(v):
    s = f'{v:.4f}'.rstrip('0').rstrip('.')
    return s if s else '0'


# ═══════════════════════════════ 组装
ROOT_UUID = uid('root-sheet')
out = []
out.append('(kicad_sch')
out.append('  (version 20230121)')
out.append('  (generator eeschema)')
out.append(f'  (uuid "{ROOT_UUID}")')
out.append('  (paper "A3")')
out.append('  (title_block')
out.append('    (title "12V→5V→3V3 电源链")')
out.append('    (rev "A")')
out.append(f'    (comment 1 "{PROJECT}")')
out.append('  )')

# ---- lib_symbols
out.append('  (lib_symbols')
for name, sym in SYMBOLS.items():
    libid = f'jlcpwr:{name}'
    out.append(f'    (symbol "{libid}"')
    out.append('      (pin_numbers hide)')
    out.append('      (pin_names (offset 0.508))')
    out.append('      (in_bom yes) (on_board yes)')
    out.append(f'      (property "Reference" "{"U" if "TPS" in name or "AMS" in name else name[0]}" '
               f'(at 0 {fnum(sym["body_rows"]/2 + 2)} 0) (effects (font (size 1.27 1.27))))')
    out.append(f'      (property "Value" "{name}" (at 0 {fnum(-sym["body_rows"]/2 - 2)} 0) '
               f'(effects (font (size 1.27 1.27))))')
    out.append(f'      (property "Footprint" "" (at 0 0 0) (effects (font (size 1.27 1.27)) hide))')
    out.append(f'      (property "Datasheet" "" (at 0 0 0) (effects (font (size 1.27 1.27)) hide))')
    out.append(f'      (symbol "{name}_0_1"')
    for g in sym['graphics']:
        out.append('        ' + g)
    out.append('      )')
    out.append(f'      (symbol "{name}_1_1"')
    for (num, pname, px, py, ang, ln) in sym['pins']:
        out.append(f'        (pin passive line (at {fnum(px)} {fnum(py)} {ang}) (length {fnum(ln)}) '
                   f'(name "{pname}" (effects (font (size 1.016 1.016)))) '
                   f'(number "{num}" (effects (font (size 1.016 1.016)))))')
    out.append('      )')
    out.append('    )')
out.append('  )')

# ---- 元件实例
for ref, sym_name, val in COMPONENTS:
    X, Y = positions[ref]
    u = uid('sym-' + ref)
    out.append(f'  (symbol (lib_id "jlcpwr:{sym_name}") (at {fnum(X)} {fnum(Y)} 0) (unit 1)')
    out.append('    (in_bom yes) (on_board yes) (dnp no) (fields_autoplaced)')
    out.append(f'    (uuid "{u}")')
    out.append(f'    (property "Reference" "{ref}" (at {fnum(X+13)} {fnum(Y-2)} 0) '
               f'(effects (font (size 1.27 1.27)) (justify left)))')
    out.append(f'    (property "Value" "{val}" (at {fnum(X+13)} {fnum(Y+1.5)} 0) '
               f'(effects (font (size 1.27 1.27)) (justify left)))')
    out.append(f'    (property "Footprint" "" (at {fnum(X)} {fnum(Y)} 0) '
               f'(effects (font (size 1.27 1.27)) hide))')
    out.append(f'    (property "Datasheet" "" (at {fnum(X)} {fnum(Y)} 0) '
               f'(effects (font (size 1.27 1.27)) hide))')
    for (num, pname, px, py, ang, ln) in SYMBOLS[sym_name]['pins']:
        out.append(f'    (pin "{num}" (uuid "{uid("pin-"+ref+"-"+num)}"))')
    out.append(f'    (instances (project "{PROJECT}" '
               f'(path "/{ROOT_UUID}" (reference "{ref}") (unit 1))))')
    out.append('  )')

# ---- 每个引脚: 短线 + 网络标签
LABEL_DIRS = {(1, 0): ('left', 'bottom'), (-1, 0): ('right', 'bottom'),
              (0, -1): ('left', 'bottom'), (0, 1): ('left', 'top')}
for ref, sym_name, val in COMPONENTS:
    for (num, pname, px, py, ang, ln) in SYMBOLS[sym_name]['pins']:
        (sx, sy), (dx, dy) = pin_point(ref, num)
        ex, ey = sx + dx * STUB, sy + dy * STUB
        net = PIN2NET[(ref, num)]
        out.append(f'  (wire (pts (xy {fnum(sx)} {fnum(sy)}) (xy {fnum(ex)} {fnum(ey)}))')
        out.append(f'    (stroke (width 0) (type default)) (uuid "{uid(f"w-{ref}-{num}")}"))')
        ha, va = LABEL_DIRS[(dx, dy)]
        out.append(f'  (label "{net}" (at {fnum(ex)} {fnum(ey)} 0)'
                   f' (effects (font (size 1.016 1.016)) (justify {ha} {va}))'
                   f' (uuid "{uid(f"l-{ref}-{num}")}"))')

out.append('  (sheet_instances (path "/" (page "1")))')
out.append(')')

text = '\n'.join(out) + '\n'

path = r'G:\PCB\灯带自定义\v1\电源链原理图_v1.kicad_sch'
with open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(text)

print('写入:', path)
print('字节数:', len(text.encode('utf-8')))
print('括号平衡:', text.count('('), 'vs', text.count(')'))
