// MASH - Mesh Airsoft Situational Hub
// firmware/src/platform/BoardConfig.h - selected board configuration

#pragma once

#if defined(MASH_BOARD_T_ECHO_PLUS)
#include "platform/boards/t_echo_plus/BoardConfig.h"
#elif defined(MASH_NATIVE_TEST)
#include "platform/boards/native/BoardConfig.h"
#else
#error "No MASH board selected. Define MASH_BOARD_T_ECHO_PLUS or add a platform/boards/<board>/BoardConfig.h selector."
#endif
