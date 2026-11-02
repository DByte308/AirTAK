#pragma once

#include <cstdint>

namespace airtak::ui {

// SSD1306 power/contrast control shared by the V3, V4/runtime OLED and T3S3.
// Hardware access is supplied by the driver so transitions can be tested natively.
class OledPanelPower {
public:
    void begin(bool ready) {
        ready_ = ready;
        asleep_ = false;
        prompt_ = false;
        brightness_ = 100;
    }

    template <typename Send>
    void setBrightness(uint8_t percent, Send send) {
        brightness_ = percent > 100 ? 100 : percent;
        if (!ready_ || prompt_) return;
        contrast(send);
        if (!asleep_) send(brightness_ == 0 ? DisplayOff : DisplayOn);
    }

    template <typename Send>
    void sleep(Send send) {
        if (!ready_ || asleep_) return;
        asleep_ = true;
        prompt_ = false;
        send(DisplayOff);
    }

    template <typename Send>
    void wake(Send send) {
        if (!ready_) return;
        asleep_ = false;
        contrast(send);
        send(!prompt_ && brightness_ == 0 ? DisplayOff : DisplayOn);
    }

    // A pairing passkey must be visible even if the normal brightness is zero.
    // The next normal frame restores the user's setting without changing config.
    template <typename Send>
    void showPrompt(Send send) {
        if (!ready_) return;
        prompt_ = true;
        asleep_ = false;
        contrast(send);
        send(DisplayOn);
    }

    template <typename Send>
    void endPrompt(Send send) {
        if (!ready_ || !prompt_) return;
        prompt_ = false;
        contrast(send);
        if (!asleep_) send(brightness_ == 0 ? DisplayOff : DisplayOn);
    }

    bool ready() const { return ready_; }
    bool canRender() const { return ready_ && !asleep_ && (brightness_ > 0 || prompt_); }

private:
    static constexpr uint8_t SetContrast = 0x81;
    static constexpr uint8_t DisplayOff = 0xAE;
    static constexpr uint8_t DisplayOn = 0xAF;
    bool ready_ = false;
    bool asleep_ = false;
    bool prompt_ = false;
    uint8_t brightness_ = 100;

    template <typename Send>
    void contrast(Send send) const {
        send(SetContrast);
        const uint32_t percent = prompt_ ? 100 : brightness_;
        // More adjustment room near minimum OLED current; 100% stays full contrast.
        // Positive percentages may use contrast 0 (minimum current); only 0% powers off.
        send(static_cast<uint8_t>((percent * percent * 255U + 5000U) / 10000U));
    }
};

} // namespace airtak::ui
