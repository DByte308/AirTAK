#pragma once

namespace airtak::ui {

// A wake press may span many polls: held wakes the panel, release emits SHORT_PRESS.
// Consume that entire gesture so it cannot also navigate, toggle HIT or shut down.
class DisplayWakeInputGate {
public:
    struct Decision { bool dispatchButton; bool dispatchTouch; };

    Decision update(bool wasAsleep, bool buttonHeld, bool hasButtonGesture) {
        if (wasAsleep && (buttonHeld || hasButtonGesture)) consumeButton_ = true;
        const Decision result{!consumeButton_, !wasAsleep};
        // Return the suppressed decision for the release itself, then allow the next press.
        if (consumeButton_ && !buttonHeld) consumeButton_ = false;
        return result;
    }

private:
    bool consumeButton_ = false;
};

} // namespace airtak::ui
