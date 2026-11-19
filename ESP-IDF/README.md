# LEDDrive

ESP32-S3 灯带控制器。手机端协议、预设、调度和绑定以 [docs/APP_TASKS.md](docs/APP_TASKS.md) 为准。
上面这份文档描述当前 GATT 服务、灯效记录和调度；本页开头不再描述已经不用的流水灯示例。

广播名是 `LEDDrive`。手机按 `io_svc` 的 128 位 UUID 过滤，不按旧的玩笑名字找设备。

- 芯片：ESP32-S3
- 框架：ESP-IDF
- 数据线：GPIO14
- 地线参考：GPIO13（不能当灯带的大电流地线，灯带 GND 要接到板子 GND）

## 接线

| 灯带引脚 | 接到 ESP32-S3 | 说明 |
|---|---|---|
| VCC / 5V | 5V（VIN） | 整条全白时电流可达约 1.5 A |
| GND | 板子 GND | GPIO13 只是被拉低的电平参考，不能走灯带大电流 |
| DIN | GPIO14 | WS2812 数据 |

GPIO 只能灌入约 40 mA。灯带的大电流回路要接到板子的 GND。3.3 V 数据若推不动 5 V 灯带，需要电平转换。

灯效、预设、调度和手机通信不要改这里的旧示例参数。当前行为见 [docs/APP_TASKS.md](docs/APP_TASKS.md)。

## 编译与烧录

```powershell
# 先加载 ESP-IDF 环境（IDF 在 E:\ESP-IDF\esp-idf-v6.0.1）
. E:\ESP-IDF\esp-idf-v6.0.1\export.ps1

cd G:\ESP32\LEDDrive
idf.py build
idf.py -p COM7 flash monitor      # 串口按实际修改
```

也可以在 VS Code 的 ESP-IDF 插件里直接点 Build / Flash。

## 实现说明

- 用 RMT 的 **bytes encoder** 把每个字节展开成 8 个 symbol，再用 **copy encoder**
  在帧尾插一段 50 µs 低电平做 RESET 锁存；每帧都由硬件 DMA-less 流式发送，CPU 只负责组帧。
- RMT 分辨率 20 MHz（1 tick = 50 ns），时序取 WS2812 手册值：
  `T0H=0.40µs / T0L=0.85µs`，`T1H=0.80µs / T1L=0.45µs`，周期正好 1.25 µs。
- 数据格式 **GRB**、每字节高位先发；先写 `frame[idx*3+0]=G, +1=R, +2=B`。
  如果点出来红绿反了，把 `put_pixel()` 里的 G / R 对调即可。
- 帧率：30 颗 × 24 bit × 1.25 µs ≈ 0.9 ms/帧，`vTaskDelay(50ms)` 实际由延时决定。

## 目录

```
├── CMakeLists.txt
├── main
│   ├── CMakeLists.txt
│   └── led_strip_main.c      # 全部逻辑都在这里
├── sdkconfig                 # 已配置为 esp32s3
└── README.md
```
