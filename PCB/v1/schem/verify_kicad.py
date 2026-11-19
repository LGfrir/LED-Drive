# -*- coding: utf-8 -*-
"""回读校验生成的 .kicad_sch：解析 s-expr，检查元件/引脚/标签/连线一致性。"""
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding='utf-8')

PATH = r'G:\PCB\灯带自定义\v1\电源链原理图_v1.kicad_sch'
txt = open(PATH, encoding='utf-8').read()


def tokenize(s):
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if c in ' \t\r\n':
            i += 1
        elif c in '()':
            yield c
            i += 1
        elif c == '"':
            j, buf = i + 1, []
            while j < n:
                if s[j] == '\\':
                    buf.append(s[j + 1]); j += 2
                elif s[j] == '"':
                    break
                else:
                    buf.append(s[j]); j += 1
            yield ('str', ''.join(buf)); i = j + 1
        else:
            j = i
            while j < n and s[j] not in ' \t\r\n()"':
                j += 1
            yield ('atom', s[i:j]); i = j


def _build(tokens):
    out = []
    for tok in tokens:
        if tok == '(':
            out.append(_build(tokens))
        elif tok == ')':
            return out
        else:
            out.append(tok[1])
    raise SyntaxError('括号未闭合')


_it = tokenize(txt)
_first = next(_it)
assert _first == '(', f'文件开头不是括号: {_first!r}'
tree = _build(_it)


def find_all(node, name):
    return [ch for ch in node if isinstance(ch, list) and ch and ch[0] == name]


def get(node, name):
    r = find_all(node, name)
    return r[0] if r else None


print('顶层:', tree[0], '| 子节点:', len(tree))

libsyms = get(tree, 'lib_symbols')
sym_defs = find_all(libsyms, 'symbol')
placed = find_all(tree, 'symbol')
labels = find_all(tree, 'label')
wires = find_all(tree, 'wire')

print('库符号:', len(sym_defs), [s[1] for s in sym_defs])
print('元件实例:', len(placed), '| 导线:', len(wires), '| 标签:', len(labels))

# 引脚定义
pin_defs = {}
for sd in sym_defs:
    libid = sd[1]
    d = {}
    for sub in find_all(sd, 'symbol'):
        for p in find_all(sub, 'pin'):
            at = get(p, 'at')
            num = get(p, 'number')[1]
            d[num] = (float(at[1]), float(at[2]), int(at[3]))
    pin_defs[libid] = d

# 引脚绝对坐标
pin_abs = {}
for sym in placed:
    libid = get(sym, 'lib_id')[1]
    at = get(sym, 'at')
    X, Y = float(at[1]), float(at[2])
    ref = None
    for p in find_all(sym, 'property'):
        if p[1] == 'Reference':
            ref = p[2]
    if libid not in pin_defs:
        print('!! 未知库符号', libid)
        continue
    for num, (px, py, ang) in pin_defs[libid].items():
        pin_abs[(ref, num)] = (round(X + px, 3), round(Y - py, 3))

wire_ends = set()
for w in wires:
    pts = get(w, 'pts')
    for p in find_all(pts, 'xy'):
        wire_ends.add((round(float(p[1]), 3), round(float(p[2]), 3)))

label_pos = {}
for l in labels:
    at = get(l, 'at')
    label_pos.setdefault((round(float(at[1]), 3), round(float(at[2]), 3)), []).append(l[1])

# 检查 1
missing = [f'{r}-{n}' for (r, n), pos in pin_abs.items() if pos not in wire_ends]
print('\n[1] 引脚未接导线:', missing if missing else '无')

# 检查 2
unlabeled = []
for w in wires:
    pts = get(w, 'pts')
    xy = [(round(float(p[1]), 3), round(float(p[2]), 3)) for p in find_all(pts, 'xy')]
    if not any(q in label_pos for q in xy):
        unlabeled.append(xy)
print('[2] 无网络标签的导线:', len(unlabeled), unlabeled[:3] if unlabeled else '')

# 检查 3
net_count = defaultdict(int)
for pos, names in label_pos.items():
    for nm in names:
        net_count[nm] += 1
print('[3] 各网络标签数:')
weak = []
for n in sorted(net_count):
    if net_count[n] < 2:
        weak.append(n)
    print(f'    {n:<10} {net_count[n]:>3}' + ('   <-- 悬空!' if net_count[n] < 2 else ''))

# 检查 4
expect = {'R': 2, 'L': 2, 'C': 2, 'D': 2, 'CONN2': 2, 'TPS54202': 6, 'AMS1117': 4}
bad = []
for sym in placed:
    libid = get(sym, 'lib_id')[1].split(':')[1]
    n = len(pin_defs[get(sym, 'lib_id')[1]])
    if n != expect[libid]:
        bad.append((libid, n))
print('[4] 引脚数异常:', bad if bad else '无')

# 检查 5: 同一位置不能有两个不同网络
clash = []
for pos, names in label_pos.items():
    if len(set(names)) > 1:
        clash.append((pos, names))
print('[5] 同点多网络冲突:', clash if clash else '无')

total = len(pin_abs)
print('\n统计: 元件 %d, 引脚 %d, 标签 %d, 网络 %d' % (len(placed), total, len(labels), len(net_count)))
print('引脚全部有标签:', len(labels) == total)
print('结论:', 'PASS' if (not missing and not unlabeled and not weak and not bad and not clash
                          and len(labels) == total) else 'FAIL')
