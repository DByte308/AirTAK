// MASH — Mesh Airsoft Situational Hub
// firmware/src/ui/MessageScreen.cpp — Message screen renderer
// Planning: §9.6 | Roadmap: 1.4
//
// Displays the last 3 received TEXT_MESSAGE_APP messages.
// Each entry: "FROM_NODE: message text" truncated to fit 200 px width.
// Shows "[N more]" footer when more than 3 messages have been received.

#include "ui/Screens.h"
#include <Arduino.h>

namespace mash {
namespace ui {

// Message ring buffer — stores last 8 messages, shows newest 3.
struct MessageEntry {
    char from_callsign[9]; // 8 chars + null
    char text[48];         // truncated to 47 chars + null for display
    uint32_t timestamp_ms;
    bool valid;
};

static MessageEntry s_messages[8];
static uint8_t      s_msg_head  = 0; // index of newest message
static uint8_t      s_msg_count = 0;

// Phase 1.4: Add a received text message to the ring buffer.
void onMessageReceived(const char* from_callsign, const char* text) {
    s_msg_head = (s_msg_head + 1) % 8;
    auto& e = s_messages[s_msg_head];
    strncpy(e.from_callsign, from_callsign, 8); e.from_callsign[8] = '\0';
    strncpy(e.text,          text,          47); e.text[47]         = '\0';
    e.timestamp_ms = millis();
    e.valid = true;
    if (s_msg_count < 8) s_msg_count++;
}

void renderMessageScreen() {
    // Header
    // TODO Phase 1.4: drawText("MESSAGES", header row);

    // Render up to 3 most recent messages
    uint8_t shown = 0;
    for (int8_t i = s_msg_head; shown < 3 && i >= 0; i--) {
        if (!s_messages[i].valid) break;
        // TODO: printf("%s: %s", s_messages[i].from_callsign, s_messages[i].text);
        shown++;
        if (i == 0) i = 8; // wrap
    }
    if (s_msg_count > 3) {
        // TODO: printf("[+%d more]", s_msg_count - 3);
    }
}

} // namespace ui
} // namespace mash
