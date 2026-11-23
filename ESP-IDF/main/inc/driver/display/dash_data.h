/**
 * @file dash_data.h
 * @brief 把 LEDDrive 的真实状态喂给原作者的单页仪表盘
 */

#ifndef DASH_DATA_H
#define DASH_DATA_H

/* 启动 1 Hz 数据适配任务（dash_init() 之后调用） */
void dash_data_start(void);

#endif /* DASH_DATA_H */
