# 手机 App 对接任务清单（v0.2 · 已冻结）

对应固件：`LEDDrive` / ESP32-S3 / NimBLE 外设。
所有整数小端。MTU 请求 247，**单包数据统一 ≤ 230 字节**。

v0.2 变更：新增 `schd_del` / `schd_dok` / `devinfo`；`time_state` 加设备当前时间；
`resp_dat` 的 type 1/4 带回最终名字；错误码分档；重复时间点改为拒绝；`clk_stat` 去掉 NOTIFY。
绑定独立持久化，导出 / 导入必须包含。

---

## 1. 服务与特征

| 服务 | 特征 | 属性 | 说明 |
|---|---|---|---|
| `time_svc` | `time_set` | WRITE | 8 字节时间包 |
| | `time_req` | NOTIFY | 设备请求时间（1 字节 seq） |
| | `time_state` | READ | 9 字节 |
| `prst_svc` | `prst_lst` | READ | `[count][id,flags]×count` |
| | `prst_red` | WRITE | `[id][offset u16]` 请求一块 |
| | `prst_dat` | NOTIFY | 分块数据 |
| | `prst_wrt` | WRITE | 分块写（id=0 新建） |
| | `prst_del` | WRITE+READ | 写 id，读使用列表 |
| | `prst_dok` | WRITE | `[id]` 确认删除 |
| | `resp_dat` | NOTIFY | 通用结果 |
| `schd_svc` | `schd_lst` | READ | `[count][id]×count` |
| | `schd_get` | WRITE+READ | 写 id，读详情 |
| | `schd_set` | WRITE | 新建/修改 |
| | `schd_del` | WRITE+READ | 写 id，读使用列表 |
| | `schd_dok` | WRITE | `[id]` 确认删除 |
| | `bind_get` | WRITE+READ | 写 seg，读绑定 |
| | `bind_set` | WRITE | 设置绑定 |
| | `io_mode` | READ+WRITE | 读 8 字节，写 2 字节 |
| | `clk_stat` | READ | 运行状态（只读，不推送） |
| | `edit_cmt` | WRITE | `[seg][save]` |
| | `devinfo` | READ | 8 字节设备信息 |

> `io_mode` 的 UUID 是 `io_mode` + 一个空格（8 字节），别抄成 7 个字符。

## 2. 包格式

### 2.1 时间

```
time_set     [year u16][mon u8][day u8][hour u8][min u8][sec u8][wday u8]
             year 2020..2099；wday 0=周日..6=周六；本地时间（CST-8）
time_req     [seq u8]
time_state   [valid u8][next_event_sec u32][now_epoch u32]
             next=0 表示今天没有更多时间点；now 是设备当前 UTC 秒
```

### 2.2 预设记录（270 字节 effect）

```
0    mode u8        0=OFF 1=WAVE 2=STATIC 3=BREATH 4=CHASE 5=POINTS 6=FLASH
1    brightness u8
2    wave.speed u16
4    wave.dir u8     0=右 1=左
5    solid r,g,b
8    breath r,g,b
11   breath.period u16
13   chase.count u8
14   chase.speed u16
16   chase.dir u8
17   chase.colors[8] 24 字节
41   points.wrap u8
42   points.count u8
43   points[8] × 28  [pos u8][period u16][color_count u8][colors[8]×3]
267  flash.mode u8
268  flash.period u16
```

记录 = `[name_len u8][name][270]`，`name` UTF-8，最多 63 字节，不带结尾 0。

### 2.3 预设读写

```
prst_red 写   [id][offset u16]
prst_dat 通知 [id][offset u16][total u16][len u8][data]   data ≤ 230
              写一次推一块；offset 由 App 递增；total=0 表示读完/不存在
prst_wrt 写   [id][total u16][offset u16][data]           data ≤ 230
              id=0 新建，设备分配 id
prst_del 写   [id] → 随后读同一特征：
              首行“总数”，之后每行 "IO号,灯段名"
prst_dok 写   [id]
```

