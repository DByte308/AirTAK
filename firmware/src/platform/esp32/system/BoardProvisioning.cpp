#include "platform/BoardConfig.h"
#if defined(AIRTAK_PLATFORM_ESP32)
#include "platform/esp32/system/BoardProvisioning.h"
#include "platform/BoardSelection.h"
#include <Arduino.h>
#include <nvs.h>
#include <nvs_flash.h>
#include <cstring>

namespace airtak::platform {
namespace {
constexpr uint32_t kBootGraceMs = 2000;
constexpr size_t kCommandCapacity = 64;
constexpr uint32_t kRebootSettleMs = 50;
constexpr uint32_t kConsolePollMs = 10;
class NvsBoardStore final : public BoardIdentityStore {
public:
    bool open() {
        if (opened_) nvs_close(handle_);
        opened_ = false;
        // initArduino normally initialized NVS already. Never erase it on an
        // error here: it may contain the user's BLE bonds and other settings.
        const esp_err_t result = nvs_flash_init();
        if (result != ESP_OK) return false;
        opened_ = nvs_open("airtak_board", NVS_READWRITE, &handle_) == ESP_OK;
        return opened_;
    }
    BoardStoreRead read(uint8_t* out, size_t capacity, size_t& size) override {
        size = 0;
        if (!opened_) return BoardStoreRead::Error;
        esp_err_t result = nvs_get_blob(handle_, "identity", nullptr, &size);
        if (result == ESP_ERR_NVS_NOT_FOUND) return BoardStoreRead::Missing;
        if (result != ESP_OK) return BoardStoreRead::Error;
        if (size > capacity) return BoardStoreRead::Ok; // codec rejects wrong length
        result = nvs_get_blob(handle_, "identity", out, &size);
        return result == ESP_OK ? BoardStoreRead::Ok : BoardStoreRead::Error;
    }
    bool write(const uint8_t* data, size_t size) override {
        return opened_ && nvs_set_blob(handle_, "identity", data, size) == ESP_OK &&
               nvs_commit(handle_) == ESP_OK;
    }
private:
    nvs_handle_t handle_ = 0;
    bool opened_ = false;
};
BoardSelection* selection = nullptr;
const char* stateName(BoardSelectionState state) {
    switch (state) {
        case BoardSelectionState::Missing: return "missing";
        case BoardSelectionState::Invalid: return "invalid";
        case BoardSelectionState::StorageError: return "storage-error";
        case BoardSelectionState::IncompatibleImage: return "incompatible-image";
        case BoardSelectionState::Ready: return "ready";
        case BoardSelectionState::Sealed: return "sealed";
    }
    return "unknown";
}
void printState(const BoardSelection& state, const char* imageId) {
    Serial.printf("[BOARD] state=%s image=%s profile=%s\n", stateName(state.state()),
        imageId ? imageId : "runtime", state.current().boardId[0] ? state.current().boardId : "unknown");
}
} // namespace

const BoardDescriptor& selectedBoard() {
    return selection ? selection->hardware() : unknownBoard();
}

void prepareBoardSelection(const char* imageBoardId) {
    static NvsBoardStore store;
    static BoardSelection state(store, imageBoardId);
    selection = &state;
    if (state.state() == BoardSelectionState::Sealed) return;
    store.open();
    bool autoBoot = state.load();
    printState(state, imageBoardId);
    Serial.println("[BOARD] commands: BOARD GET | BOARD LIST | BOARD SET <id> | BOARD BOOT");
    Serial.println("[BOARD] select the physical board explicitly; chip identity is not a board id");
    const uint32_t started = millis();
    char line[kCommandCapacity] = {};
    size_t used = 0;
    bool discard = false;
    for (;;) {
        while (Serial.available()) {
            const int ch = Serial.read();
            autoBoot = false; // interacting with the console requires explicit BOOT/SET
            if (ch == '\r') continue;
            if (ch != '\n') {
                if (ch < 32 || ch > 126 || used + 1 >= sizeof(line)) discard = true;
                else if (!discard) line[used++] = static_cast<char>(ch);
                continue;
            }
            line[used] = '\0';
            if (discard) {
                Serial.println("[BOARD] error=invalid-command");
            } else if (std::strcmp(line, "BOARD GET") == 0) {
                printState(state, imageBoardId);
            } else if (std::strcmp(line, "BOARD LIST") == 0) {
                for (int i = 0; i < boardCount(); ++i) {
                    const auto& profile = boardAt(i);
                    if (!imageBoardId || std::strcmp(profile.boardId, imageBoardId) == 0)
                        Serial.printf("[BOARD] available=%s name=%s\n", profile.boardId, profile.boardName);
                }
            } else if (std::strncmp(line, "BOARD SET ", 10) == 0) {
                if (state.provision(line + 10)) {
                    Serial.printf("[BOARD] stored=%s rebooting\n", state.current().boardId);
                    Serial.flush();
                    delay(kRebootSettleMs);
                    ESP.restart();
                } else {
                    Serial.println("[BOARD] error=provision-rejected (id, image constraint, or storage)");
                    printState(state, imageBoardId);
                }
            } else if (std::strcmp(line, "BOARD BOOT") == 0) {
                if (state.seal()) { printState(state, imageBoardId); return; }
                Serial.println("[BOARD] error=no-ready-profile");
            } else {
                Serial.println("[BOARD] error=unknown-command");
            }
            used = 0;
            discard = false;
        }
        if (autoBoot && uint32_t(millis() - started) >= kBootGraceMs && state.seal()) {
            printState(state, imageBoardId);
            return;
        }
        delay(kConsolePollMs); // no board GPIO, display or radio work in this loop
    }
}
} // namespace airtak::platform
#endif
