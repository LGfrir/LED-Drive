# -*- coding: utf-8 -*-
"""最终选型 → 反查校验 LCSC 料号 → 输出 BOM(json/csv)"""
import csv
import json
import sys
import time
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding='utf-8')
API = 'https://pro.lceda.cn/api/eda/product/search'
PROXY = 'http://127.0.0.1:7900'
_op = urllib.request.build_opener(urllib.request.ProxyHandler({'http': PROXY, 'https': PROXY}))


def by_code(code):
    """按料号反查：用 keyword 搜，再精确匹配 code"""
    url = API + '?' + urllib.parse.urlencode({'keyword': code, 'page': 1, 'pageSize': 20})
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    lst = (json.loads(_op.open(req, timeout=25).read().decode('utf-8'))
           .get('result') or {}).get('productList') or []
    for it in lst:
        if (it.get('code') or '').upper() == code.upper():
            return it
    return None


def st(it):
    try:
        return int(it.get('stockNumber') or 0)
    except ValueError:
        return 0


def pr(it):
    p = it.get('priceList') or []
    return p[0]['price'] if p else None


# (位号, 值, 说明, LCSC)
BOM = [
    ('U1', 'TPS54202', '同步降压 4.5-28V 2A', 'C191884'),
    ('U2', 'AMS1117-3.3', 'LDO 3.3V 1A', 'C6186'),
    ('D1', 'SS34', '肖特基 40V 3A', 'C8678'),
    ('D2', 'SMAJ15A', 'TVS 15V 单向', 'C86819'),
    ('D3', 'LED-G', '绿色 LED', 'C2986048'),
    ('L1', '15uH', '功率电感 4A', 'C408452'),
    ('F1', 'PTC-2A', '自恢复保险丝 30V 2A', 'C960026'),
    ('C1', '100uF/35V', '铝电解 SMD', 'C2836437'),
    ('C2', '10uF/35V', 'MLCC 1206', 'C6119957'),
    ('C3', '0.1uF/50V', 'MLCC 0603', 'C187860'),
    ('C4', '0.1uF/50V', 'MLCC 0603', 'C187860'),
    ('C5', '22uF/16V', 'MLCC 1206', 'C90146'),
    ('C6', '22uF/16V', 'MLCC 1206', 'C90146'),
    ('C7', '0.1uF/50V', 'MLCC 0603', 'C187860'),
    ('C8', '10uF/16V', 'MLCC 0805', 'C6119886'),
    ('C9', '22uF/16V', 'MLCC 1206', 'C90146'),
    ('C10', '0.1uF/50V', 'MLCC 0603', 'C187860'),
    ('R1', '470k', 'EN 分压上 (原 511k，改值提库存)', 'C23178'),
    ('R2', '100k', 'EN 分压下 (原 105k)', 'C25803'),
    ('R3', '100k', 'FB 分压上', 'C25803'),
    ('R4', '13.3k', 'FB 分压下', 'C2933140'),
    ('R5', '1k', 'LED 限流', 'C21190'),
    ('J1', 'XH2.54-2P', '12V 输入 卧贴', 'C5142098'),
    ('J2', 'XH2.54-2P', '3V3 输出 卧贴', 'C5142098'),
    ('J3', 'XH2.54-2P', '5V 输出 卧贴', 'C5142098'),
]

rows, problems = [], []
seen = {}
for ref, val, desc, code in BOM:
    if code in seen:
        rows.append({**seen[code], 'ref': ref, 'val': val, 'desc': desc})
        continue
    try:
        it = by_code(code)
    except Exception as e:
        problems.append((ref, code, f'查询异常 {e}'))
        continue
    time.sleep(0.3)
    if not it:
        problems.append((ref, code, '料号反查不到'))
        continue
    src = {
        'lcsc': code,
        'model': it.get('model'),
        'package': it.get('standard'),
        'name': it.get('name'),
        'brand': it.get('brandName'),
        'class': it.get('JLCPCB Part Class') or '?',
        'smt': bool(it.get('canPaster')),
        'stock': st(it),
        'price': pr(it),
    }
    seen[code] = src
    rows.append({**src, 'ref': ref, 'val': val, 'desc': desc})

print('%-6s %-12s %-11s %-22s %-16s %-9s %-5s %8s %8s' %
      ('位号', '值', 'LCSC', '型号', '封装', '库', '可贴', '库存', '单价'))
print('-' * 118)
for r in rows:
    print('%-6s %-12s %-11s %-22s %-16s %-9s %-5s %8d %8s' %
          (r['ref'], r['val'], r['lcsc'], str(r.get('model'))[:22], str(r.get('package'))[:16],
           'BASIC' if r.get('class') == 'Basic Part' else 'ext',
           'Y' if r.get('smt') else 'N', r.get('stock') or 0, r.get('price')))

print('\n问题:', problems if problems else '无')

with open(r'E:\.Agent\tmp\schem\bom_final.json', 'w', encoding='utf-8') as f:
    json.dump(rows, f, ensure_ascii=False, indent=1)

with open(r'G:\PCB\灯带自定义\v1\BOM_电源链_v1.csv', 'w', encoding='utf-8-sig', newline='') as f:
    w = csv.writer(f)
    w.writerow(['位号', '值', '说明', 'LCSC料号', '型号', '封装', '品牌', '库类型', 'SMT可贴', '库存', '单价(￥)'])
    for r in rows:
        w.writerow([r['ref'], r['val'], r['desc'], r['lcsc'], r.get('model'), r.get('package'),
                    r.get('brand'), r.get('class'), 'Y' if r.get('smt') else 'N',
                    r.get('stock'), r.get('price')])

uniq = len(seen)
total = sum((r.get('price') or 0) for r in rows)
print(f'\n唯一料号 {uniq} 个 / 总元件 {len(rows)} 个 / 单板器件成本约 ￥{total:.2f}')
print('CSV: G:\\PCB\\灯带自定义\\v1\\BOM_电源链_v1.csv')