### 2.4 结果通知 `resp_dat`

```
[type u8][a u8][b u8][status u8]              固定 4 字节
type 1 / 4 额外带名字：
[type][a][b][status][name_len u8][name...]    设备最终采用的名字

status: 0=成功 1=其他失败 2=容量满 3=只读 4=不存在 5=参数不合法

type=1 预设写入      a=预设 id（新建时为分配到的 id）
type=2 草稿提交      a=seg，b=新预设 id（save=0 时 b=0xFF）
type=3 预设删除      a=被删 id，b=改绑的 ROM id
type=4 调度写入      a=调度 id
type=5 绑定写入      a=seg
type=6 对时          a=status
type=7 IO 模式       a=io，b=mode
type=8 调度删除      a=调度 id
```

### 2.5 调度 / 绑定 / 状态

```
schd_lst 读   [count][id]×count
schd_get 写   [id]
schd_get 读   [id][name_len][name][slot_count][at_sec u32 × count]
schd_set 写   同读格式；id=0 新建；重复时间点返回 status=5
schd_del 写   [id] → 读同特征得到使用列表（格式同 prst_del）
schd_dok 写   [id]
bind_get 写   [seg]，读 [seg][sched][slot_count][preset_id × count]
bind_set 写   [seg][sched][slot_count][preset_id × count]
              0xFF = 关灯；sched=0 = 解除绑定
io_mode  读   [io][mode]×4；写 [io][mode]   mode 0=手动 1=时间
clk_stat 读   [valid][next_event u32][count][seg, active_preset, io_mode]×count
edit_cmt 写   [seg][save]   save=1 存新预设并绑当前槽
devinfo  读   [fw_major][fw_minor][fw_patch][io_count][seg_total]
              [preset_max][sched_max][slot_max]
```

## 3. 逐条答复（对应 App 的 21 个问题）

1. **删除调度**：新特征 `schd_del` + `schd_dok`。先写 `schd_del` 拿使用列表，确认后写 `schd_dok`。删除后这些灯段变成“未绑定”，时间模式下会关灯，App 必须提示。
2. **改名**：`resp_dat` type 1/4 现在带最终 `name`，不用回读。
3. **flags**：bit0 = ROM，其余保留为 0。只读只看 bit0。
4. **分块读**：App 循环写 `prst_red`，offset 每次 +230；设备每次只推一块；`total=0` 结束。
5. **分块大小**：读写统一 **230**（固件写入口兼容到 235，但 App 按 230 发）。
6. **名字**：UTF-8，最长 63 字节，`name_len` 按字节。
7. **id**：预设 1..32，ROM 0x80..0x86，0xFF 空；调度 1..8；写接口里 0 = 新建。
8. **clk_stat**：只读、不推送；每次操作后主动读一次。`time_req`/`prst_dat`/`resp_dat` 才需要订阅。
9. **时间显示**：用 `time_state` 的 `now_epoch` 显示设备时间，App 本地每秒自增，每次操作后再读一次校准。
10. **时间点**：本地当天秒，<86400，界面可精确到秒（一般 :00）。重复时间点**直接拒绝**（status=5），App 先弹“覆盖”，替换后再整表提交；槽位顺序按 App 提交的顺序，绑定也按这个顺序写。（已确认）
11. **0xFF 与 0x80**：界面上都叫“关灯”，只显示一个选项；0xFF 用于空槽，0x80 是普通预设。
12. **库里改预设**：立即生效（1 秒内所有引用它的灯段重刷），不是草稿、不弹窗。
13. **手动模式**：直接写、直接存 NVS，不弹“保存为新预设”；只有时间模式 IO 才走草稿流程。
14. **返回**：“返回”= 发 `edit_cmt save=0`；不发就一直保持草稿，直到断线。
15. **导入**：以第 6 节为准——调度按内容去重、重名顺延、空位新增；绑定按 **seg_id** 覆盖。新设备没有该 seg_id 就跳过并在结果里报告。段顺序不会影响匹配。（已确认）
16. **meta.fw**：读 `devinfo` 前 3 字节。
17. **JSON**：本项目内部格式，保留 `schema_version`，不承诺给第三方客户端。
18. **UI 位置**：建议灯带页顶部放 IO 模式；预设库 / 调度 / 绑定做成灯带页的三个二级入口；绑定挂在灯段详情里（每段一个“调度绑定”）。
19. **后台**：本版不做常驻对时，App 进后台/锁屏后设备保持黑，等下次前台连接再对时。
20. **错误码**：已分档（2 满 / 3 只读 / 4 不存在 / 5 参数），App 可以直接映射文案；1 作为兜底。
21. **对时**：无论手机在哪个时区，都换算成 **CST-8** 再写；`wday` 0=周日..6=周六。

