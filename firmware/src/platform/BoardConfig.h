// AirTAK - Airsoft Team Awareness Kit
// firmware/src/platform/BoardConfig.h - selected board configuration

#pragma once

#if defined(AIRTAK_BOARD_T_ECHO)
#include "platform/boards/t_echo/BoardConfig.h"
#elif defined(AIRTAK_BOARD_T_ECHO_PLUS)
#include "platform/boards/t_echo_plus/BoardConfig.h"
#elif defined(AIRTAK_BOARD_T_DECK)
#include "platform/boards/t_deck/BoardConfig.h"
#elif defined(AIRTAK_BOARD_HELTEC_T114)
#include "platform/boards/heltec_t114/BoardConfig.h"
#elif defined(AIRTAK_BOARD_HELTEC_V3)
#include "platform/boards/heltec_v3/BoardConfig.h"
#elif defined(AIRTAK_BOARD_T3S3)
#include "platform/boards/t3s3/BoardConfig.h"
#elif defined(AIRTAK_NATIVE_TEST)
#include "platform/boards/native/BoardConfig.h"
#else
#error "No AirTAK board selected. Define AIRTAK_BOARD_T_ECHO, AIRTAK_BOARD_T_ECHO_PLUS, AIRTAK_BOARD_T_DECK, AIRTAK_BOARD_HELTEC_T114, AIRTAK_BOARD_HELTEC_V3, AIRTAK_BOARD_T3S3, or add a platform/boards/<board>/BoardConfig.h selector."
#endif
