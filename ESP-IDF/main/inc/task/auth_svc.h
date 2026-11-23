/**
 * @file auth_svc.h
 * @brief 六位数字密码和已识别手机。密码、蓝牙名、手机名单都在 NVS 命名空间 "auth"。
 */

#ifndef __AUTH_SVC_H__
#define __AUTH_SVC_H__

#include <stdbool.h>
#include <stdint.h>

#define AUTH_PIN_LEN 6
#define AUTH_NAME_MAX 31
#define AUTH_DEV_MAX 8

/* 写特征被拒绝时带回的 ATT 错误，手机按这个数显示原因。 */
#define AUTH_ERR_PIN    0x80
#define AUTH_ERR_FORMAT 0x81
#define AUTH_ERR_OLD    0x82
#define AUTH_ERR_STATE  0x83

void auth_init(void);
void auth_on_disconnect(void);
const char *auth_device_name(void);

/* 未核对的连接读不了灯带。返回 0 表示已放行。 */
int auth_guard(void);

#endif /* __AUTH_SVC_H__ */
