// AirTAK Android — config/constants/OtaConstants.kt
// Planning: §3.2, §3.3, §4.3, §6.1
//
// Centralized firmware update constants: ESP32 ROM loader protocol,
// Nordic Secure DFU, USB CDC, and progress reporting defaults.

package com.airtak.config.constants

// ── Progress reporter defaults ──────────────────────────
/** Status report every N chunks during OTA streaming. */
const val OTA_PROGRESS_EVERY_N_CHUNKS = 50
/** Minimum interval between UI progress updates. */
const val OTA_PROGRESS_MIN_REPORT_MS = 500L

// ── ESP32 ROM loader SLIP opcodes (§3.3) ────────────────
const val ESP_SYNC = 0x08
const val ESP_FLASH_BEGIN = 0x02
const val ESP_FLASH_DATA = 0x03
const val ESP_FLASH_END = 0x04
const val ESP_MEM_BEGIN = 0x05
const val ESP_MEM_END = 0x06
const val ESP_MEM_DATA = 0x07
const val ESP_SPI_ATTACH = 0x0D
const val ESP_READ_REG = 0x0A

// ── ESP32 flash layout (default_16MB.csv) ───────────────
/** Block size matching the ROM loader's SPI write buffer (1 KiB). */
const val ESP_BLOCK_SIZE = 0x400
/** ESP32 minimum erase unit (4 KiB). */
const val ESP_FLASH_SECTOR_SIZE = 0x1000
/** app0 offset in default partition table. */
const val ESP_FLASH_OFFSET = 0x10000
/** otadata partition offset. */
const val ESP_OTA_DATA_OFFSET = 0xE000
/** otadata partition size. */
const val ESP_OTA_DATA_SIZE = 0x2000
/** app1 offset in default partition table. */
const val ESP_APP1_OFFSET = 0x650000
/** Max firmware image size for OTA (2 MiB). */
const val ESP_OTA_MAX_SIZE = 2_097_152

/** First byte of an ESP32 OTA app image (esp_image_header_t.magic). */
const val ESP_APP_MAGIC = 0xE9.toByte()

// ── ESP32 serial timing ─────────────────────────────────
const val ESP_DEFAULT_BAUD = 115200
const val ESP_BULK_TIMEOUT_MS = 3_000
const val ESP_FLASH_DATA_TIMEOUT_MS = 10_000
const val ESP_SYNC_TIMEOUT_MS = 100
const val ESP_SYNC_RETRIES = 7
/** Sleep between flash end and reboot. */
const val ESP_FLASH_END_DELAY_MS = 2_000L
/** Inter-block pacing delay. */
const val ESP_BLOCK_PACING_MS = 5L
/** Drain buffer read timeout. */
const val ESP_DRAIN_TIMEOUT_MS = 200L
/** Interface claim drain timeout. */
const val ESP_IFCLAIM_DRAIN_MS = 50L
/** Default bulk transfer timeout for short reads. */
const val ESP_SHORT_READ_TIMEOUT_MS = 30

// ── DTR/RTS reset pulse timing (esptool sequence) ──────
const val ESP_RESET_PULSE_MS = 100L
const val ESP_RESET_RELEASE_MS = 50L
const val ESP_GPIO0_RELEASE_MS = 50L

// ── RAM stub block size ─────────────────────────────────
const val ESP_RAM_BLOCK_SIZE = 0x1800

// ── ESP32 BLE OTA engine ────────────────────────────────
const val ESP_BLE_OTA_STATUS_EVERY_N = 50
const val ESP_BLE_OTA_FINISH_TIMEOUT_MS = 30_000L
const val ESP_BLE_OTA_MAX_QUEUE_DEPTH = 8
const val ESP_BLE_OTA_STATE_RECEIVING = 1
const val ESP_BLE_OTA_STATE_COMPLETE = 4
const val ESP_BLE_OTA_STATE_ERROR = 5

// ── nRF52 Secure DFU opcodes (§3.2) ─────────────────────
const val NRF_OP_CREATE = 0x01
const val NRF_OP_SET_PRN = 0x02
const val NRF_OP_CALC_CRC = 0x03
const val NRF_OP_EXECUTE = 0x04
const val NRF_OP_SELECT = 0x06
const val NRF_OP_MTU_GET = 0x07
const val NRF_OP_PING = 0x09
const val NRF_OP_RESPONSE = 0x60

