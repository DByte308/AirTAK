// AirTAK — ESP32 FreeRTOS shim: FreeRTOS.h
// Forwards to <freertos/FreeRTOS.h> so the codebase's flat #include <FreeRTOS.h>
// works on both the nRF52 BSP (which provides FreeRTOS.h at the flat path) and
// the ESP32 Arduino framework (which puts it under freertos/).
#pragma once
#include <freertos/FreeRTOS.h>