## 4. 关键流程

### 4.1 连接

1. 请求 MTU 247。
2. 订阅 `time_req`、`resp_dat`、`prst_dat`。
3. 立刻写 `time_set`（CST-8）。
4. 读 `devinfo`、`prst_lst`、`schd_lst`、`io_mode`、`clk_stat`，按需读详情。

### 4.2 改灯效（时间模式）

1. 直接写段特征（`light_mode` / `seg_brightness` / `cfg_lt_*`）；
2. 设备自动进草稿，画面实时变；
3. 完成 → 弹“保存为新预设？”
   - 是 → `edit_cmt [seg][1]`，设备建预设并绑当前槽，`resp_dat type=2` 回新 id；
   - 否 → `edit_cmt [seg][0]`，恢复原槽预设；
4. 草稿期间到点：画面保持草稿（方案 A）；断线自动丢弃。

### 4.3 删除预设 / 调度

1. 写 `prst_del` / `schd_del` 拿使用列表（首行总数）；
2. 确认后写 `prst_dok` / `schd_dok`；
3. 预设：这些段改绑同 mode 的 ROM 预设（`0x80+mode`）；调度：这些段解绑并关灯；
4. 等 `resp_dat type=3/8` 刷新。

## 5. 导入 / 导出

**绑定是独立持久化的**：固件按灯段把 `sched_id + 每个时间点的 preset_id` 存进 NVS（键 `b<seg_id>`），
开机随调度一起恢复；删除调度 / 删除预设时会同步重写绑定并落盘。
所以导出必须包含绑定，导入也必须写回绑定，否则新设备上灯段会处于“未绑定 → 时间模式关灯”。

- **导出**：App 读全量数据拼 JSON（`schema_version`、`meta`、`presets[]`、`schedules[]`、`bindings[]`、`io_modes[]`）。
  `bindings[]` 每条：`{seg_id, sched_id, preset_ids[]}`。
- **导入**（增加模式）：
  1. 比较 effect 270 字节，完全相同就跳过（忽略名字和 id）；
  2. 新建用 `prst_wrt id=0`，用 `resp_dat type=1` 的 id 与 name 建映射；
  3. 空位不足：提示“目前 X / 总 32 / 待导入 Y”，列出待导入项供删除，删到放得下再继续；
  4. 调度同样去重、改名、占空位；
  5. 绑定按 seg_id 覆盖，缺段跳过并报告；
  6. 进度条按“预设 + 调度 + 绑定 + 模式”总步数推进。

## 6. 已确认的约定（v0.2 冻结）

- 草稿跨时间点 = 方案 A：画面保持草稿，到点不跳新槽，直到保存 / 取消 / 断线；
- 重复时间点：设备拒绝，App 负责“覆盖”交互；
- 导入调度 = 按内容去重后新增；绑定 = 按 `seg_id` 覆盖；
- 绑定随灯段持久化在 NVS，导出 / 导入必须带上；
- ROM 预设名：关灯 / 随波逐流 / 常亮 / 呼吸 / 追逐 / 多点 / 爆闪，界面用下划线等样式区分，不加前缀；
- 本版不对后台常驻对时做要求，App 回到前台后重连即对时。

无其它未决项。
