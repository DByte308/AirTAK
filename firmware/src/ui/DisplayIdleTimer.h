#pragma once

#include <cstdint>

namespace airtak::ui {

// Panel-only inactivity policy. A changed setting restarts the interval and wakes
// an idle panel, including when the user changes the timeout to zero (never off).
class DisplayIdleTimer {
public:
    enum class Action { None, Sleep, Wake };

    DisplayIdleTimer(uint32_t now, uint32_t seconds) : lastInteraction_(now), seconds_(seconds) {}

    Action update(uint32_t now, bool interaction, uint32_t seconds) {
        if (interaction || seconds != seconds_) {
            lastInteraction_ = now;
            seconds_ = seconds;
            if (asleep_) {
                asleep_ = false;
                return Action::Wake;
            }
        }
        // Use 64-bit arithmetic: a large seconds value must not wrap into a short timeout.
        if (!asleep_ && seconds_ > 0 &&
            static_cast<uint64_t>(static_cast<uint32_t>(now - lastInteraction_)) >=
                static_cast<uint64_t>(seconds_) * 1000ULL) {
            asleep_ = true;
            return Action::Sleep;
        }
        return Action::None;
    }

    bool asleep() const { return asleep_; }

private:
    uint32_t lastInteraction_;
    uint32_t seconds_;
    bool asleep_ = false;
};

} // namespace airtak::ui
