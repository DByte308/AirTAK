// AirTAK - Airsoft Team Awareness Kit
// firmware/src/platform/BoardConfig.h - selected board configuration

#pragma once

#if defined(AIRTAK_BOARD_T_ECHO_PLUS)
#include "platform/boards/t_echo_plus/BoardConfig.h"
#elif defined(AIRTAK_NATIVE_TEST)
#include "platform/boards/native/BoardConfig.h"
#else
#error "No AirTAK board selected. Define AIRTAK_BOARD_T_ECHO_PLUS or add a platform/boards/<board>/BoardConfig.h selector."
#endif