const val NRF_OBJ_TYPE_COMMAND = 0x01
const val NRF_OBJ_TYPE_DATA = 0x02
const val NRF_RESULT_SUCCESS = 0x01

// ── nRF52 DFU timing ────────────────────────────────────
const val NRF_SERIAL_DEFAULT_BAUD = 115_200
const val NRF_SERIAL_BULK_TIMEOUT_MS = 5_000

/** Time the Adafruit nRF52 bootloader needs from reboot to advertise. */
const val NRF_BOOTLOADER_WAKE_MS = 2_500L
/** Cap on the library-managed BLE DFU run (5 min). */
const val NRF_BLE_DFU_TIMEOUT_MS = 5L * 60L * 1_000L

// ── SLIP framing (RFC 1055) ──────────────────────────────
const val SLIP_END = 0xC0
const val SLIP_ESC = 0xDB
const val SLIP_ESC_END = 0xDC
const val SLIP_ESC_ESC = 0xDD

// ── USB CDC class request codes ─────────────────────────
const val CDC_SET_LINE_CODING = 0x20
const val CDC_SET_CONTROL_LINE_STATE = 0x22
const val USB_REQ_TYPE_CLASS_OUT = 0x21
const val USB_REQ_TYPE_VENDOR_OUT = 0x40
const val USB_REQ_TYPE_VENDOR_IN = 0xC0

// ── CP210x vendor-specific requests (Silicon Labs AN571) ─
const val CP210X_IFC_ENABLE = 0x00
const val CP210X_SET_BAUDRATE = 0x1E
const val CP210X_SET_MHS = 0x07

// ── CH340 vendor-specific requests ──────────────────────
const val CH340_REQ_WRITE_REG = 0x9A
const val CH340_REQ_SERIAL_INIT = 0xA1
const val CH340_REQ_MODEM_CTRL = 0xA4

// ── USB Vendor IDs ──────────────────────────────────────
const val VID_NRF52840 = 0x239A
const val VID_ESP32_NATIVE = 0x303A
const val VID_SILABS_CP210X = 0x10C4
const val VID_QINHENG_CH340 = 0x1A86

// ── Firmware update defaults ────────────────────────────
/** Default wait-for-reconnect timeout. */
const val OTA_RECONNECT_TIMEOUT_MS = 30_000L

// ── ESP32 ROM protocol magic bytes ──────────────────────
const val ESP_ROM_REPLY_OK = 0x01.toByte()
const val ESP_ROM_REPLY_STATUS_OK_BYTE2 = 0x00.toByte()
const val ESP_ROM_REPLY_STATUS_OK_BYTE4 = 0x00.toByte()

// ── ESP32 SYNC frame constants ──────────────────────────
const val ESP_SYNC_FRAME_DATA_LEN = 36
const val ESP_SYNC_FRAME_BYTE = 0x55.toByte()
val ESP_SYNC_FRAME_PREFIX = byteArrayOf(0x07.toByte(), 0x07.toByte(), 0x12.toByte(), 0x20.toByte())
const val ESP_XOR_CHECKSUM_SEED = 0xEF

// ── ESP32 flash padding ─────────────────────────────────
const val ESP_FLASH_PADDING_BYTE = 0xFF.toByte()

// ── ESP32 reboot modes ──────────────────────────────────
/** Reboot into app after flash end. */
const val ESP_REBOOT_APP = 0

// ── ESP32 SPI_ATTACH payload size ───────────────────────
const val ESP_SPI_ATTACH_PAYLOAD_SIZE = 8

// ── ESP32 OHAI wait ─────────────────────────────────────
const val ESP_OHAI_WAIT_MS = 10_000

// ── ESP32 progress logging interval ─────────────────────
const val ESP_PROGRESS_LOG_EVERY_N = 128

// ── USB CDC line coding defaults ────────────────────────
const val USB_CDC_STOP_BITS_1 = 0x00
const val USB_CDC_PARITY_NONE = 0x00
const val USB_CDC_DATA_BITS_8 = 0x08
const val USB_CDC_DTR_RTS_ASSERTED = 0x03

// ── CP210x interface enable ─────────────────────────────
const val CP210X_ENABLE_VALUE = 0x0001

// ── CP210x MHS bits ─────────────────────────────────────
const val CP210X_MHS_DTR = 0x01
const val CP210X_MHS_RTS = 0x02
const val CP210X_MHS_MASK = 0x03

