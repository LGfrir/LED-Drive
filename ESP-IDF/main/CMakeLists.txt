idf_component_register(
    SRCS
        "src/main.c"
        "src/driver/ws2812/ws2812_rmt.c"
        "src/driver/ws2812/ws2812_spi.c"
        "src/driver/bt/NimBLE.c"
        "src/driver/led_strip/light_mode.c"
        "src/driver/led_strip/light_mode_driver.c"
        "src/driver/nvs/nvs_flash_save.c"
        "src/driver/preset/light_preset.c"
        "src/driver/timer/local_clock.c"
        "src/driver/timer/time_sync.c"
        "src/task/clock_scheduler.c"
        "src/task/gatt_svc.c"
        "src/task/gatt_time_svc.c"
        "src/task/auth_svc.c"
        "src/driver/display/lcd.c"
        "src/driver/display/disp_dash.c"
        "src/driver/display/dash_data.c"
        "src/driver/wifi/wifi_mgr.c"
        "src/task/ota_svc.c"
        "src/task/ota_ble_svc.c"
    INCLUDE_DIRS
        "inc"
    REQUIRES
        bt
        nvs_flash
        esp_driver_gpio
        esp_driver_rmt
        esp_driver_spi
        esp_lcd
        esp_timer
        esp_wifi
        esp_netif
        esp_event
        esp_http_client
        app_update
        bootloader_support
        mbedtls
        esp_app_format
        freertos
)

target_compile_definitions(${COMPONENT_LIB} PUBLIC LCD_SPI_HOST=SPI3_HOST)

if(TEST_BOARD)
    target_compile_definitions(${COMPONENT_LIB} PUBLIC TEST_BOARD=1)
endif()
