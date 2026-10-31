#include "platform/BoardSelection.h"
#include <cstring>

namespace airtak {
BoardSelection::BoardSelection(BoardIdentityStore& store, const char* imageBoardId)
    : store_(store), imageBoard_(imageBoardId ? &board(imageBoardId) : nullptr) {}

bool BoardSelection::compatible(const BoardDescriptor& candidate) const {
    return &candidate != &unknownBoard() && (!imageBoard_ || imageBoard_ == &candidate);
}
const BoardDescriptor& BoardSelection::current() const {
    return current_ ? *current_ : unknownBoard();
}
const BoardDescriptor& BoardSelection::hardware() const {
    return state_ == BoardSelectionState::Sealed ? current() : unknownBoard();
}
bool BoardSelection::load() {
    if (state_ == BoardSelectionState::Sealed) return false;
    current_ = nullptr;
    uint8_t data[BOARD_IDENTITY_RECORD_SIZE] = {};
    size_t actual = 0;
    const auto result = store_.read(data, sizeof(data), actual);
    if (result == BoardStoreRead::Missing) { state_ = BoardSelectionState::Missing; return false; }
    if (result != BoardStoreRead::Ok) { state_ = BoardSelectionState::StorageError; return false; }
    const auto& candidate = decodeBoardIdentity(data, actual);
    if (&candidate == &unknownBoard()) { state_ = BoardSelectionState::Invalid; return false; }
    if (!compatible(candidate)) { state_ = BoardSelectionState::IncompatibleImage; return false; }
    current_ = &candidate;
    state_ = BoardSelectionState::Ready;
    return true;
}
bool BoardSelection::provision(const char* boardId) {
    if (state_ == BoardSelectionState::Sealed) return false;
    const auto& candidate = board(boardId);
    if (!compatible(candidate)) return false;
    uint8_t data[BOARD_IDENTITY_RECORD_SIZE];
    if (!encodeBoardIdentity(boardId, data, sizeof(data))) return false;
    // Once persistence starts, any failure leaves the application pin-safe.
    current_ = nullptr;
    state_ = BoardSelectionState::StorageError;
    if (!store_.write(data, sizeof(data))) return false;
    uint8_t check[BOARD_IDENTITY_RECORD_SIZE] = {};
    size_t actual = 0;
    if (store_.read(check, sizeof(check), actual) != BoardStoreRead::Ok) return false;
    if (actual != sizeof(data) || std::memcmp(data, check, sizeof(data)) != 0) {
        state_ = BoardSelectionState::Invalid;
        return false;
    }
    current_ = &candidate;
    state_ = BoardSelectionState::Ready;
    return true;
}
bool BoardSelection::seal() {
    if (state_ != BoardSelectionState::Ready || !current_) return false;
    state_ = BoardSelectionState::Sealed;
    return true;
}
bool BoardSelection::clear() {
    // A sealed selection is immutable: never allow clearing after boot.
    if (state_ == BoardSelectionState::Sealed) return false;
    if (!store_.clear()) return false;   // fail-closed: leave state unchanged on error
    current_ = nullptr;
    state_ = BoardSelectionState::Missing;
    return true;
}
} // namespace airtak