// ── CH340 register constants ────────────────────────────
val CH340_INIT_RD_VALUES = shortArrayOf(0x27, 0x00)
const val CH340_BAUD_DIV_WRITE_LOW = 0x1312
const val CH340_BAUD_DIV_WRITE_HIGH = 0xCC83
const val CH340_BAUD_DIV_READ_LOW = 0x0F2C
const val CH340_BAUD_DIV_READ_HIGH = 0x0007
const val CH340_LINE_CTRL_LOW = 0x2518
const val CH340_LINE_CTRL_HIGH = 0x00C3
const val CH340_MODEM_DEFAULT = 0xFF
const val CH340_MODEM_DTR_NEG = 0x20
const val CH340_MODEM_RTS_NEG = 0x40

// ── USB request codes ───────────────────────────────────
const val USB_BMREQ_SET_FEATURE = 0x02
const val USB_REQ_CLEAR_FEATURE = 0x01
const val USB_ENDPOINT_HALT = 0x00

// ── ESP32 USB IN endpoint halt recovery ─────────────────
const val ESP_IN_EP_HALT_THRESHOLD_MS = 3_000

// ── ESP32 ROM reply magic ───────────────────────────────
const val ESP_ROM_RETRY_REPLY_MAGIC = 0x01.toByte()

// ── ESP32 large erase timeout ───────────────────────────
const val ESP_FLASH_LARGE_ERASE_TIMEOUT_MS = 30_000

// ── NRF PING ID ─────────────────────────────────────────
const val NRF_PING_ID = 0x01.toByte()

// ── NRF PRN value (packet receipt notification = 0) ─────
const val NRF_PRN_VALUE = 0

// ── NRF protocol sizes ──────────────────────────────────
const val NRF_SELECT_RESP_MIN_SIZE = 12
const val NRF_CRC_RESP_MIN_SIZE = 8
const val NRF_RESPONSE_HEADER_BYTES = 3

// ── NRF SLIP chunk size ─────────────────────────────────
const val NRF_SLIP_CHUNK_SIZE = 64

// ── NRF SLIP overhead estimate ──────────────────────────
const val NRF_SLIP_MAX_OVERHEAD = 4

// ── NRF serial read timeout ─────────────────────────────
const val NRF_SERIAL_SHORT_READ_TIMEOUT_MS = 100

// ── USB CDC class codes ─────────────────────────────────
const val USB_CDC_CLASS_CONTROL = 0x02
const val USB_CDC_CLASS_DATA = 0x0A

// ── CDC control line bits ───────────────────────────────
const val CDC_DTR_BIT = 0x01
const val CDC_RTS_BIT = 0x02

// ── ESP BLE OTA engine supplementary ────────────────────
/** Timeout waiting for OTA start acknowledgement. */
const val ESP_BLE_OTA_START_ACK_TIMEOUT_MS = 5_000L
/** Backpressure poll delay in the OTA streaming loop. */
const val ESP_BLE_OTA_BACKPRESSURE_MS = 20L
/** TX queue drain timeout at OTA finish. */
const val ESP_BLE_OTA_TX_DRAIN_TIMEOUT_MS = 60_000L
/** Final status check timeout after transfer. */
const val ESP_BLE_OTA_FINAL_STATUS_TIMEOUT_MS = 5_000L
/** Inner loop poll interval during status wait. */
const val ESP_BLE_OTA_STATUS_POLL_MS = 1_000L

// ── Logger buffer size for OTA engine ───────────────────
const val ESP_BLE_OTA_LOG_BUF_BYTES = 64

// ── ESP32 flash block timeout defaults ──────────────────
const val ESP_MEM_BEGIN_TIMEOUT_MS = 10_000
const val ESP_FLASH_BEGIN_TIMEOUT_MS = 10_000
const val ESP_FLASH_BEGIN_LARGE_TIMEOUT_MS = 30_000

// ── Firmware file MIME types ─────────────────────────────
/** Primary MIME type for firmware binary files. */
const val FW_MIME_OCTET_STREAM = "application/octet-stream"
/** Alternative MIME for binary firmware. */
const val FW_MIME_X_BINARY = "application/x-binary"
/** MIME type for compressed/nRF DFU zip firmware packages. */
const val FW_MIME_ZIP = "application/zip"
/** Wildcard MIME to catch any system-registered binary file type. */
const val FW_MIME_WILDCARD = "*/*"
