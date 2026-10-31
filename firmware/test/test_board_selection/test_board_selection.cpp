#include <unity.h>
#include <cstring>
#include <vector>
#include "platform/BoardSelection.h"

using namespace airtak;
void setUp() {}
void tearDown() {}
class Store final : public BoardIdentityStore {
public:
    std::vector<uint8_t> data;
    BoardStoreRead readResult = BoardStoreRead::Missing;
    bool failWrite = false, failReadback = false, corruptReadback = false;
    bool failClear = false;
    unsigned writes = 0;
    unsigned clears = 0;
    BoardStoreRead read(uint8_t* out, size_t capacity, size_t& size) override {
        size = data.size();
        if (writes && failReadback) return BoardStoreRead::Error;
        if (readResult != BoardStoreRead::Ok) return readResult;
        if (size <= capacity) {
            std::memcpy(out, data.data(), size);
            if (writes && corruptReadback && size) out[0] ^= 1;
        }
        return BoardStoreRead::Ok;
    }
    bool write(const uint8_t* bytes, size_t size) override {
        ++writes;
        if (failWrite) return false;
        data.assign(bytes, bytes + size);
        readResult = BoardStoreRead::Ok;
        return true;
    }
    void existing(const char* id) {
        data.resize(BOARD_IDENTITY_RECORD_SIZE);
        TEST_ASSERT_TRUE(encodeBoardIdentity(id, data.data(), data.size()));
        readResult = BoardStoreRead::Ok;
    }
    bool clear() override {
        ++clears;
        if (failClear) return false;
        data.clear();
        readResult = BoardStoreRead::Missing;
        return true;   // idempotent: an already-empty store still clears
    }
};
static void assertPinSafe(BoardSelection& selection) {
    TEST_ASSERT_EQUAL_PTR(&unknownBoard(), &selection.current());
    TEST_ASSERT_FALSE(selection.seal());
}
static void test_missing_record_cannot_start_hardware() {
    Store store;
    BoardSelection selection(store);
    TEST_ASSERT_FALSE(selection.load());
    TEST_ASSERT_EQUAL(BoardSelectionState::Missing, selection.state());
    assertPinSafe(selection);
    TEST_ASSERT_EQUAL_UINT(0, store.writes);
}
static void test_read_error_never_erases_or_guesses() {
    Store store;
    store.readResult = BoardStoreRead::Error;
    BoardSelection selection(store);
    TEST_ASSERT_FALSE(selection.load());
    TEST_ASSERT_EQUAL(BoardSelectionState::StorageError, selection.state());
    assertPinSafe(selection);
    TEST_ASSERT_EQUAL_UINT(0, store.writes);
}
static void test_corrupt_and_oversized_records_are_pin_safe() {
    Store store;
    store.existing("heltec_v4");
    store.data[0] ^= 1;
    BoardSelection selection(store);
    TEST_ASSERT_FALSE(selection.load());
    TEST_ASSERT_EQUAL(BoardSelectionState::Invalid, selection.state());
    assertPinSafe(selection);
    store.existing("heltec_v4");
    store.data.resize(BOARD_IDENTITY_RECORD_SIZE + 1);
    TEST_ASSERT_FALSE(selection.load());
    assertPinSafe(selection);
}
static void test_legacy_image_rejects_another_valid_board() {
    Store store;
    store.existing("t_deck");
    BoardSelection selection(store, "heltec_v4");
    TEST_ASSERT_FALSE(selection.load());
    TEST_ASSERT_EQUAL(BoardSelectionState::IncompatibleImage, selection.state());
    assertPinSafe(selection);
    TEST_ASSERT_FALSE(selection.provision("t_deck"));
    TEST_ASSERT_EQUAL_UINT(0, store.writes);
}
static void test_unconstrained_selection_can_load_each_known_profile() {
    Store store;
    BoardSelection selection(store);
    for (int i = 0; i < boardCount(); ++i) {
        store.existing(boardAt(i).boardId);
        TEST_ASSERT_TRUE(selection.load());
        TEST_ASSERT_EQUAL_PTR(&boardAt(i), &selection.current());
    }
}
static void test_provision_requires_readback_and_survives_new_boot_object() {
    Store store;
    BoardSelection selection(store, "heltec_v4");
    TEST_ASSERT_TRUE(selection.provision("heltec_v4"));
    TEST_ASSERT_EQUAL_UINT(1, store.writes);
    TEST_ASSERT_EQUAL(BoardSelectionState::Ready, selection.state());
    TEST_ASSERT_EQUAL_PTR(&board("heltec_v4"), &selection.current());
    TEST_ASSERT_TRUE(selection.seal());
    BoardSelection reboot(store, "heltec_v4");
    TEST_ASSERT_TRUE(reboot.load());
    TEST_ASSERT_EQUAL_PTR(&selection.current(), &reboot.current());
}
static void test_invalid_commands_do_not_write_flash() {
    Store store;
    BoardSelection selection(store, "heltec_v4");
    TEST_ASSERT_FALSE(selection.provision(nullptr));
    TEST_ASSERT_FALSE(selection.provision("HELTEC_V4"));
    TEST_ASSERT_FALSE(selection.provision("heltec_v4 "));
    TEST_ASSERT_FALSE(selection.provision("unknown"));
    TEST_ASSERT_EQUAL_UINT(0, store.writes);
    assertPinSafe(selection);
}
static void test_failed_write_clears_prior_candidate() {
    Store store;
    store.existing("heltec_v4");
    BoardSelection selection(store);
    TEST_ASSERT_TRUE(selection.load());
    store.failWrite = true;
    TEST_ASSERT_FALSE(selection.provision("t_deck"));
    TEST_ASSERT_EQUAL(BoardSelectionState::StorageError, selection.state());
    assertPinSafe(selection);
}
static void test_failed_readback_cannot_activate_board() {
    Store store;
    store.failReadback = true;
    BoardSelection selection(store);
    TEST_ASSERT_FALSE(selection.provision("heltec_v4"));
    TEST_ASSERT_EQUAL(BoardSelectionState::StorageError, selection.state());
    assertPinSafe(selection);
}
static void test_corrupt_readback_cannot_activate_board() {
    Store store;
    store.corruptReadback = true;
    BoardSelection selection(store);
    TEST_ASSERT_FALSE(selection.provision("heltec_v4"));
    TEST_ASSERT_EQUAL(BoardSelectionState::Invalid, selection.state());
    assertPinSafe(selection);
}
static void test_sealed_selection_is_immutable() {
    Store store;
    BoardSelection selection(store);
    TEST_ASSERT_TRUE(selection.provision("heltec_v4"));
    TEST_ASSERT_TRUE(selection.seal());
    TEST_ASSERT_FALSE(selection.load());
    TEST_ASSERT_FALSE(selection.provision("t_deck"));
    TEST_ASSERT_FALSE(selection.seal());
    TEST_ASSERT_EQUAL(BoardSelectionState::Sealed, selection.state());
    TEST_ASSERT_EQUAL_PTR(&board("heltec_v4"), &selection.current());
    TEST_ASSERT_EQUAL_UINT(1, store.writes);
}
static void test_ready_metadata_never_exposes_hardware_before_seal() {
    Store store;
    store.existing("heltec_v4");
    BoardSelection selection(store);
    TEST_ASSERT_TRUE(selection.load());
    TEST_ASSERT_EQUAL_PTR(&board("heltec_v4"), &selection.current());
    TEST_ASSERT_EQUAL_PTR(&unknownBoard(), &selection.hardware());
    TEST_ASSERT_TRUE(selection.provision("t_deck"));
    TEST_ASSERT_EQUAL_PTR(&unknownBoard(), &selection.hardware());
    TEST_ASSERT_TRUE(selection.seal());
    TEST_ASSERT_EQUAL_PTR(&board("t_deck"), &selection.hardware());
}
static void test_unknown_image_constraint_fails_closed() {
    Store store;
    store.existing("heltec_v4");
    BoardSelection selection(store, "typo");
    TEST_ASSERT_FALSE(selection.load());
    TEST_ASSERT_FALSE(selection.provision("heltec_v4"));
    assertPinSafe(selection);
    TEST_ASSERT_EQUAL_UINT(0, store.writes);
}
static void test_clear_from_ready_becomes_missing() {
    Store store;
    BoardSelection selection(store);
    TEST_ASSERT_TRUE(selection.provision("heltec_v4"));
    TEST_ASSERT_EQUAL(BoardSelectionState::Ready, selection.state());

    TEST_ASSERT_TRUE(selection.clear());
    TEST_ASSERT_EQUAL(BoardSelectionState::Missing, selection.state());
    TEST_ASSERT_EQUAL_PTR(&unknownBoard(), &selection.current());
    TEST_ASSERT_EQUAL_UINT(1, store.clears);
}
static void test_clear_then_load_reports_missing() {
    Store store;
    BoardSelection selection(store);
    TEST_ASSERT_TRUE(selection.provision("heltec_v4"));
    TEST_ASSERT_TRUE(selection.clear());

    // After clearing, the backing store reports Missing -> load() returns false.
    TEST_ASSERT_FALSE(selection.load());
    TEST_ASSERT_EQUAL(BoardSelectionState::Missing, selection.state());
    assertPinSafe(selection);
}
static void test_clear_empty_store_is_idempotent_true() {
    Store store;   // readResult = Missing (empty)
    BoardSelection selection(store);
    TEST_ASSERT_TRUE(selection.clear());
    TEST_ASSERT_EQUAL(BoardSelectionState::Missing, selection.state());
    // Clearing an already-empty store is safe and idempotent.
    TEST_ASSERT_TRUE(selection.clear());
    TEST_ASSERT_EQUAL(BoardSelectionState::Missing, selection.state());
    TEST_ASSERT_EQUAL_UINT(2, store.clears);
}
static void test_clear_store_failure_fail_closed() {
    Store store;
    BoardSelection selection(store);
    TEST_ASSERT_TRUE(selection.provision("heltec_v4"));
    store.failClear = true;

    // A failed store clear leaves the selection unchanged (fail-closed).
    TEST_ASSERT_FALSE(selection.clear());
    TEST_ASSERT_EQUAL(BoardSelectionState::Ready, selection.state());
    TEST_ASSERT_EQUAL_PTR(&board("heltec_v4"), &selection.current());
}
static void test_clear_rejected_when_sealed() {
    Store store;
    BoardSelection selection(store);
    TEST_ASSERT_TRUE(selection.provision("heltec_v4"));
    TEST_ASSERT_TRUE(selection.seal());

    // A sealed selection is immutable: clear() must be rejected and never
    // touch the store.
    TEST_ASSERT_FALSE(selection.clear());
    TEST_ASSERT_EQUAL(BoardSelectionState::Sealed, selection.state());
    TEST_ASSERT_EQUAL_PTR(&board("heltec_v4"), &selection.current());
    TEST_ASSERT_EQUAL_UINT(0, store.clears);
}
int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_missing_record_cannot_start_hardware);
    RUN_TEST(test_read_error_never_erases_or_guesses);
    RUN_TEST(test_corrupt_and_oversized_records_are_pin_safe);
    RUN_TEST(test_legacy_image_rejects_another_valid_board);
    RUN_TEST(test_unconstrained_selection_can_load_each_known_profile);
    RUN_TEST(test_provision_requires_readback_and_survives_new_boot_object);
    RUN_TEST(test_invalid_commands_do_not_write_flash);
    RUN_TEST(test_failed_write_clears_prior_candidate);
    RUN_TEST(test_failed_readback_cannot_activate_board);
    RUN_TEST(test_corrupt_readback_cannot_activate_board);
    RUN_TEST(test_sealed_selection_is_immutable);
    RUN_TEST(test_unknown_image_constraint_fails_closed);
    RUN_TEST(test_ready_metadata_never_exposes_hardware_before_seal);
    RUN_TEST(test_clear_from_ready_becomes_missing);
    RUN_TEST(test_clear_then_load_reports_missing);
    RUN_TEST(test_clear_empty_store_is_idempotent_true);
    RUN_TEST(test_clear_store_failure_fail_closed);
    RUN_TEST(test_clear_rejected_when_sealed);
    return UNITY_END();
}
