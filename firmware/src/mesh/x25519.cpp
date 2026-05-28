// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/x25519.cpp — X25519 implementation via mbedTLS ECP Montgomery
// Planning: §15.1 PKI | Roadmap: PKI/DM

#include "mesh/x25519.h"
#define MBEDTLS_ALLOW_PRIVATE_ACCESS
#include <mbedtls/ecp.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/entropy.h>
#include <Arduino.h>
#include <cstring>

#if defined(AIRTAK_PLATFORM_ESP32)
#include <esp_random.h>
#endif

namespace airtak {

namespace {

// RNG wrapper — on ESP32 uses hardware esp_random(), else CTR_DRBG.
#if defined(AIRTAK_PLATFORM_ESP32)
static int hwRngCallback(void*, unsigned char* out, size_t len) {
    while (len >= 4) {
        uint32_t r = esp_random();
        memcpy(out, &r, 4);
        out += 4; len -= 4;
    }
    if (len) {
        uint32_t r = esp_random();
        memcpy(out, &r, len);
    }
    return 0;
}
#define X25519_RNG_CALLBACK hwRngCallback
#define X25519_RNG_CTX       nullptr
static void ensureRngSeeded() {}
#else
static mbedtls_ctr_drbg_context s_drbg_ctx;
static mbedtls_entropy_context  s_entropy_ctx;
static bool s_drbg_seeded = false;

static void ensureRngSeeded() {
    if (s_drbg_seeded) return;
    mbedtls_ctr_drbg_init(&s_drbg_ctx);
    mbedtls_entropy_init(&s_entropy_ctx);
    mbedtls_ctr_drbg_seed(&s_drbg_ctx, mbedtls_entropy_func,
                          &s_entropy_ctx, nullptr, 0);
    s_drbg_seeded = true;
}
#define X25519_RNG_CALLBACK mbedtls_ctr_drbg_random
#define X25519_RNG_CTX      (&s_drbg_ctx)
#endif
} // anonymous namespace

bool x25519_keypair(uint8_t pubkey_out[32], uint8_t privkey_out[32],
                    const uint8_t seed[32]) {
    ensureRngSeeded();

    mbedtls_ecp_group   grp;
    mbedtls_ecp_point   Q;
    mbedtls_mpi          d;

    mbedtls_ecp_group_init(&grp);
    mbedtls_ecp_point_init(&Q);
    mbedtls_mpi_init(&d);

    int ret = 0;

    ret = mbedtls_ecp_group_load(&grp, MBEDTLS_ECP_DP_CURVE25519);
    if (ret != 0) goto cleanup;

    {
        uint8_t clamped[32];
        memcpy(clamped, seed, 32);
        clamped[0]  &= 248;
        clamped[31] &= 127;
        clamped[31] |= 64;

        MBEDTLS_MPI_CHK(mbedtls_mpi_read_binary_le(&d, clamped, 32));
    }

    {
        mbedtls_ecp_point G;
        mbedtls_ecp_point_init(&G);

        MBEDTLS_MPI_CHK(mbedtls_mpi_lset(&G.X, 9));
        MBEDTLS_MPI_CHK(mbedtls_mpi_lset(&G.Z, 1));

        MBEDTLS_MPI_CHK(mbedtls_ecp_mul(&grp, &Q, &d, &G,
                X25519_RNG_CALLBACK, X25519_RNG_CTX));

        mbedtls_ecp_point_free(&G);
    }

    {
        size_t olen = 0;
        ret = mbedtls_ecp_point_write_binary(&grp, &Q,
                MBEDTLS_ECP_PF_COMPRESSED, &olen, pubkey_out, 32);
        if (ret != 0) goto cleanup;
        if (olen != 32) { ret = MBEDTLS_ERR_ECP_BAD_INPUT_DATA; goto cleanup; }
    }

    {
        uint8_t raw[32];
        MBEDTLS_MPI_CHK(mbedtls_mpi_write_binary_le(&d, raw, 32));
        memcpy(privkey_out, raw, 32);
    }

cleanup:
    mbedtls_ecp_group_free(&grp);
    mbedtls_ecp_point_free(&Q);
    mbedtls_mpi_free(&d);
    return (ret == 0);
}

bool x25519_shared_secret(uint8_t shared_out[32],
                          const uint8_t privkey[32],
                          const uint8_t pubkey[32]) {
    ensureRngSeeded();

    mbedtls_ecp_group   grp;
    mbedtls_ecp_point   Q;
    mbedtls_ecp_point   R;
    mbedtls_mpi          d;

    mbedtls_ecp_group_init(&grp);
    mbedtls_ecp_point_init(&Q);
    mbedtls_ecp_point_init(&R);
    mbedtls_mpi_init(&d);

    int ret = 0;

    ret = mbedtls_ecp_group_load(&grp, MBEDTLS_ECP_DP_CURVE25519);
    if (ret != 0) goto cleanup;

    {
        uint8_t clamped[32];
        memcpy(clamped, privkey, 32);
        clamped[0]  &= 248;
        clamped[31] &= 127;
        clamped[31] |= 64;

        MBEDTLS_MPI_CHK(mbedtls_mpi_read_binary_le(&d, clamped, 32));
    }

    {
        MBEDTLS_MPI_CHK(mbedtls_mpi_read_binary_le(&Q.X, pubkey, 32));
        MBEDTLS_MPI_CHK(mbedtls_mpi_lset(&Q.Z, 1));
        mbedtls_mpi_free(&Q.Y);
        mbedtls_mpi_init(&Q.Y);
    }

    MBEDTLS_MPI_CHK(mbedtls_ecp_mul(&grp, &R, &d, &Q,
            X25519_RNG_CALLBACK, X25519_RNG_CTX));

    {
        size_t olen = 0;
        ret = mbedtls_ecp_point_write_binary(&grp, &R,
                MBEDTLS_ECP_PF_COMPRESSED, &olen, shared_out, 32);
        if (ret != 0) goto cleanup;
        if (olen != 32) { ret = MBEDTLS_ERR_ECP_BAD_INPUT_DATA; goto cleanup; }
    }

    {
        uint8_t zero[32] = {};
        if (memcmp(shared_out, zero, 32) == 0) { ret = MBEDTLS_ERR_ECP_INVALID_KEY; }
    }

cleanup:
    mbedtls_ecp_group_free(&grp);
    mbedtls_ecp_point_free(&Q);
    mbedtls_ecp_point_free(&R);
    mbedtls_mpi_free(&d);
    return (ret == 0);
}

} // namespace airtak
