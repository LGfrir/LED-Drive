# -*- coding: utf-8 -*-
"""把 BOM 输出成 xlsx（含汇总页与位号明细）"""
import json
import sys
sys.stdout.reconfigure(encoding='utf-8')

from openpyxl import Workbook
from openpyxl.styles import Font, Alignment, PatternFill, Border, Side
from openpyxl.utils import get_column_letter

rows = json.load(open(r'E:\.Agent\tmp\schem\bom_final.json', encoding='utf-8'))

HEAD_FILL = PatternFill('solid', fgColor='1F3864')
HEAD_FONT = Font(color='FFFFFF', bold=True, size=10)
THIN = Side(style='thin', color='BFBFBF')
BORDER = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)


def style_header(ws, ncol):
    for c in range(1, ncol + 1):
        cell = ws.cell(row=1, column=c)
        cell.fill = HEAD_FILL
        cell.font = HEAD_FONT
        cell.alignment = Alignment(horizontal='center', vertical='center')
        cell.border = BORDER
    ws.freeze_panes = 'A2'


wb = Workbook()

# ── Sheet1: 位号明细
ws = wb.active
ws.title = '位号明细'
headers = ['位号', '值', '说明', 'LCSC料号', '型号', '封装', '品牌', '库类型', 'SMT可贴', '库存', '单价(￥)', '小计(￥)']
ws.append(headers)
for r in rows:
    price = r.get('price') or 0
    ws.append([r['ref'], r['val'], r['desc'], r['lcsc'], r.get('model'), r.get('package'),
               r.get('brand'), r.get('class'), 'Y' if r.get('smt') else 'N',
               r.get('stock') or 0, price, round(price, 4)])
style_header(ws, len(headers))
for row in ws.iter_rows(min_row=2, max_row=ws.max_row, max_col=len(headers)):
    for cell in row:
        cell.border = BORDER
        cell.font = Font(size=9)
for col, w in zip('ABCDEFGHIJKL', [7, 13, 26, 12, 22, 16, 18, 12, 9, 10, 10, 10]):
    ws.column_dimensions[col].width = w
ws.auto_filter.ref = f'A1:L{ws.max_row}'

# ── Sheet2: 唯一料号汇总
ws2 = wb.create_sheet('料号汇总')
seen = {}
for r in rows:
    if r['lcsc'] not in seen:
        seen[r['lcsc']] = {**r, 'refs': []}
    seen[r['lcsc']]['refs'].append(r['ref'])
h2 = ['LCSC料号', '位号', '数量', '型号', '封装', '库类型', '库存', '单价(￥)']
ws2.append(h2)
for code, v in seen.items():
    ws2.append([code, ' '.join(v['refs']), len(v['refs']), v.get('model'), v.get('package'),
                v.get('class'), v.get('stock'), v.get('price')])
style_header(ws2, len(h2))
for row in ws2.iter_rows(min_row=2, max_row=ws2.max_row, max_col=len(h2)):
    for cell in row:
        cell.border = BORDER
        cell.font = Font(size=9)
for col, w in zip('ABCDEFGH', [12, 26, 7, 24, 18, 13, 11, 10]):
    ws2.column_dimensions[col].width = w

# ── Sheet3: 汇总信息
ws3 = wb.create_sheet('汇总')
total = sum((r.get('price') or 0) for r in rows)
basic = sum(1 for v in seen.values() if v.get('class') == 'Basic Part')
info = [
    ('工程', '灯带自定义 v1 — 12V→5V→3V3 电源链'),
    ('元件总数', len(rows)),
    ('唯一料号数', len(seen)),
    ('其中基础库(Basic)', basic),
    ('其中扩展库(Extended)', len(seen) - basic),
    ('全部 SMT 可贴', '是' if all(r.get('smt') for r in rows) else '否'),
    ('单板器件成本(￥)', round(total, 2)),
    ('最小库存', min((r.get('stock') or 0) for r in rows)),
    ('', ''),
    ('数据来源', 'pro.lceda.cn 器件搜索接口，逐料号反查核对'),
    ('生成时间', __import__('datetime').datetime.now().strftime('%Y-%m-%d %H:%M')),
]
for k, v in info:
    ws3.append([k, v])
ws3.column_dimensions['A'].width = 22
ws3.column_dimensions['B'].width = 46
for row in ws3.iter_rows(min_row=1, max_row=ws3.max_row, max_col=2):
    row[0].font = Font(bold=True, size=10)
    row[1].font = Font(size=10)

out = r'G:\PCB\灯带自定义\v1\BOM_电源链_v1.xlsx'
wb.save(out)
print('saved', out)
print('元件', len(rows), '唯一料号', len(seen), '基础库', basic, '成本', round(total, 2))
