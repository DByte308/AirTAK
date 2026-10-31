#pragma once
#include "platform/BoardIdentity.h"

namespace airtak {
enum class BoardStoreRead { Ok, Missing, Error };
class BoardIdentityStore {
public:
    virtual ~BoardIdentityStore() = default;
    virtual BoardStoreRead read(uint8_t* out, size_t capacity, size_t& actualSize) = 0;
    virtual bool write(const uint8_t* data, size_t size) = 0;
};
enum class BoardSelectionState { Missing, Invalid, StorageError, IncompatibleImage, Ready, Sealed };

// Startup-only state machine. No GPIO, Arduino or automatic storage erasure.
// A legacy-HAL test image must constrain selection to its compiled board id;
// only a fully runtime-converted image may omit that constraint.
class BoardSelection {
public:
    explicit BoardSelection(BoardIdentityStore& store, const char* imageBoardId = nullptr);
    bool load();
    bool provision(const char* boardId); // persist + exact readback before accepting
    bool seal();                       // prohibit further load/provision changes
    const BoardDescriptor& current() const; // metadata for provisioning/status
    const BoardDescriptor& hardware() const; // unknown until selection is sealed
    BoardSelectionState state() const { return state_; }
private:
    bool compatible(const BoardDescriptor& candidate) const;
    BoardIdentityStore& store_;
    const BoardDescriptor* imageBoard_;
    const BoardDescriptor* current_ = nullptr;
    BoardSelectionState state_ = BoardSelectionState::Missing;
};
} // namespace airtak
