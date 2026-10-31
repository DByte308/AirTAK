#include <unity.h>
#include <cstring>
#include <initializer_list>
#include "platform/BoardIdentity.h"

using namespace airtak;
void setUp() {}
void tearDown() {}

static void updateChecksum(uint8_t* record) {
    const uint32_t crc = boardIdentityChecksum(record, 24);
    for (unsigned i = 0; i < 4; ++i) record[24 + i] = (crc >> (8 * i)) & 0xFFu;
}
static void test_crc_reference_vector() {
    const char* vector = "123456789";
    TEST_ASSERT_EQUAL_HEX32(0xCBF43926u, boardIdentityChecksum(
        reinterpret_cast<const uint8_t*>(vector), std::strlen(vector)));
}
static void test_all_known_boards_roundtrip() {
    for (int i = 0; i < boardCount(); ++i) {
        uint8_t record[BOARD_IDENTITY_RECORD_SIZE];
        const auto& profile = boardAt(i);
        TEST_ASSERT_TRUE(encodeBoardIdentity(profile.boardId, record, sizeof(record)));
        TEST_ASSERT_EQUAL_PTR(&profile, &decodeBoardIdentity(record, sizeof(record)));
    }
}
static void test_invalid_encode_does_not_touch_output() {
    uint8_t record[BOARD_IDENTITY_RECORD_SIZE];
    std::memset(record, 0xA5, sizeof(record));
    TEST_ASSERT_FALSE(encodeBoardIdentity("unknown", record, sizeof(record)));
    TEST_ASSERT_FALSE(encodeBoardIdentity(nullptr, record, sizeof(record)));
    TEST_ASSERT_FALSE(encodeBoardIdentity("heltec_v4", record, sizeof(record) - 1));
    TEST_ASSERT_FALSE(encodeBoardIdentity("heltec_v4", nullptr, sizeof(record)));
    for (auto byte : record) TEST_ASSERT_EQUAL_HEX8(0xA5, byte);
}
static void test_missing_truncated_or_extra_record_is_unknown() {
    uint8_t record[BOARD_IDENTITY_RECORD_SIZE + 1] = {};
    TEST_ASSERT_TRUE(encodeBoardIdentity("heltec_v4", record, sizeof(record)));
    TEST_ASSERT_EQUAL_PTR(&unknownBoard(), &decodeBoardIdentity(nullptr, 0));
    for (size_t length = 0; length < BOARD_IDENTITY_RECORD_SIZE; ++length)
        TEST_ASSERT_EQUAL_PTR(&unknownBoard(), &decodeBoardIdentity(record, length));
    TEST_ASSERT_EQUAL_PTR(&unknownBoard(), &decodeBoardIdentity(record, sizeof(record)));
}
static void test_every_single_bit_corruption_is_rejected() {
    uint8_t record[BOARD_IDENTITY_RECORD_SIZE];
    TEST_ASSERT_TRUE(encodeBoardIdentity("heltec_v4", record, sizeof(record)));
    for (size_t byte = 0; byte < sizeof(record); ++byte) {
        for (unsigned bit = 0; bit < 8; ++bit) {
            record[byte] ^= uint8_t(1u << bit);
            TEST_ASSERT_EQUAL_PTR(&unknownBoard(), &decodeBoardIdentity(record, sizeof(record)));
            record[byte] ^= uint8_t(1u << bit);
        }
    }
}
static void test_future_version_wrong_family_and_reserved_byte_are_rejected() {
    uint8_t record[BOARD_IDENTITY_RECORD_SIZE];
    for (int index : {4, 5, 7}) {
        TEST_ASSERT_TRUE(encodeBoardIdentity("heltec_v4", record, sizeof(record)));
        record[index] = 99;
        updateChecksum(record);
        TEST_ASSERT_EQUAL_PTR(&unknownBoard(), &decodeBoardIdentity(record, sizeof(record)));
    }
}
static void test_unknown_id_with_valid_checksum_is_rejected() {
    uint8_t record[BOARD_IDENTITY_RECORD_SIZE];
    TEST_ASSERT_TRUE(encodeBoardIdentity("heltec_v4", record, sizeof(record)));
    record[8] = 'x';
    updateChecksum(record);
    TEST_ASSERT_EQUAL_PTR(&unknownBoard(), &decodeBoardIdentity(record, sizeof(record)));
}
static void test_noncanonical_padding_or_embedded_null_is_rejected() {
    uint8_t record[BOARD_IDENTITY_RECORD_SIZE];
    TEST_ASSERT_TRUE(encodeBoardIdentity("heltec_v4", record, sizeof(record)));
    record[23] = 1;
    updateChecksum(record);
    TEST_ASSERT_EQUAL_PTR(&unknownBoard(), &decodeBoardIdentity(record, sizeof(record)));
    TEST_ASSERT_TRUE(encodeBoardIdentity("heltec_v4", record, sizeof(record)));
    record[10] = 0;
    updateChecksum(record);
    TEST_ASSERT_EQUAL_PTR(&unknownBoard(), &decodeBoardIdentity(record, sizeof(record)));
}
int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_crc_reference_vector);
    RUN_TEST(test_all_known_boards_roundtrip);
    RUN_TEST(test_invalid_encode_does_not_touch_output);
    RUN_TEST(test_missing_truncated_or_extra_record_is_unknown);
    RUN_TEST(test_every_single_bit_corruption_is_rejected);
    RUN_TEST(test_future_version_wrong_family_and_reserved_byte_are_rejected);
    RUN_TEST(test_unknown_id_with_valid_checksum_is_rejected);
    RUN_TEST(test_noncanonical_padding_or_embedded_null_is_rejected);
    return UNITY_END();
}
