// AirTAK — Airsoft Team Awareness Kit
// firmware/src/ui/MessageScreen.cpp — Message screen state hook
// Planning: §9.6 | Roadmap: 1.4
//
// Active GxEPD2 rendering is centralized in hal/Display.cpp for Phase 1.

#include "ui/Screens.h"
#include <Arduino.h>

namespace airtak {
namespace ui {

// Message ring buffer — stores last 8 messages, shows newest 3.
struct MessageEntry {
    char from_callsign[MSG_CALLSIGN_DISPLAY_CHARS + 1];
    char text[MSG_TEXT_DISPLAY_CHARS + 1];
    uint32_t timestamp_ms;
    bool valid;
};

static MessageEntry s_messages[MSG_RING_BUFFER_SIZE];
static uint8_t      s_msg_head  = 0; // index of newest message
static uint8_t      s_msg_count = 0;

// Phase 1.4: Add a received text message to the ring buffer.
void onMessageReceived(const char* from_callsign, const char* text) {
    s_msg_head = (s_msg_head + 1) % MSG_RING_BUFFER_SIZE;
    auto& e = s_messages[s_msg_head];
    strncpy(e.from_callsign, from_callsign, MSG_CALLSIGN_DISPLAY_CHARS); e.from_callsign[MSG_CALLSIGN_DISPLAY_CHARS] = '\0';
    strncpy(e.text,          text,          MSG_TEXT_DISPLAY_CHARS);       e.text[MSG_TEXT_DISPLAY_CHARS]         = '\0';
    e.timestamp_ms = millis();
    e.valid = true;
    if (s_msg_count < MSG_RING_BUFFER_SIZE) s_msg_count++;
}

void renderMessageScreen() {
    uint8_t shown = 0;
    for (int8_t i = s_msg_head; shown < MSG_DISPLAY_COUNT && i >= 0; i--) {
        if (!s_messages[i].valid) break;
        shown++;
        if (i == 0) i = MSG_RING_BUFFER_SIZE; // wrap
    }
}

} // namespace ui
} // namespace airtak
