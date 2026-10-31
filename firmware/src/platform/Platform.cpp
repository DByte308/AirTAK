// AirTAK - Airsoft Team Awareness Kit
// firmware/src/platform/Platform.cpp - common platform dispatch

#include "platform/Platform.h"
#include "config/constants/Constants.h"

#include <cstring>

#if !defined(AIRTAK_NATIVE_TEST)
#include <Arduino.h>
#endif

#if defined(AIRTAK_PLATFORM_NRF52)
#define NRF_FICR_BASE        0x10000000UL
#define NRF_FICR_DEVICEID0   (*(volatile uint32_t*)(NRF_FICR_BASE + 0x060))
#define NRF_FICR_DEVICEID1   (*(volatile uint32_t*)(NRF_FICR_BASE + 0x064))
#define NRF_FICR_DEVICEADDR0 (*(volatile uint32_t*)(NRF_FICR_BASE + 0x0A4))
#define NRF_FICR_DEVICEADDR1 (*(volatile uint32_t*)(NRF_FICR_BASE + 0x0A8))
#elif defined(AIRTAK_PLATFORM_ESP32)
#include <esp_efuse.h>
#include <esp_mac.h>
#include <SPI.h>
#include <Wire.h>
#endif

namespace airtak {
namespace platform {

const char* boardName() {
    return AIRTAK_BOARD_NAME;
}

const char* mcuFamilyName() {
#if defined(AIRTAK_PLATFORM_NRF52)
    return "nrf52";
#elif defined(AIRTAK_PLATFORM_ESP32)
    return "esp32";
#elif defined(AIRTAK_PLATFORM_NATIVE)
    return "native";
#else
    return "unknown";
#endif
}

void initBoard() {
#if !defined(AIRTAK_NATIVE_TEST)
#ifdef AIRTAK_RUNTIME_BOARD
    const auto& profile = selectedBoard();
    if (&profile == &unknownBoard()) return;
    if (profile.powerEnablePin >= 0) {
        pinMode(profile.powerEnablePin, OUTPUT);
        digitalWrite(profile.powerEnablePin, profile.powerEnableActiveLow ? LOW : HIGH);
        delay(BOOT_PERIPH_SETTLE_MS);
    }
    // Deassert both devices before starting a potentially shared SPI bus.
    if (profile.radioNss >= 0) {
        pinMode(profile.radioNss, OUTPUT);
        digitalWrite(profile.radioNss, HIGH);
    }
    if (profile.display == DisplayKind::Tft && profile.tftCs >= 0) {
        pinMode(profile.tftCs, OUTPUT);
        digitalWrite(profile.tftCs, HIGH);
    }
    // SPI.begin(-1, ...) may select Arduino defaults, which is not pin-safe.
    if (profile.radioSck >= 0 && profile.radioMiso >= 0 &&
        profile.radioMosi >= 0 && profile.radioNss >= 0)
        SPI.begin(profile.radioSck, profile.radioMiso, profile.radioMosi, profile.radioNss);
    if (profile.i2cSda >= 0 && profile.i2cScl >= 0)
        Wire.begin(profile.i2cSda, profile.i2cScl);
#else
#if defined(AIRTAK_PIN_POWER_EN) && AIRTAK_PIN_POWER_EN >= 0
    pinMode(AIRTAK_PIN_POWER_EN, OUTPUT);
    // Drive to the active state.  Most boards use active HIGH (T-Echo, T114,
    // T-Deck).  Heltec V3 uses active LOW (Vext) — the board config signals
    // this by defining AIRTAK_POWER_EN_ACTIVE_LOW.
#ifdef AIRTAK_POWER_EN_ACTIVE_LOW
    digitalWrite(AIRTAK_PIN_POWER_EN, LOW);
#else
    digitalWrite(AIRTAK_PIN_POWER_EN, HIGH);
#endif
#if defined(AIRTAK_PLATFORM_ESP32)
    delay(BOOT_PERIPH_SETTLE_MS); // peripherals need time to stabilise after the rail comes up
#endif
#endif
#if defined(AIRTAK_PLATFORM_ESP32)
    // Initialise the shared SPI bus for T-Deck (TFT + SX1262 share SCK/MOSI/MISO).
    SPI.begin(AIRTAK_PIN_SX1262_SCK, AIRTAK_PIN_SX1262_MISO,
              AIRTAK_PIN_SX1262_MOSI, AIRTAK_PIN_SX1262_NSS);
    // Initialise the shared I2C bus when needed.  Skip if the board's OLED
    // already uses a different I2C bus (Heltec V3: OLED on 17/18, shared I2C
    // on 41/42 — both need initialisation but only the OLED bus is started here;
    // the shared bus is unused by the standard firmware and started in
    // Display::init() for its own pins).
#if defined(AIRTAK_PIN_I2C_SDA) && AIRTAK_PIN_I2C_SDA >= 0
    Wire.begin(AIRTAK_PIN_I2C_SDA, AIRTAK_PIN_I2C_SCL);
#endif
#endif
#endif // AIRTAK_RUNTIME_BOARD
#endif
}

uint32_t nodeId() {
#if defined(AIRTAK_PLATFORM_NRF52)
    // XOR DEVICEID0 with both halves of the 64-bit BLE MAC address.
    // DEVICEID[0] alone can collide across devices (only 32 bits).
    // The MAC address is guaranteed unique per IEEE OUI assignment.
    uint32_t mac0 = NRF_FICR_DEVICEADDR0;
    uint32_t mac1 = NRF_FICR_DEVICEADDR1;
    return NRF_FICR_DEVICEID0 ^ mac0 ^ (mac1 << 16);
#elif defined(AIRTAK_PLATFORM_ESP32)
    uint8_t mb[6];
    esp_efuse_mac_get_default(mb);
    return (uint32_t)mb[0] | ((uint32_t)mb[1] << 8) |
           ((uint32_t)mb[2] << 16) | ((uint32_t)mb[3] << 24);
#elif defined(AIRTAK_PLATFORM_NATIVE)
    return 0x4D415348UL;
#else
#error "Add nodeId() support for this MCU family."
#endif
}

void macAddress(uint8_t out[6]) {
    if (!out) return;
#if defined(AIRTAK_PLATFORM_NRF52)
    uint32_t addr0 = NRF_FICR_DEVICEADDR0;
    uint32_t addr1 = NRF_FICR_DEVICEADDR1;
    memcpy(out + 0, &addr0, 4);
    memcpy(out + 4, &addr1, 2);
#elif defined(AIRTAK_PLATFORM_ESP32)
    esp_efuse_mac_get_default(out);
#elif defined(AIRTAK_PLATFORM_NATIVE)
    const uint8_t native_mac[6] = { 0x4D, 0x41, 0x53, 0x48, 0x00, 0x01 };
    memcpy(out, native_mac, sizeof(native_mac));
#else
#error "Add macAddress() support for this MCU family."
#endif
}

// ── Meshtastic HardwareModel enum (source: meshtastic/protobufs mesh.proto) ─
// Full enumeration kept here as a single reference so new board additions
// don't require an upstream lookup.  Only AirTAK-supported boards are active;
// the rest are commented out and serve as documentation.
uint8_t meshtasticHwModel() {
    // ── currently supported ──────────────────────────────────────────────
#if defined(AIRTAK_RUNTIME_BOARD)
    const int model = selectedBoard().hwModel;
    return model >= 0 ? static_cast<uint8_t>(model) : 0;
#elif defined(AIRTAK_BOARD_T_DECK)          //  50 T_DECK
    return 50;
#elif defined(AIRTAK_BOARD_T_ECHO_PLUS)      //  33 T_ECHO_PLUS
    return 33;
#elif defined(AIRTAK_BOARD_T_ECHO)           //   7 T_ECHO
    return 7;
#elif defined(AIRTAK_BOARD_HELTEC_T114)      //  43 HELTEC_V3
    return 43;
#elif defined(AIRTAK_BOARD_HELTEC_V3)        //  43 HELTEC_V3
    return 43;
#elif defined(AIRTAK_BOARD_HELTEC_V4)        //  43 HELTEC_V3 (V4 uses the same Meshtastic node type)
    return 43;
#elif defined(AIRTAK_BOARD_T3S3)             //  16 TLORA_T3_S3
    return 16;
#elif defined(AIRTAK_PLATFORM_NATIVE)        //   0 UNSET
    return 0;
    // ── unsupported — uncomment when board support is added ──────────────
//#elif defined(AIRTAK_BOARD_TLORA_V2)       //   1 TLORA_V2
//    return 1;
//#elif defined(AIRTAK_BOARD_TLORA_V1)       //   2 TLORA_V1
//    return 2;
//#elif defined(AIRTAK_BOARD_TLORA_V2_1_1P6) //   3 TLORA_V2_1_1P6
//    return 3;
//#elif defined(AIRTAK_BOARD_TBEAM)           //   4 TBEAM
//    return 4;
//#elif defined(AIRTAK_BOARD_HELTEC_V2_0)     //   5 HELTEC_V2_0
//    return 5;
//#elif defined(AIRTAK_BOARD_TBEAM_V0P7)      //   6 TBEAM_V0P7
//    return 6;
//#elif defined(AIRTAK_BOARD_T_ECHO)          //   7 T_ECHO
//    return 7;
//#elif defined(AIRTAK_BOARD_TLORA_V1_1P3)   //   8 TLORA_V1_1P3
//    return 8;
//#elif defined(AIRTAK_BOARD_RAK4631)         //   9 RAK4631
//    return 9;
//#elif defined(AIRTAK_BOARD_HELTEC_V2_1)     //  10 HELTEC_V2_1
//    return 10;
//#elif defined(AIRTAK_BOARD_HELTEC_V1)       //  11 HELTEC_V1
//    return 11;
//#elif defined(AIRTAK_BOARD_LILYGO_TBEAM_S3_CORE) // 12 LILYGO_TBEAM_S3_CORE
//    return 12;
//#elif defined(AIRTAK_BOARD_RAK11200)        //  13 RAK11200
//    return 13;
//#elif defined(AIRTAK_BOARD_NANO_G1)         //  14 NANO_G1
//    return 14;
//#elif defined(AIRTAK_BOARD_TLORA_V2_1_1P8) //  15 TLORA_V2_1_1P8
//    return 15;
//#elif defined(AIRTAK_BOARD_NANO_G1_EXPLORER)//  17 NANO_G1_EXPLORER
//    return 17;
//#elif defined(AIRTAK_BOARD_NANO_G2_ULTRA)   //  18 NANO_G2_ULTRA
//    return 18;
//#elif defined(AIRTAK_BOARD_LORA_TYPE)       //  19 LORA_TYPE
//    return 19;
//#elif defined(AIRTAK_BOARD_WIPHONE)         //  20 WIPHONE
//    return 20;
//#elif defined(AIRTAK_BOARD_WIO_WM1110)      //  21 WIO_WM1110
//    return 21;
//#elif defined(AIRTAK_BOARD_RAK2560)         //  22 RAK2560
//    return 22;
//#elif defined(AIRTAK_BOARD_HELTEC_HRU_3601) //  23 HELTEC_HRU_3601
//    return 23;
//#elif defined(AIRTAK_BOARD_HELTEC_WIRELESS_BRIDGE) // 24 HELTEC_WIRELESS_BRIDGE
//    return 24;
//#elif defined(AIRTAK_BOARD_STATION_G1)      //  25 STATION_G1
//    return 25;
//#elif defined(AIRTAK_BOARD_RAK11310)         //  26 RAK11310
//    return 26;
//#elif defined(AIRTAK_BOARD_SENSELORA_RP2040)//  27 SENSELORA_RP2040
//    return 27;
//#elif defined(AIRTAK_BOARD_SENSELORA_S3)    //  28 SENSELORA_S3
//    return 28;
//#elif defined(AIRTAK_BOARD_CANARYONE)        //  29 CANARYONE
//    return 29;
//#elif defined(AIRTAK_BOARD_RP2040_LORA)     //  30 RP2040_LORA
//    return 30;
//#elif defined(AIRTAK_BOARD_STATION_G2)       //  31 STATION_G2
//    return 31;
//#elif defined(AIRTAK_BOARD_LORA_RELAY_V1)   //  32 LORA_RELAY_V1
//    return 32;
//#elif defined(AIRTAK_BOARD_T_ECHO_PLUS)      //  33 T_ECHO_PLUS
//    return 33;
//#elif defined(AIRTAK_BOARD_PPR)              //  34 PPR
//    return 34;
//#elif defined(AIRTAK_BOARD_GENIEBLOCKS)      //  35 GENIEBLOCKS
//    return 35;
//#elif defined(AIRTAK_BOARD_NRF52_UNKNOWN)    //  36 NRF52_UNKNOWN
//    return 36;
//#elif defined(AIRTAK_BOARD_PORTDUINO)        //  37 PORTDUINO
//    return 37;
//#elif defined(AIRTAK_BOARD_ANDROID_SIM)      //  38 ANDROID_SIM
//    return 38;
//#elif defined(AIRTAK_BOARD_DIY_V1)            //  39 DIY_V1
//    return 39;
//#elif defined(AIRTAK_BOARD_NRF52840_PCA10059)//  40 NRF52840_PCA10059
//    return 40;
//#elif defined(AIRTAK_BOARD_DR_DEV)            //  41 DR_DEV
//    return 41;
//#elif defined(AIRTAK_BOARD_M5STACK)           //  42 M5STACK
//    return 42;
//#elif defined(AIRTAK_BOARD_HELTEC_WSL_V3)    //  44 HELTEC_WSL_V3
//    return 44;
//#elif defined(AIRTAK_BOARD_BETAFPV_2400_TX)  //  45 BETAFPV_2400_TX
//    return 45;
//#elif defined(AIRTAK_BOARD_BETAFPV_900_NANO_TX)// 46 BETAFPV_900_NANO_TX
//    return 46;
//#elif defined(AIRTAK_BOARD_RPI_PICO)          //  47 RPI_PICO
//    return 47;
//#elif defined(AIRTAK_BOARD_HELTEC_WIRELESS_TRACKER) // 48 HELTEC_WIRELESS_TRACKER
//    return 48;
//#elif defined(AIRTAK_BOARD_HELTEC_WIRELESS_PAPER)   // 49 HELTEC_WIRELESS_PAPER
//    return 49;
//#elif defined(AIRTAK_BOARD_T_WATCH_S3)        //  51 T_WATCH_S3
//    return 51;
//#elif defined(AIRTAK_BOARD_PICOMPUTER_S3)     //  52 PICOMPUTER_S3
//    return 52;
//#elif defined(AIRTAK_BOARD_HELTEC_HT62)       //  53 HELTEC_HT62
//    return 53;
//#elif defined(AIRTAK_BOARD_EBYTE_ESP32_S3)    //  54 EBYTE_ESP32_S3
//    return 54;
//#elif defined(AIRTAK_BOARD_ESP32_S3_PICO)     //  55 ESP32_S3_PICO
//    return 55;
//#elif defined(AIRTAK_BOARD_CHATTER_2)          //  56 CHATTER_2
//    return 56;
//#elif defined(AIRTAK_BOARD_HELTEC_WIRELESS_PAPER_V1_0) // 57 HELTEC_WIRELESS_PAPER_V1_0
//    return 57;
//#elif defined(AIRTAK_BOARD_HELTEC_WIRELESS_TRACKER_V1_0)// 58 HELTEC_WIRELESS_TRACKER_V1_0
//    return 58;
//#elif defined(AIRTAK_BOARD_UNPHONE)            //  59 UNPHONE
//    return 59;
//#elif defined(AIRTAK_BOARD_TD_LORAC)           //  60 TD_LORAC
//    return 60;
//#elif defined(AIRTAK_BOARD_CDEBYTE_EORA_S3)    //  61 CDEBYTE_EORA_S3
//    return 61;
//#elif defined(AIRTAK_BOARD_TWC_MESH_V4)        //  62 TWC_MESH_V4
//    return 62;
//#elif defined(AIRTAK_BOARD_NRF52_PROMICRO_DIY) //  63 NRF52_PROMICRO_DIY
//    return 63;
//#elif defined(AIRTAK_BOARD_RADIOMASTER_900_BANDIT_NANO) // 64 RADIOMASTER_900_BANDIT_NANO
//    return 64;
//#elif defined(AIRTAK_BOARD_HELTEC_CAPSULE_SENSOR_V3)    // 65 HELTEC_CAPSULE_SENSOR_V3
//    return 65;
//#elif defined(AIRTAK_BOARD_HELTEC_VISION_MASTER_T190)   // 66 HELTEC_VISION_MASTER_T190
//    return 66;
//#elif defined(AIRTAK_BOARD_HELTEC_VISION_MASTER_E213)   // 67 HELTEC_VISION_MASTER_E213
//    return 67;
//#elif defined(AIRTAK_BOARD_HELTEC_VISION_MASTER_E290)   // 68 HELTEC_VISION_MASTER_E290
//    return 68;
//#elif defined(AIRTAK_BOARD_HELTEC_MESH_NODE_T114)// 69 HELTEC_MESH_NODE_T114
//    return 69;
//#elif defined(AIRTAK_BOARD_SENSECAP_INDICATOR)  //  70 SENSECAP_INDICATOR
//    return 70;
//#elif defined(AIRTAK_BOARD_TRACKER_T1000_E)     //  71 TRACKER_T1000_E
//    return 71;
//#elif defined(AIRTAK_BOARD_RAK3172)             //  72 RAK3172
//    return 72;
//#elif defined(AIRTAK_BOARD_WIO_E5)              //  73 WIO_E5
//    return 73;
//#elif defined(AIRTAK_BOARD_RADIOMASTER_900_BANDIT)// 74 RADIOMASTER_900_BANDIT
//    return 74;
//#elif defined(AIRTAK_BOARD_ME25LS01_4Y10TD)     //  75 ME25LS01_4Y10TD
//    return 75;
//#elif defined(AIRTAK_BOARD_RP2040_FEATHER_RFM95)//  76 RP2040_FEATHER_RFM95
//    return 76;
//#elif defined(AIRTAK_BOARD_M5STACK_COREBASIC)   //  77 M5STACK_COREBASIC
//    return 77;
//#elif defined(AIRTAK_BOARD_M5STACK_CORE2)       //  78 M5STACK_CORE2
//    return 78;
//#elif defined(AIRTAK_BOARD_RPI_PICO2)           //  79 RPI_PICO2
//    return 79;
//#elif defined(AIRTAK_BOARD_M5STACK_CORES3)      //  80 M5STACK_CORES3
//    return 80;
//#elif defined(AIRTAK_BOARD_SEEED_XIAO_S3)       //  81 SEEED_XIAO_S3
//    return 81;
//#elif defined(AIRTAK_BOARD_MS24SF1)             //  82 MS24SF1
//    return 82;
//#elif defined(AIRTAK_BOARD_TLORA_C6)            //  83 TLORA_C6
//    return 83;
//#elif defined(AIRTAK_BOARD_WISMESH_TAP)         //  84 WISMESH_TAP
//    return 84;
//#elif defined(AIRTAK_BOARD_ROUTASTIC)           //  85 ROUTASTIC
//    return 85;
//#elif defined(AIRTAK_BOARD_MESH_TAB)            //  86 MESH_TAB
//    return 86;
//#elif defined(AIRTAK_BOARD_MESHLINK)            //  87 MESHLINK
//    return 87;
//#elif defined(AIRTAK_BOARD_XIAO_NRF52_KIT)      //  88 XIAO_NRF52_KIT
//    return 88;
//#elif defined(AIRTAK_BOARD_THINKNODE_M1)        //  89 THINKNODE_M1
//    return 89;
//#elif defined(AIRTAK_BOARD_THINKNODE_M2)        //  90 THINKNODE_M2
//    return 90;
//#elif defined(AIRTAK_BOARD_T_ETH_ELITE)         //  91 T_ETH_ELITE
//    return 91;
//#elif defined(AIRTAK_BOARD_HELTEC_SENSOR_HUB)   //  92 HELTEC_SENSOR_HUB
//    return 92;
//#elif defined(AIRTAK_BOARD_MUZI_BASE)           //  93 MUZI_BASE
//    return 93;
//#elif defined(AIRTAK_BOARD_HELTEC_MESH_POCKET)  //  94 HELTEC_MESH_POCKET
//    return 94;
//#elif defined(AIRTAK_BOARD_SEEED_SOLAR_NODE)    //  95 SEEED_SOLAR_NODE
//    return 95;
//#elif defined(AIRTAK_BOARD_NOMADSTAR_METEOR_PRO)//  96 NOMADSTAR_METEOR_PRO
//    return 96;
//#elif defined(AIRTAK_BOARD_CROWPANEL)           //  97 CROWPANEL
//    return 97;
//#elif defined(AIRTAK_BOARD_LINK_32)             //  98 LINK_32
//    return 98;
//#elif defined(AIRTAK_BOARD_SEEED_WIO_TRACKER_L1)//  99 SEEED_WIO_TRACKER_L1
//    return 99;
//#elif defined(AIRTAK_BOARD_SEEED_WIO_TRACKER_L1_EINK) // 100 SEEED_WIO_TRACKER_L1_EINK
//    return 100;
//#elif defined(AIRTAK_BOARD_MUZI_R1_NEO)         // 101 MUZI_R1_NEO
//    return 101;
//#elif defined(AIRTAK_BOARD_T_DECK_PRO)          // 102 T_DECK_PRO
//    return 102;
//#elif defined(AIRTAK_BOARD_T_LORA_PAGER)        // 103 T_LORA_PAGER
//    return 103;
//#elif defined(AIRTAK_BOARD_M5STACK_RESERVED)    // 104 M5STACK_RESERVED
//    return 104;
//#elif defined(AIRTAK_BOARD_WISMESH_TAG)         // 105 WISMESH_TAG
//    return 105;
//#elif defined(AIRTAK_BOARD_RAK3312)             // 106 RAK3312
//    return 106;
//#elif defined(AIRTAK_BOARD_THINKNODE_M5)        // 107 THINKNODE_M5
//    return 107;
//#elif defined(AIRTAK_BOARD_HELTEC_MESH_SOLAR)   // 108 HELTEC_MESH_SOLAR
//    return 108;
//#elif defined(AIRTAK_BOARD_T_ECHO_LITE)         // 109 T_ECHO_LITE
//    return 109;
//#elif defined(AIRTAK_BOARD_HELTEC_V4)           // 110 HELTEC_V4
//    return 110;
//#elif defined(AIRTAK_BOARD_M5STACK_C6L)         // 111 M5STACK_C6L
//    return 111;
//#elif defined(AIRTAK_BOARD_M5STACK_CARDPUTER_ADV)//112 M5STACK_CARDPUTER_ADV
//    return 112;
//#elif defined(AIRTAK_BOARD_HELTEC_WIRELESS_TRACKER_V2) // 113 HELTEC_WIRELESS_TRACKER_V2
//    return 113;
//#elif defined(AIRTAK_BOARD_T_WATCH_ULTRA)        // 114 T_WATCH_ULTRA
//    return 114;
//#elif defined(AIRTAK_BOARD_THINKNODE_M3)          // 115 THINKNODE_M3
//    return 115;
//#elif defined(AIRTAK_BOARD_WISMESH_TAP_V2)        // 116 WISMESH_TAP_V2
//    return 116;
//#elif defined(AIRTAK_BOARD_RAK3401)               // 117 RAK3401
//    return 117;
//#elif defined(AIRTAK_BOARD_RAK6421)               // 118 RAK6421
//    return 118;
//#elif defined(AIRTAK_BOARD_THINKNODE_M4)           // 119 THINKNODE_M4
//    return 119;
//#elif defined(AIRTAK_BOARD_THINKNODE_M6)           // 120 THINKNODE_M6
//    return 120;
//#elif defined(AIRTAK_BOARD_MESHSTICK_1262)         // 121 MESHSTICK_1262
//    return 121;
//#elif defined(AIRTAK_BOARD_TBEAM_1_WATT)           // 122 TBEAM_1_WATT
//    return 122;
//#elif defined(AIRTAK_BOARD_T5_S3_EPAPER_PRO)       // 123 T5_S3_EPAPER_PRO
//    return 123;
//#elif defined(AIRTAK_BOARD_TBEAM_BPF)              // 124 TBEAM_BPF
//    return 124;
//#elif defined(AIRTAK_BOARD_MINI_EPAPER_S3)         // 125 MINI_EPAPER_S3
//    return 125;
//#elif defined(AIRTAK_BOARD_TDISPLAY_S3_PRO)        // 126 TDISPLAY_S3_PRO
//    return 126;
//#elif defined(AIRTAK_BOARD_HELTEC_MESH_NODE_T096)  // 127 HELTEC_MESH_NODE_T096
//    return 127;
//#elif defined(AIRTAK_BOARD_TRACKER_T1000_E_PRO)    // 128 TRACKER_T1000_E_PRO
//    return 128;
//#elif defined(AIRTAK_BOARD_THINKNODE_M7)            // 129 THINKNODE_M7
//    return 129;
//#elif defined(AIRTAK_BOARD_THINKNODE_M8)            // 130 THINKNODE_M8
//    return 130;
//#elif defined(AIRTAK_BOARD_THINKNODE_M9)            // 131 THINKNODE_M9
//    return 131;
//#elif defined(AIRTAK_BOARD_HELTEC_V4_R8)            // 132 HELTEC_V4_R8
//    return 132;
//#elif defined(AIRTAK_BOARD_HELTEC_MESH_NODE_T1)     // 133 HELTEC_MESH_NODE_T1
//    return 133;
//#elif defined(AIRTAK_BOARD_STATION_G3)              // 134 STATION_G3
//    return 134;
//#elif defined(AIRTAK_BOARD_T_IMPULSE_PLUS)          // 135 T_IMPULSE_PLUS
//    return 135;
//#elif defined(AIRTAK_BOARD_T_ECHO_CARD)             // 136 T_ECHO_CARD
//    return 136;
    // ── catch-all ────────────────────────────────────────────────────────
#else
#error "Add meshtasticHwModel() support for this board.  See the commented-out list above for the correct model number."
#endif
}

} // namespace platform
} // namespace airtak
