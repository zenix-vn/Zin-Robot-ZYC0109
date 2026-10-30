idf_component_register(SRCS "main.c" "pad.c"
                       INCLUDE_DIRS "."
                       REQUIRES bluepad32 btstack nvs_flash esp_wifi esp_netif esp_event esp_http_server
                                esp_driver_uart esp_driver_ledc esp_driver_gpio esp_timer)
